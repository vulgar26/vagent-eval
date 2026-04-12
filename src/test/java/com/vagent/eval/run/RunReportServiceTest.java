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

/**
 * {@link RunReportService#computeReport} 纯计算单测（不依赖 Spring / {@link RunStore}）。
 */
class RunReportServiceTest {

    private static EvalRun run(String runId, int totalCases, int completedCases) {
        return new EvalRun(
                runId,
                "ds",
                "probe",
                RunStatus.FINISHED,
                totalCases,
                completedCases,
                Instant.parse("2026-04-11T00:00:00Z"),
                Instant.parse("2026-04-11T00:00:01Z"),
                Instant.parse("2026-04-11T00:00:02Z"),
                ""
        );
    }

    private static EvalResult row(Verdict v, ErrorCode ec, long latencyMs) {
        return new EvalResult("run_x", "ds", "probe", "c", v, ec, latencyMs, Instant.now(), Map.of());
    }

    @Test
    void passAndSkippedRates_overTotalCases_incompleteRun() {
        List<EvalResult> results = List.of(
                row(Verdict.PASS, null, 10),
                row(Verdict.PASS, null, 10),
                row(Verdict.SKIPPED_UNSUPPORTED, ErrorCode.SKIPPED_UNSUPPORTED, 5),
                row(Verdict.FAIL, ErrorCode.UPSTREAM_UNAVAILABLE, 1)
        );
        Map<String, Object> rep = RunReportService.computeReport(run("run_x", 10, 4), results, 5);
        assertThat(rep.get("pass_count")).isEqualTo(2);
        assertThat(rep.get("skipped_count")).isEqualTo(1);
        assertThat(rep.get("fail_count")).isEqualTo(1);
        assertThat(rep.get("pass_rate")).isEqualTo(0.2);
        assertThat(rep.get("skipped_rate")).isEqualTo(0.1);
    }

    @Test
    void noResultsYet_ratesNull_p95Null() {
        Map<String, Object> rep = RunReportService.computeReport(run("run_x", 10, 0), List.of(), 5);
        assertThat(rep.get("pass_rate")).isNull();
        assertThat(rep.get("skipped_rate")).isNull();
        assertThat(rep.get("p95_latency_ms")).isNull();
        assertThat(rep.get("latency_sample_count")).isEqualTo(0);
    }

    @Test
    void p95NearestRank_fourSamples() {
        assertThat(RunReportService.p95NearestRank(List.of(10L, 20L, 30L, 40L))).isEqualTo(40L);
        assertThat(RunReportService.p95NearestRank(List.of(100L))).isEqualTo(100L);
        assertThat(RunReportService.p95NearestRank(List.of())).isNull();
    }

    @Test
    void errorCodeTopN_sortsByCountThenName() {
        List<EvalResult> results = List.of(
                row(Verdict.FAIL, ErrorCode.UNKNOWN, 1),
                row(Verdict.FAIL, ErrorCode.UNKNOWN, 1),
                row(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, 1),
                row(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, 1),
                row(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, 1),
                row(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, 1),
                row(Verdict.PASS, null, 1)
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) RunReportService.computeReport(run("r", 10, 7), results, 2)
                .get("error_code_counts");
        assertThat(top).hasSize(2);
        assertThat(top.get(0).get("error_code")).isEqualTo("CONTRACT_VIOLATION");
        assertThat(top.get(0).get("count")).isEqualTo(4);
        assertThat(top.get(1).get("error_code")).isEqualTo("UNKNOWN");
        assertThat(top.get(1).get("count")).isEqualTo(2);
    }

    @Test
    void markdownSummaryContainsKeyLines() {
        List<EvalResult> results = List.of(row(Verdict.PASS, null, 10));
        String md = (String) RunReportService.computeReport(run("run_ab", 1, 1), results, 5).get("markdown_summary");
        assertThat(md).contains("run_ab");
        assertThat(md).contains("pass_rate:");
        assertThat(md).contains("p95_latency_ms:");
    }
}
