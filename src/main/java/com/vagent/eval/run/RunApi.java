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
 * Day3：Run API（创建 / 查询 / 取消 / 结果列表）。
 * <p>
 * P0 允许串行执行，因此 createRun 后立即后台启动执行线程；前端/脚本用 GET 查询进度与结果。
 */
@RestController
@RequestMapping(path = "/api/v1/eval/runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class RunApi {

    private final DatasetStore datasetStore;
    private final RunStore runStore;
    private final RunRunner runRunner;

    public RunApi(DatasetStore datasetStore, RunStore runStore, RunRunner runRunner) {
        this.datasetStore = datasetStore;
        this.runStore = runStore;
        this.runRunner = runRunner;
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

    @GetMapping("/{runId}/results")
    public Map<String, Object> results(@PathVariable String runId,
                                       @RequestParam(defaultValue = "0") int offset,
                                       @RequestParam(defaultValue = "50") int limit) {
        List<EvalResult> rows = runStore.listResults(runId, offset, Math.min(limit, 200));
        return Map.of(
                "run_id", runId,
                "offset", offset,
                "limit", Math.min(limit, 200),
                "results", rows
        );
    }
}

