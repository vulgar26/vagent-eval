package com.vagent.eval.run;

import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import com.vagent.eval.run.RunModel.RunStatus;
import com.vagent.eval.run.RunModel.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunCompareServiceTest {

    private static EvalRun run(String runId, String ds, int totalCases, int completed) {
        return new EvalRun(
                runId,
                ds,
                "probe",
                RunStatus.FINISHED,
                totalCases,
                completed,
                Instant.parse("2026-04-11T00:00:00Z"),
                Instant.parse("2026-04-11T00:00:01Z"),
                Instant.parse("2026-04-11T00:00:02Z"),
                ""
        );
    }

    private static EvalResult row(String caseId, Verdict v, ErrorCode ec) {
        return new EvalResult("run_x", "ds1", "probe", caseId, v, ec, 10L, Instant.now(), null, Map.of());
    }

    private static EvalResult rowMeta(String runId, String caseId, Verdict v, ErrorCode ec, Map<String, Object> meta) {
        return new EvalResult(runId, "ds1", "probe", caseId, v, ec, 10L, Instant.now(), meta, Map.of());
    }

    private static EvalProperties propertiesWithMetaTraceKeys(String... keys) {
        EvalProperties p = new EvalProperties();
        EvalProperties.TargetConfig probeTarget = new EvalProperties.TargetConfig();
        probeTarget.setTargetId("probe");
        probeTarget.setMetaTraceKeys(List.of(keys));
        p.setTargets(List.of(probeTarget));
        return p;
    }

    @Test
    void resultsPath_encodesCaseId() {
        assertThat(RunCompareService.resultsPath("run_a", "a/b"))
                .contains("case_id=a%2Fb");
    }

    @Test
    void regression_improvement_and_missing() {
        EvalRun base = run("run_b", "ds1", 3, 3);
        EvalRun cand = run("run_c", "ds1", 3, 2);
        List<EvalResult> baseR = List.of(
                row("c1", Verdict.PASS, null),
                row("c2", Verdict.FAIL, ErrorCode.UNKNOWN),
                row("c3", Verdict.PASS, null)
        );
        List<EvalResult> candR = List.of(
                row("c1", Verdict.FAIL, ErrorCode.TIMEOUT),
                row("c2", Verdict.PASS, null)
        );
        Map<String, Object> out = RunCompareService.compareRuns("run_b", "run_c", base, baseR, cand, candR);

        assertThat(out.get("compare_version")).isEqualTo(RunCompareService.COMPARE_VERSION);
        assertThat(out.get("pass_rate_delta")).isEqualTo(-1.0 / 3.0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reg = (List<Map<String, Object>>) out.get("regressions");
        assertThat(reg).hasSize(1);
        assertThat(reg.get(0).get("case_id")).isEqualTo("c1");
        assertThat(reg.get(0).get("cand_results_path")).isEqualTo("/api/v1/eval/runs/run_c/results?case_id=c1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> imp = (List<Map<String, Object>>) out.get("improvements");
        assertThat(imp).hasSize(1);
        assertThat(imp.get(0).get("case_id")).isEqualTo("c2");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> miss = (List<Map<String, Object>>) out.get("missing_in_cand");
        assertThat(miss).hasSize(1);
        assertThat(miss.get(0).get("case_id")).isEqualTo("c3");
    }

    @Test
    void datasetMismatch_throws() {
        EvalRun base = run("b", "ds1", 1, 1);
        EvalRun cand = run("c", "ds2", 1, 1);
        assertThatThrownBy(() -> RunCompareService.compareRuns("b", "c", base, List.of(row("c1", Verdict.PASS, null)), cand, List.of(row("c1", Verdict.PASS, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same dataset_id");
    }

    @Test
    void compareRuns_metaTraceCopiesOnlyConfiguredKeysForTarget() {
        EvalProperties p = new EvalProperties();
        EvalProperties.TargetConfig probeTarget = new EvalProperties.TargetConfig();
        probeTarget.setTargetId("probe");
        probeTarget.setMetaTraceKeys(List.of("retrieve_hit_count", "custom_travel_metric"));
        p.setTargets(List.of(probeTarget));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("retrieve_hit_count", 3);
        meta.put("hybrid_lexical_mode", "bm25");
        meta.put("custom_travel_metric", "ignored_in_probe_samples");

        EvalResult br = new EvalResult(
                "run_b", "ds1", "probe", "c1", Verdict.PASS, null, 10L, Instant.now(), meta, Map.of());
        EvalResult cr = new EvalResult(
                "run_c", "ds1", "probe", "c1", Verdict.FAIL, ErrorCode.TIMEOUT, 10L, Instant.now(), meta, Map.of());

        EvalRun base = run("run_b", "ds1", 1, 1);
        EvalRun cand = run("run_c", "ds1", 1, 1);
        Map<String, Object> out = RunCompareService.compareRuns("run_b", "run_c", base, List.of(br), cand, List.of(cr), p);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reg = (List<Map<String, Object>>) out.get("regressions");
        assertThat(reg).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> baseTrace = (Map<String, Object>) reg.get(0).get("base_meta_trace");
        assertThat(baseTrace).containsOnlyKeys("retrieve_hit_count", "custom_travel_metric");
        assertThat(baseTrace.get("retrieve_hit_count")).isEqualTo(3);
        assertThat(baseTrace.get("custom_travel_metric")).isEqualTo("ignored_in_probe_samples");
    }

    @Test
    void metaTraceDiff_reportsChangedWorkflowId() {
        EvalProperties p = propertiesWithMetaTraceKeys("workflow_id");
        EvalResult br = rowMeta("run_b", "c1", Verdict.PASS, null,
                Map.of("workflow_id", "market_data_explain"));
        EvalResult cr = rowMeta("run_c", "c1", Verdict.FAIL, ErrorCode.TIMEOUT,
                Map.of("workflow_id", "market_data_explain_v2"));

        Map<String, Object> out = RunCompareService.compareRuns(
                "run_b", "run_c",
                run("run_b", "ds1", 1, 1), List.of(br),
                run("run_c", "ds1", 1, 1), List.of(cr),
                p);

        Map<String, Object> diff = firstRegressionMetaTraceDiff(out);
        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) diff.get("workflow_id");
        assertThat(workflow.get("base")).isEqualTo("market_data_explain");
        assertThat(workflow.get("cand")).isEqualTo("market_data_explain_v2");
    }

    @Test
    void metaTraceDiff_omitsEqualValues() {
        EvalProperties p = propertiesWithMetaTraceKeys("workflow_id");
        EvalResult br = rowMeta("run_b", "c1", Verdict.PASS, null,
                Map.of("workflow_id", "market_data_explain"));
        EvalResult cr = rowMeta("run_c", "c1", Verdict.FAIL, ErrorCode.TIMEOUT,
                Map.of("workflow_id", "market_data_explain"));

        Map<String, Object> out = RunCompareService.compareRuns(
                "run_b", "run_c",
                run("run_b", "ds1", 1, 1), List.of(br),
                run("run_c", "ds1", 1, 1), List.of(cr),
                p);

        assertThat(firstRegression(out)).doesNotContainKey("meta_trace_diff");
    }

    @Test
    void metaTraceDiff_ignoresMissingTraceKeys() {
        EvalProperties p = propertiesWithMetaTraceKeys("workflow_family");
        EvalResult br = rowMeta("run_b", "c1", Verdict.PASS, null,
                Map.of("workflow_id", "market_data_explain"));
        EvalResult cr = rowMeta("run_c", "c1", Verdict.FAIL, ErrorCode.TIMEOUT,
                Map.of("workflow_id", "market_data_explain_v2"));

        Map<String, Object> out = RunCompareService.compareRuns(
                "run_b", "run_c",
                run("run_b", "ds1", 1, 1), List.of(br),
                run("run_c", "ds1", 1, 1), List.of(cr),
                p);

        Map<String, Object> row = firstRegression(out);
        @SuppressWarnings("unchecked")
        Map<String, Object> baseTrace = (Map<String, Object>) row.get("base_meta_trace");
        @SuppressWarnings("unchecked")
        Map<String, Object> candTrace = (Map<String, Object>) row.get("cand_meta_trace");
        assertThat(baseTrace).doesNotContainKey("workflow_id");
        assertThat(candTrace).doesNotContainKey("workflow_id");
        assertThat(row).doesNotContainKey("meta_trace_diff");
    }

    @Test
    void metaTraceDiff_reportsMissingOnOneSide() {
        EvalProperties p = propertiesWithMetaTraceKeys("workflow_id");
        EvalResult br = rowMeta("run_b", "c1", Verdict.PASS, null,
                Map.of("workflow_id", "market_data_explain"));
        EvalResult cr = rowMeta("run_c", "c1", Verdict.FAIL, ErrorCode.TIMEOUT, Map.of());

        Map<String, Object> out = RunCompareService.compareRuns(
                "run_b", "run_c",
                run("run_b", "ds1", 1, 1), List.of(br),
                run("run_c", "ds1", 1, 1), List.of(cr),
                p);

        Map<String, Object> diff = firstRegressionMetaTraceDiff(out);
        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) diff.get("workflow_id");
        assertThat(workflow.get("base")).isEqualTo("market_data_explain");
        assertThat(workflow).containsEntry("cand", null);
    }

    @Test
    void metaTraceDiff_handlesArrayOrObjectValues() {
        EvalProperties p = propertiesWithMetaTraceKeys("tool_trace", "evidence_summary");
        EvalResult br = rowMeta("run_b", "c1", Verdict.PASS, null, Map.of(
                "tool_trace", List.of(Map.of("tool_name", "market_data", "outcome", "ok")),
                "evidence_summary", Map.of("source_count", 1)
        ));
        EvalResult cr = rowMeta("run_c", "c1", Verdict.FAIL, ErrorCode.TIMEOUT, Map.of(
                "tool_trace", List.of(Map.of("tool_name", "market_data", "outcome", "timeout")),
                "evidence_summary", Map.of("source_count", 1)
        ));

        Map<String, Object> out = RunCompareService.compareRuns(
                "run_b", "run_c",
                run("run_b", "ds1", 1, 1), List.of(br),
                run("run_c", "ds1", 1, 1), List.of(cr),
                p);

        Map<String, Object> diff = firstRegressionMetaTraceDiff(out);
        assertThat(diff).containsOnlyKeys("tool_trace");
    }

    private static Map<String, Object> firstRegression(Map<String, Object> out) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regressions = (List<Map<String, Object>>) out.get("regressions");
        assertThat(regressions).hasSize(1);
        return regressions.get(0);
    }

    private static Map<String, Object> firstRegressionMetaTraceDiff(Map<String, Object> out) {
        @SuppressWarnings("unchecked")
        Map<String, Object> diff = (Map<String, Object>) firstRegression(out).get("meta_trace_diff");
        assertThat(diff).isNotNull();
        return diff;
    }
}
