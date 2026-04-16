package com.vagent.eval.run;

import com.vagent.eval.dataset.DatasetStore;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P0+ S3：按 dataset {@code tags} 前缀对单次 run 分桶出子报表。
 * <p>
 * 口径：每个 bucket 的分母为「该 bucket 命中的 case 数」，与 {@link RunReportService} 的全卷分母（total_cases）不同；
 * 这样便于周报直接贴 attack/rag 子集通过率。
 */
@Service
public class RunBucketReportService {

    public static final String BUCKETS_VERSION = "run.buckets.v1";

    private final DatasetStore datasetStore;
    private final RunStore runStore;

    public RunBucketReportService(DatasetStore datasetStore, RunStore runStore) {
        this.datasetStore = datasetStore;
        this.runStore = runStore;
    }

    /**
     * @param runId         已存在的 run
     * @param tagPrefixes   bucket 前缀列表（例如 {@code attack/}、{@code rag/empty}）；空则使用默认集合
     * @param errorCodeTopN 每个 bucket 的 error_code TopN 长度
     */
    public Map<String, Object> buildBuckets(String runId, List<String> tagPrefixes, int errorCodeTopN) {
        EvalRun run = runStore.getRun(runId).orElseThrow(() -> new NoSuchElementException("run not found"));
        List<EvalResult> results = runStore.listAllResults(runId);

        List<EvalCase> cases = datasetStore.listAllCases(run.datasetId());
        Map<String, Set<String>> tagsByCaseId = cases.stream().collect(Collectors.toMap(
                EvalCase::caseId,
                c -> Set.copyOf(c.tags()),
                (a, b) -> a,
                LinkedHashMap::new
        ));

        List<String> prefixes = normalizePrefixes(tagPrefixes);
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (String prefix : prefixes) {
            buckets.add(buildOneBucket(run, results, tagsByCaseId, prefix, errorCodeTopN));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buckets_version", BUCKETS_VERSION);
        out.put("run_id", runId);
        out.put("dataset_id", run.datasetId());
        out.put("target_id", run.targetId());
        out.put("tag_prefixes", prefixes);
        out.put("buckets", buckets);
        return out;
    }

    private static Map<String, Object> buildOneBucket(
            EvalRun run,
            List<EvalResult> allResults,
            Map<String, Set<String>> tagsByCaseId,
            String prefix,
            int errorCodeTopN
    ) {
        List<EvalResult> filtered = allResults.stream()
                .filter(r -> hasTagPrefix(tagsByCaseId.get(r.caseId()), prefix))
                .toList();

        int bucketTotal = (int) tagsByCaseId.entrySet().stream()
                .filter(e -> hasTagPrefix(e.getValue(), prefix))
                .count();

        // 用 computeReport 的聚合逻辑，但把分母口径改为「bucket_total」。
        EvalRun bucketRun = new EvalRun(
                run.runId(),
                run.datasetId(),
                run.targetId(),
                run.status(),
                bucketTotal,
                filtered.size(),
                run.createdAt(),
                run.startedAt(),
                run.finishedAt(),
                run.cancelReason()
        );

        Map<String, Object> rep = RunReportService.computeReport(bucketRun, filtered, errorCodeTopN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tag_prefix", prefix);
        out.put("bucket_total_cases", bucketTotal);
        out.put("bucket_results_count", filtered.size());
        out.put("report", rep);
        return out;
    }

    private static boolean hasTagPrefix(Set<String> tags, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (String t : tags) {
            if (t != null && t.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizePrefixes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of("attack/", "rag/empty", "rag/low_conf");
        }
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String p = s.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            return List.of("attack/", "rag/empty", "rag/low_conf");
        }
        return List.copyOf(out);
    }
}

