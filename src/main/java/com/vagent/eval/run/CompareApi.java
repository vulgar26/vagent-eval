package com.vagent.eval.run;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Day8：对比 API（base run vs cand run）。
 * <p>
 * 与 {@link RunApi} 分列，避免 runs 资源下出现语义不清的子路径；本控制器挂在 {@code /api/v1/eval/compare}。
 */
@RestController
@RequestMapping(path = "/api/v1/eval", produces = MediaType.APPLICATION_JSON_VALUE)
public class CompareApi {

    private final RunCompareService runCompareService;

    public CompareApi(RunCompareService runCompareService) {
        this.runCompareService = runCompareService;
    }

    /**
     * {@code compare} v1：{@link RunCompareService#compare(String, String)}。
     *
     * @param baseRunId 基线 run id
     * @param candRunId 候选 run id（须与 base 同一 {@code dataset_id}）
     * @return 含 {@code pass_rate_delta}、{@code regressions}、{@code improvements}、{@code missing_in_cand} 等
     */
    @GetMapping("/compare")
    public Map<String, Object> compare(
            @RequestParam(name = "base_run_id") String baseRunId,
            @RequestParam(name = "cand_run_id") String candRunId
    ) {
        return runCompareService.compare(baseRunId, candRunId);
    }
}
