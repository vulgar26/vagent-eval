package com.vagent.eval.run;

import com.vagent.eval.dataset.DatasetStore;
import com.vagent.eval.dataset.InMemoryDatasetStore;
import com.vagent.eval.dataset.EvalExpectedBehavior;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.dataset.Model.EvalDataset;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import com.vagent.eval.run.RunModel.RunStatus;
import com.vagent.eval.run.RunModel.Verdict;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RunBucketReportService} 单测：验证 tag 前缀分桶 + 分母口径（bucket_total_cases）。
 */
class RunBucketReportServiceTest {

    @Test
    void buckets_filterByTagPrefix_andUseBucketTotalAsRateDenominator() {
        DatasetStore datasets = new InMemoryDatasetStore();
        RunStore runs = Mockito.mock(RunStore.class);
        RunBucketReportService svc = new RunBucketReportService(datasets, runs);

        EvalDataset ds = datasets.createDataset("bucket-test", "1", "unit test");
        String datasetId = ds.datasetId();

        Instant createdAt = Instant.parse("2026-04-14T00:00:00Z");
        datasets.appendCases(datasetId, List.of(
                new EvalCase("a1", datasetId, "q1", EvalExpectedBehavior.ANSWER, false, List.of("attack/x"), createdAt),
                new EvalCase("a2", datasetId, "q2", EvalExpectedBehavior.ANSWER, false, List.of("attack/y"), createdAt),
                new EvalCase("r1", datasetId, "q3", EvalExpectedBehavior.ANSWER, false, List.of("rag/foo"), createdAt),
                new EvalCase("x1", datasetId, "q4", EvalExpectedBehavior.ANSWER, false, List.of("other"), createdAt)
        ));

        String runId = "run_bucket_test";
        EvalRun run = new EvalRun(
                runId,
                datasetId,
                "probe",
                RunStatus.PENDING,
                4,
                0,
                createdAt,
                null,
                null,
                ""
        );

        List<EvalResult> results = List.of(
                new EvalResult(runId, datasetId, "probe", "a1", Verdict.PASS, null, 10, createdAt, null, Map.of()),
                new EvalResult(runId, datasetId, "probe", "a2", Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, 11, createdAt, null, Map.of()),
                new EvalResult(runId, datasetId, "probe", "r1", Verdict.PASS, null, 12, createdAt, null, Map.of()),
                new EvalResult(runId, datasetId, "probe", "x1", Verdict.PASS, null, 13, createdAt, null, Map.of())
        );

        Mockito.when(runs.getRun(runId)).thenReturn(java.util.Optional.of(run));
        Mockito.when(runs.listAllResults(runId)).thenReturn(results);

        Map<String, Object> out = svc.buildBuckets(runId, List.of("attack/", "rag/"), 5);
        assertThat(out.get("buckets_version")).isEqualTo(RunBucketReportService.BUCKETS_VERSION);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).hasSize(2);

        Map<String, Object> attack = buckets.stream()
                .filter(b -> "attack/".equals(b.get("tag_prefix")))
                .findFirst()
                .orElseThrow();
        assertThat(attack.get("bucket_total_cases")).isEqualTo(2);
        assertThat(attack.get("bucket_results_count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> attackRep = (Map<String, Object>) attack.get("report");
        assertThat(attackRep.get("pass_count")).isEqualTo(1);
        assertThat(attackRep.get("fail_count")).isEqualTo(1);
        assertThat(attackRep.get("pass_rate")).isEqualTo(0.5);

        Map<String, Object> rag = buckets.stream()
                .filter(b -> "rag/".equals(b.get("tag_prefix")))
                .findFirst()
                .orElseThrow();
        assertThat(rag.get("bucket_total_cases")).isEqualTo(1);
        assertThat(rag.get("bucket_results_count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> ragRep = (Map<String, Object>) rag.get("report");
        assertThat(ragRep.get("pass_count")).isEqualTo(1);
        assertThat(ragRep.get("pass_rate")).isEqualTo(1.0);
    }

    @Test
    void defaultPrefixes_returnThreeBuckets_evenIfEmpty() {
        DatasetStore datasets = new InMemoryDatasetStore();
        RunStore runs = Mockito.mock(RunStore.class);
        RunBucketReportService svc = new RunBucketReportService(datasets, runs);

        EvalDataset ds = datasets.createDataset("bucket-defaults", "1", "unit test");
        String datasetId = ds.datasetId();
        Instant createdAt = Instant.parse("2026-04-14T00:00:00Z");
        datasets.appendCases(datasetId, List.of(
                new EvalCase("c1", datasetId, "q", EvalExpectedBehavior.ANSWER, false, List.of("misc"), createdAt)
        ));

        String runId = "run_bucket_defaults_test";
        EvalRun run = new EvalRun(
                runId,
                datasetId,
                "probe",
                RunStatus.PENDING,
                1,
                0,
                createdAt,
                null,
                null,
                ""
        );

        List<EvalResult> results = List.of(
                new EvalResult(runId, datasetId, "probe", "c1", Verdict.PASS, null, 1, createdAt, null, Map.of())
        );

        Mockito.when(runs.getRun(runId)).thenReturn(java.util.Optional.of(run));
        Mockito.when(runs.listAllResults(runId)).thenReturn(results);

        Map<String, Object> out = svc.buildBuckets(runId, null, 5);
        @SuppressWarnings("unchecked")
        List<String> prefixes = (List<String>) out.get("tag_prefixes");
        assertThat(prefixes).containsExactly("attack/", "rag/empty", "rag/low_conf");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).hasSize(3);
        for (Map<String, Object> b : buckets) {
            assertThat(b.get("bucket_total_cases")).isEqualTo(0);
            assertThat(b.get("bucket_results_count")).isEqualTo(0);
        }
    }
}
