package com.vagent.eval.run;

import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.dataset.DatasetStore;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.observability.EvalMetrics;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Day3/Day6：串行执行器。
 * <p>
 * 人话：把一个 dataset 的所有题按顺序发给 target，一题一条结果地记录下来。
 * 取消逻辑：每跑下一题前检查 cancelRequested，若为 true 则停止并把 run 标记为 CANCELLED。
 * <p>
 * Day6：每题调用 {@link TargetClient#postEvalChat} 时已带上 membership 相关头；
 * 调用 {@link RunEvaluator#evaluate} 时传入 {@code run.targetId()}，供 {@link CitationMembership} 绑定 target。
 */
@Component
public class RunRunner {

    private static final Logger log = LoggerFactory.getLogger(RunRunner.class);

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
    private final EvalProperties evalProperties;
    private final EvalMetrics evalMetrics;
    private final Semaphore permits;

    public RunRunner(
            DatasetStore datasetStore,
            RunStore runStore,
            TargetClient targetClient,
            RunEvaluator evaluator,
            EvalProperties evalProperties,
            EvalMetrics evalMetrics
    ) {
        this.datasetStore = datasetStore;
        this.runStore = runStore;
        this.targetClient = targetClient;
        this.evaluator = evaluator;
        this.evalProperties = evalProperties;
        this.evalMetrics = evalMetrics;
        int max = evalProperties == null ? 8 : evalProperties.getRunner().getMaxConcurrency();
        this.permits = new Semaphore(Math.max(1, max), true);
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

            // 阶段二：结构化日志（key=value），便于 grep/采集；不打印题干/答案全文
            log.info(
                    "eval_event=run_started run_id={} dataset_id={} target_id={} total_cases={}",
                    runId,
                    run.datasetId(),
                    run.targetId(),
                    run.totalCases()
            );

            // 取 target 配置
            EvalProperties.TargetConfig tc = targetClient.findTarget(run.targetId())
                    .filter(EvalProperties.TargetConfig::isEnabled)
                    .orElseThrow(() -> new IllegalArgumentException("target not found or disabled: target_id=" + run.targetId()));

            List<EvalCase> cases = datasetStore.listAllCases(run.datasetId());
            for (EvalCase c : cases) {
                if (runStore.isCancelRequested(runId)) {
                    runStore.markCancelled(runId, runStore.getRun(runId).map(EvalRun::cancelReason).orElse(""));
                    evalMetrics.runTerminal(run.targetId(), "CANCELLED");
                    log.info(
                            "eval_event=run_terminal run_id={} dataset_id={} target_id={} status=CANCELLED reason=user_cancel",
                            runId,
                            run.datasetId(),
                            run.targetId()
                    );
                    return;
                }

                EvalResult r = runOneCase(run, tc.getBaseUrl(), c);
                runStore.appendResult(r);
                evalMetrics.caseResult(run.targetId(), r.verdict(), r.errorCode());
                log.info(
                        "eval_event=case_finished run_id={} dataset_id={} target_id={} case_id={} verdict={} error_code={} latency_ms={}",
                        runId,
                        run.datasetId(),
                        run.targetId(),
                        c.caseId(),
                        r.verdict(),
                        r.errorCode() == null ? "NONE" : r.errorCode().name(),
                        r.latencyMs()
                );

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
                evalMetrics.runTerminal(run.targetId(), "CANCELLED");
                log.info(
                        "eval_event=run_terminal run_id={} dataset_id={} target_id={} status=CANCELLED reason=user_cancel_end",
                        runId,
                        run.datasetId(),
                        run.targetId()
                );
                return;
            }
            runStore.markFinished(runId);
            evalMetrics.runTerminal(run.targetId(), "FINISHED");
            log.info(
                    "eval_event=run_terminal run_id={} dataset_id={} target_id={} status=FINISHED",
                    runId,
                    run.datasetId(),
                    run.targetId()
            );
        } catch (Exception e) {
            // P0：避免线程异常导致 run 永远停在 RUNNING（无法验收/无法排障）
            log.error("RunRunner crashed: runId={}", runId, e);
            String msg = e.getMessage();
            String reason = "runner_error:" + e.getClass().getSimpleName();
            if (msg != null && !msg.isBlank()) {
                reason += ":" + truncate(msg.trim(), 200);
            }
            runStore.markCancelled(runId, reason);
            try {
                EvalRun r = runStore.getRun(runId).orElse(null);
                if (r != null) {
                    evalMetrics.runTerminal(r.targetId(), "CANCELLED");
                    log.info(
                            "eval_event=run_terminal run_id={} dataset_id={} target_id={} status=CANCELLED reason=runner_crash",
                            runId,
                            r.datasetId(),
                            r.targetId()
                    );
                }
            } catch (Exception ignored) {
                // 指标/日志不应影响取消落库
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    /**
     * 执行单道 case：HTTP 调用 +（成功路径下）{@link RunEvaluator} 判定，并组装 {@link EvalResult}。
     * <p>
     * 异常与超时在非 2xx 分支外统一落入 {@code UPSTREAM_UNAVAILABLE} / {@code TIMEOUT} 等，避免线程静默死。
     */
    private EvalResult runOneCase(EvalRun run, String baseUrl, EvalCase c) {
        Instant now = Instant.now();
        Map<String, Object> debug = new HashMap<>();
        long t0 = System.nanoTime();
        Verdict verdict;
        ErrorCode errorCode;
        boolean acquired = false;

        try {
            int timeoutMs = evalProperties == null ? 0 : evalProperties.getRunner().getAcquireTimeoutMs();
            acquired = permits.tryAcquire(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
            if (!acquired) {
                evalMetrics.runnerSemaphoreTimeout(run.targetId());
                debug.put("verdict_reason", "eval_rate_limited");
                verdict = Verdict.FAIL;
                errorCode = ErrorCode.RATE_LIMITED;
                long latencyMs = (System.nanoTime() - t0) / 1_000_000L;
                return new EvalResult(run.runId(), run.datasetId(), run.targetId(), c.caseId(), verdict,
                        errorCode, latencyMs, now, debug);
            }

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
                // Day4：2xx 但 body 非 JSON → PARSE_ERROR（与 CONTRACT_VIOLATION 区分）
                if (tr.json() == null || tr.json().isNull()) {
                    verdict = Verdict.FAIL;
                    errorCode = ErrorCode.PARSE_ERROR;
                    debug.put("parse_error", "body_not_json");
                    return new EvalResult(run.runId(), run.datasetId(), run.targetId(), c.caseId(), verdict,
                            errorCode, latencyMs, now, debug);
                }
                RunEvaluator.EvalOutcome o = evaluator.evaluate(c, tr.json(), run.targetId());
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
            String msg = e.getMessage();
            if (msg != null && !msg.isBlank()) {
                debug.put("exception_message", msg);
            }
        }
        finally {
            if (acquired) {
                permits.release();
            }
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

