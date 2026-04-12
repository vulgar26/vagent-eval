package com.vagent.eval.run;

import com.vagent.eval.dataset.DatasetStore;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Day3/Day7：Run API（创建 / 查询 / 取消 / 结果列表 / 汇总报表）。
 * <p>
 * P0 允许串行执行，因此 createRun 后立即后台启动执行线程；前端/脚本用 GET 查询进度与结果。
 * Day7 增加 {@code GET .../report}，输出 {@link RunReportService} 计算的 v1 指标。
 */
@RestController
@RequestMapping(path = "/api/v1/eval/runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class RunApi {

    private static final int REPORT_ERROR_CODE_TOP_N_MIN = 1;
    private static final int REPORT_ERROR_CODE_TOP_N_MAX = 20;

    private final DatasetStore datasetStore;
    private final RunStore runStore;
    private final RunRunner runRunner;
    private final RunReportService runReportService;

    public RunApi(DatasetStore datasetStore, RunStore runStore, RunRunner runRunner, RunReportService runReportService) {
        this.datasetStore = datasetStore;
        this.runStore = runStore;
        this.runRunner = runRunner;
        this.runReportService = runReportService;
    }

    public record CreateRunRequest(String datasetId, String targetId) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public EvalRun create(@RequestBody CreateRunRequest req) {
        if (req == null || req.datasetId() == null || req.targetId() == null) {
            throw new IllegalArgumentException("dataset_id and target_id are required");
        }
        String datasetId = req.datasetId().trim();
        String targetId = req.targetId().trim();
        datasetStore.getDataset(datasetId).orElseThrow(() -> new NoSuchElementException("dataset not found"));

        int total = datasetStore.caseCount(datasetId);
        EvalRun run = runStore.createRun(datasetId, targetId, total);
        runRunner.startAsync(run.runId());
        return run;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("runs", runStore.listRuns());
    }

    @GetMapping("/{runId}")
    public EvalRun get(@PathVariable String runId) {
        return runStore.getRun(runId).orElseThrow(() -> new NoSuchElementException("run not found"));
    }

    @PostMapping("/{runId}/cancel")
    public EvalRun cancel(@PathVariable String runId, @RequestBody(required = false) Map<String, Object> body) {
        String reason = "";
        if (body != null && body.get("reason") != null) {
            reason = String.valueOf(body.get("reason"));
        }
        runStore.requestCancel(runId, reason);
        // 如果还没开始或正在跑，runner 会在下一题前停下并标记 CANCELLED。
        return runStore.getRun(runId).orElseThrow(() -> new NoSuchElementException("run not found"));
    }

    /**
     * @param caseId 可选；若指定则只返回该 {@code case_id} 的结果（仍受 offset/limit 约束），供 Day8 compare 回归项直达明细。
     */
    @GetMapping("/{runId}/results")
    public Map<String, Object> results(@PathVariable String runId,
                                       @RequestParam(defaultValue = "0") int offset,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(name = "case_id", required = false) String caseId) {
        int lim = Math.min(limit, 200);
        List<EvalResult> rows = runStore.listResults(runId, offset, lim, caseId);
        return Map.of(
                "run_id", runId,
                "offset", offset,
                "limit", lim,
                "results", rows
        );
    }

    /**
     * Day7：{@code run.report} v1 —— pass_rate、skipped_rate、p95_latency_ms、error_code TopN 等。
     * <p>
     * {@code error_code_top_n} 缺省 5，超出 [1, 20] 时裁剪到区间内，
     * 避免过大查询拖慢响应。
     *
     * @param runId           已存在的 run
     * @param errorCodeTopN   错误码榜单长度
     * @return 报告 JSON；含 {@code markdown_summary} 便于直接粘贴到周报
     */
    @GetMapping("/{runId}/report")
    public Map<String, Object> report(
            @PathVariable String runId,
            @RequestParam(name = "error_code_top_n", defaultValue = "5") int errorCodeTopN
    ) {
        int n = Math.min(REPORT_ERROR_CODE_TOP_N_MAX, Math.max(REPORT_ERROR_CODE_TOP_N_MIN, errorCodeTopN));
        return runReportService.buildReport(runId, n);
    }
}

