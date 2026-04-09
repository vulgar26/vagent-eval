package com.vagent.eval.run;

import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.dataset.DatasetStore;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.Verdict;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Day3：串行执行器。
 * <p>
 * 人话：把一个 dataset 的所有题按顺序发给 target，一题一条结果地记录下来。
 * 取消逻辑：每跑下一题前检查 cancelRequested，若为 true 则停止并把 run 标记为 CANCELLED。
 */
@Component
public class RunRunner {

    /**
     * P0：为了让“取消 run”在本地可稳定复现，串行跑每条 case 之间默认 sleep 一小段时间。
     * <p>
     * 现实原因：当 target 连接失败（例如端口未开）时，每条 case 会在毫秒级失败，导致 run 在你发出 cancel 前就 FINISHED，
     * 从而无法展示 CANCELLED 的状态转换。这个 delay 让取消有一个可观测窗口。
     * <p>
     * 后续（P1）应改为可配置项（例如 eval.run.inter_case_sleep_ms），并在 CI/生产默认置 0。
     */
    private static final long INTER_CASE_SLEEP_MS = 50;

    private final DatasetStore datasetStore;
    private final RunStore runStore;
    private final TargetClient targetClient;
    private final RunEvaluator evaluator;

    public RunRunner(DatasetStore datasetStore, RunStore runStore, TargetClient targetClient, RunEvaluator evaluator) {
        this.datasetStore = datasetStore;
        this.runStore = runStore;
        this.targetClient = targetClient;
        this.evaluator = evaluator;
    }

    public void startAsync(String runId) {
        Thread t = new Thread(() -> execute(runId), "run-runner-" + runId);
        t.setDaemon(true);
        t.start();
    }

    private void execute(String runId) {
        runStore.markStarted(runId);
        try {
            EvalRun run = runStore.getRun(runId).orElseThrow(() -> new NoSuchElementException("run not found"));

            // 取 target 配置
            EvalProperties.TargetConfig tc = targetClient.findTarget(run.targetId())
                    .filter(EvalProperties.TargetConfig::isEnabled)
                    .orElseThrow(() -> new IllegalArgumentException("target not found or disabled"));

            List<EvalCase> cases = datasetStore.listAllCases(run.datasetId());
            for (EvalCase c : cases) {
                if (runStore.isCancelRequested(runId)) {
                    runStore.markCancelled(runId, runStore.getRun(runId).map(EvalRun::cancelReason).orElse(""));
                    return;
                }

                EvalResult r = runOneCase(run, tc.getBaseUrl(), c);
                runStore.appendResult(r);

                // 给 cancel 一个稳定的可观测窗口（见 INTER_CASE_SLEEP_MS 注释）
                if (INTER_CASE_SLEEP_MS > 0) {
                    try {
                        Thread.sleep(INTER_CASE_SLEEP_MS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            if (runStore.isCancelRequested(runId)) {
                runStore.markCancelled(runId, runStore.getRun(runId).map(EvalRun::cancelReason).orElse(""));
                return;
            }
            runStore.markFinished(runId);
        } catch (Exception e) {
            // P0：避免线程异常导致 run 永远停在 RUNNING（无法验收/无法排障）
            runStore.markCancelled(runId, "runner_error:" + e.getClass().getSimpleName());
        }
    }

    private EvalResult runOneCase(EvalRun run, String baseUrl, EvalCase c) {
        Instant now = Instant.now();
        Map<String, Object> debug = new HashMap<>();
        long t0 = System.nanoTime();
        Verdict verdict;
        ErrorCode errorCode;

        try {
            TargetClient.TargetResponse tr = targetClient.postEvalChat(run.targetId(), baseUrl, run.runId(), run.datasetId(), c.caseId(), c.question());
            long latencyMs = tr.latencyMs();

            if (tr.statusCode() < 200 || tr.statusCode() >= 300) {
                verdict = Verdict.FAIL;
                errorCode = (tr.statusCode() == 401 || tr.statusCode() == 403) ? ErrorCode.AUTH
                        : (tr.statusCode() == 429) ? ErrorCode.RATE_LIMITED
                        : ErrorCode.UPSTREAM_UNAVAILABLE;
                debug.put("http_status", tr.statusCode());
                return new EvalResult(run.runId(), run.datasetId(), run.targetId(), c.caseId(), verdict,
                        errorCode, latencyMs, now, debug);
            } else {
                RunEvaluator.EvalOutcome o = evaluator.evaluate(c, tr.json());
                verdict = o.verdict();
                errorCode = o.errorCode() == null ? null : o.errorCode();
                debug.putAll(o.debug());
                return new EvalResult(run.runId(), run.datasetId(), run.targetId(), c.caseId(), verdict,
                        errorCode == null ? null : errorCode, latencyMs, now, debug);
            }
        } catch (java.net.http.HttpTimeoutException e) {
            verdict = Verdict.FAIL;
            errorCode = ErrorCode.TIMEOUT;
            debug.put("exception", "timeout");
        } catch (Exception e) {
            verdict = Verdict.FAIL;
            errorCode = ErrorCode.UPSTREAM_UNAVAILABLE;
            debug.put("exception", e.getClass().getSimpleName());
        }

        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;
        return new EvalResult(
                run.runId(),
                run.datasetId(),
                run.targetId(),
                c.caseId(),
                verdict,
                errorCode == null ? (verdict == Verdict.PASS ? null : ErrorCode.UNKNOWN) : errorCode,
                latencyMs,
                now,
                debug
        );
    }
}

