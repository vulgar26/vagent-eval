package com.vagent.eval.run;

import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import com.vagent.eval.run.RunModel.RunStatus;
import com.vagent.eval.run.RunModel.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        return new EvalResult("run_x", "ds1", "probe", caseId, v, ec, 10L, Instant.now(), Map.of());
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
}
