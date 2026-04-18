package com.vagent.eval.run;

import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.dataset.DatasetStore;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import com.vagent.eval.run.RunModel.Verdict;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Day7：{@code run.report} v1 —— 对单次 run 的 {@link EvalResult} 做聚合，供日报/门禁/对比。
 * <p>
 * <strong>与明细 API 的关系</strong>：{@link RunApi} 的 {@code GET .../results} 返回逐题明细；本服务在此基础上
 * 计算通过率、跳过率、p95 时延、错误码 TopN，<strong>不修改</strong>存储、不改变判定规则。
 * <p>
 * <strong>报告版本</strong>：响应内 {@code report_version} 固定为 {@value #REPORT_VERSION}；v1 起在可提供 dataset case 定义时附加
 * {@code by_expected_behavior} / {@code by_requires_citations}（分母为该维度下的题数，与全卷 {@code pass_rate} 口径一致）。
 * {@value #SLICES_VERSION} 起每桶附带 {@code p95_latency_ms}、{@code error_code_counts} 等与全卷对齐的字段；{@code markdown_summary} 追加切片摘要。
 */
@Service
public class RunReportService {

    /**
     * 对外声明的报告 schema 版本（与 {@link RunEvaluator#EVAL_RULE_VERSION} 不同：那是判定规则，这是汇总报表）。
     */
    public static final String REPORT_VERSION = "run.report.v1";

    /**
     * 分片统计子结构版本（便于客户端判断解析器是否识别）。
     */
    public static final String SLICES_VERSION = "run.report.slices.v2";

    /**
     * p95 算法标识，写入 JSON，避免读者猜测用的是哪种分位数定义。
     */
    public static final String P95_METHOD_NEAREST_RANK = "nearest_rank_ceiling";

    /**
     * 通过率/跳过率的分母语义标识（见 {@link #computeReport(EvalRun, List, int)}）。
     */
    public static final String RATE_DENOMINATOR_TOTAL_CASES = "total_cases";

    private final RunStore runStore;
    private final DatasetStore datasetStore;

    public RunReportService(RunStore runStore, DatasetStore datasetStore) {
        this.runStore = runStore;
        this.datasetStore = datasetStore;
    }

    /**
     * 按 runId 从 {@link RunStore} 拉取元数据与<strong>全部</strong>结果行，生成 v1 报告 Map（键已用 snake_case，便于直接序列化）。
     *
     * @param runId           已存在的 run
     * @param errorCodeTopN   错误码榜单长度，建议在调用方限制在合理区间（如 1～20）
     * @return 扁平 + 嵌套列表结构的报告；若 run 不存在抛 {@link NoSuchElementException}（由全局异常处理映射 404）
     */
    public Map<String, Object> buildReport(String runId, int errorCodeTopN) {
        EvalRun run = runStore.getRun(runId).orElseThrow(() -> new NoSuchElementException("run not found"));
        List<EvalResult> results = runStore.listAllResults(runId);
        List<EvalCase> cases = null;
        try {
            cases = datasetStore.listAllCases(run.datasetId());
        } catch (IllegalArgumentException ignored) {
            // dataset 已从内存淘汰或未导入到本实例：仍返回主报告，不含维度切片
        }
        return computeReport(run, results, errorCodeTopN, cases);
    }

    /**
     * 核心计算（纯函数式：只依赖传入的 run 快照与结果列表），单测直接调用本方法即可，无需起 Spring。
     * <p>
     * <strong>pass_rate / skipped_rate</strong>：
     * <ul>
     *   <li>分母 = {@link EvalRun#totalCases()}（创建 run 时的总题数），语义见 {@value #RATE_DENOMINATOR_TOTAL_CASES}；</li>
     *   <li>分子 = 当前已写入的 {@link EvalResult} 中 {@code verdict == PASS} / {@code SKIPPED_UNSUPPORTED} 的条数；</li>
     *   <li>若 run 尚未跑完，分母仍为 total_cases，因此比率会<strong>偏低</strong>——表示「相对整卷」的进度口径；</li>
     *   <li>若 {@code total_cases == 0}，两率置 {@code null}（避免除以零）；</li>
     *   <li>若尚无任何结果行（{@code results.isEmpty()}），两率亦置 {@code null}，表示「尚无样本，不展示比率」。</li>
     * </ul>
     * <p>
     * <strong>p95_latency_ms（nearest-rank，向上取整秩）</strong>：
     * <ul>
     *   <li>取所有结果行的 {@link EvalResult#latencyMs()}，升序排序；</li>
     *   <li>设 n 为样本数，若 n=0 则 p95 为 {@code null}；</li>
     *   <li>令 k = ceil(0.95 × n)，k 限制在 [1, n]；p95 为排序后第 k 个元素（1-based），即下标 k-1；</li>
     *   <li>例：n=4，latency [10,20,30,40] → k=4 → p95=40；n=1 → k=1 → p95=唯一样本。</li>
     * </ul>
     * 与「线性插值」法在 n 较小时可能相差若干毫秒；v1 固定本算法并在 {@code p95_method} 字段中声明。
     * <p>
     * <strong>error_code TopN</strong>：仅统计 {@code error_code != null} 的结果行；按出现次数降序，次数相同按
     * {@link ErrorCode#name()} 字典序升序稳定并列；取前 {@code errorCodeTopN} 条。
     */
    public static Map<String, Object> computeReport(EvalRun run, List<EvalResult> results, int errorCodeTopN) {
        return computeReport(run, results, errorCodeTopN, null);
    }

    /**
     * @param allCases 非 null 时生成 {@code by_expected_behavior} 与 {@code by_requires_citations}；为 null 时省略（如 compare 内嵌、或 dataset 已不在内存）
     */
    public static Map<String, Object> computeReport(EvalRun run, List<EvalResult> results, int errorCodeTopN, List<EvalCase> allCases) {
        int totalCases = run.totalCases();
        int nResults = results.size();

        int pass = 0;
        int skipped = 0;
        int fail = 0;
        List<Long> latencies = new ArrayList<>();
        for (EvalResult r : results) {
            if (r.verdict() == Verdict.PASS) {
                pass++;
            } else if (r.verdict() == Verdict.SKIPPED_UNSUPPORTED) {
                skipped++;
            } else {
                fail++;
            }
            latencies.add(r.latencyMs());
        }

        Double passRate = null;
        Double skippedRate = null;
        if (totalCases > 0 && nResults > 0) {
            passRate = pass / (double) totalCases;
            skippedRate = skipped / (double) totalCases;
        }

        Long p95 = p95NearestRank(latencies);
        int topN = Math.max(1, errorCodeTopN);
        List<Map<String, Object>> errorTop = errorCodeTopN(results, topN);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("report_version", REPORT_VERSION);
        m.put("run_id", run.runId());
        m.put("total_cases", totalCases);
        m.put("completed_cases", run.completedCases());
        m.put("results_count", nResults);
        m.put("pass_count", pass);
        m.put("skipped_count", skipped);
        m.put("fail_count", fail);
        m.put("rate_denominator", RATE_DENOMINATOR_TOTAL_CASES);
        m.put("pass_rate", passRate);
        m.put("skipped_rate", skippedRate);
        m.put("latency_sample_count", nResults);
        m.put("p95_method", P95_METHOD_NEAREST_RANK);
        m.put("p95_latency_ms", p95);
        m.put("error_code_top_n", topN);
        m.put("error_code_counts", errorTop);
        List<Map<String, Object>> byEb = null;
        List<Map<String, Object>> byRc = null;
        if (allCases != null && !allCases.isEmpty() && nResults > 0) {
            Map<String, EvalResult> byCaseId = indexResultsByCaseId(results);
            byEb = slicesByExpectedBehavior(allCases, byCaseId, topN);
            byRc = slicesByRequiresCitations(allCases, byCaseId, topN);
            m.put("slices_version", SLICES_VERSION);
            m.put("by_expected_behavior", byEb);
            m.put("by_requires_citations", byRc);
        }
        m.put("markdown_summary", buildMarkdownSummary(run.runId(), passRate, skippedRate, p95, errorTop, byEb, byRc));
        return m;
    }

    static Map<String, EvalResult> indexResultsByCaseId(List<EvalResult> results) {
        Map<String, EvalResult> m = new LinkedHashMap<>();
        for (EvalResult r : results) {
            m.put(r.caseId(), r);
        }
        return m;
    }

    /**
     * 每个 {@code expected_behavior} 桶：分母 = 该行为在 dataset 中的题数；分子与全卷一致，只统计已有 result 行。
     */
    static List<Map<String, Object>> slicesByExpectedBehavior(
            List<EvalCase> cases, Map<String, EvalResult> byCaseId, int errorCodeTopN) {
        Map<String, List<EvalCase>> grouped = cases.stream().collect(Collectors.groupingBy(c -> c.expectedBehavior().toJson(), LinkedHashMap::new, Collectors.toList()));
        List<String> order = List.of("answer", "clarify", "deny", "tool");
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : order) {
            List<EvalCase> bucket = grouped.remove(key);
            if (bucket != null && !bucket.isEmpty()) {
                out.add(oneSliceRow("expected_behavior", key, bucket, byCaseId, errorCodeTopN));
            }
        }
        for (Map.Entry<String, List<EvalCase>> e : grouped.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.add(oneSliceRow("expected_behavior", e.getKey(), e.getValue(), byCaseId, errorCodeTopN));
            }
        }
        return out;
    }

    static List<Map<String, Object>> slicesByRequiresCitations(
            List<EvalCase> cases, Map<String, EvalResult> byCaseId, int errorCodeTopN) {
        Map<Boolean, List<EvalCase>> grouped = cases.stream().collect(Collectors.partitioningBy(EvalCase::requiresCitations));
        List<Map<String, Object>> out = new ArrayList<>();
        List<EvalCase> noCit = grouped.get(false);
        if (noCit != null && !noCit.isEmpty()) {
            out.add(oneSliceRow("requires_citations", Boolean.FALSE, noCit, byCaseId, errorCodeTopN));
        }
        List<EvalCase> cit = grouped.get(true);
        if (cit != null && !cit.isEmpty()) {
            out.add(oneSliceRow("requires_citations", Boolean.TRUE, cit, byCaseId, errorCodeTopN));
        }
        return out;
    }

    private static Map<String, Object> oneSliceRow(
            String dimensionKey,
            Object dimensionValue,
            List<EvalCase> bucket,
            Map<String, EvalResult> byCaseId,
            int errorCodeTopN
    ) {
        int denom = bucket.size();
        int pc = 0;
        int sc = 0;
        int fc = 0;
        List<EvalResult> bucketResults = new ArrayList<>();
        for (EvalCase c : bucket) {
            EvalResult r = byCaseId.get(c.caseId());
            if (r == null) {
                continue;
            }
            bucketResults.add(r);
            if (r.verdict() == Verdict.PASS) {
                pc++;
            } else if (r.verdict() == Verdict.SKIPPED_UNSUPPORTED) {
                sc++;
            } else {
                fc++;
            }
        }
        int withResult = bucketResults.size();
        List<Long> latencies = bucketResults.stream().map(EvalResult::latencyMs).toList();
        Long p95Slice = p95NearestRank(latencies);
        int topN = Math.max(1, errorCodeTopN);
        List<Map<String, Object>> sliceErrors = errorCodeTopN(bucketResults, topN);

        Double pr = denom > 0 ? pc / (double) denom : null;
        Double sr = denom > 0 ? sc / (double) denom : null;
        Double fr = denom > 0 ? fc / (double) denom : null;
        Map<String, Object> row = new LinkedHashMap<>();
        if (dimensionValue instanceof Boolean z) {
            row.put(dimensionKey, z);
        } else {
            row.put(dimensionKey, Objects.toString(dimensionValue, ""));
        }
        row.put("cases_total", denom);
        row.put("results_count", withResult);
        row.put("pass_count", pc);
        row.put("skipped_count", sc);
        row.put("fail_count", fc);
        row.put("rate_denominator", RATE_DENOMINATOR_TOTAL_CASES);
        row.put("pass_rate", pr);
        row.put("skipped_rate", sr);
        row.put("fail_rate", fr);
        row.put("latency_sample_count", withResult);
        row.put("p95_method", P95_METHOD_NEAREST_RANK);
        row.put("p95_latency_ms", p95Slice);
        row.put("error_code_top_n", topN);
        row.put("error_code_counts", sliceErrors);
        return row;
    }

    /**
     * nearest-rank p95：升序排序后取第 ceil(0.95*n) 个（1-based），边界 clamp 到 [1,n]。
     */
    static Long p95NearestRank(List<Long> latencies) {
        if (latencies == null || latencies.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int n = sorted.size();
        int k = (int) Math.ceil(0.95 * n);
        if (k < 1) {
            k = 1;
        }
        if (k > n) {
            k = n;
        }
        return sorted.get(k - 1);
    }

    /**
     * 统计非空 error_code，按 count desc、code name asc 排序后截断前 topN 条。
     */
    static List<Map<String, Object>> errorCodeTopN(List<EvalResult> results, int topN) {
        Map<ErrorCode, Long> counts = results.stream()
                .map(EvalResult::errorCode)
                .filter(c -> c != null)
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

        List<Map.Entry<ErrorCode, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator
                .<Map.Entry<ErrorCode, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(e -> e.getKey().name()));

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, entries.size()); i++) {
            Map.Entry<ErrorCode, Long> e = entries.get(i);
            out.add(Map.of(
                    "error_code", e.getKey().name(),
                    "count", e.getValue().intValue()
            ));
        }
        return out;
    }

    private static String buildMarkdownSummary(
            String runId,
            Double passRate,
            Double skippedRate,
            Long p95,
            List<Map<String, Object>> errorTop,
            List<Map<String, Object>> byExpectedBehavior,
            List<Map<String, Object>> byRequiresCitations
    ) {
        String pr = passRate == null ? "n/a" : String.format("%.4f", passRate);
        String sr = skippedRate == null ? "n/a" : String.format("%.4f", skippedRate);
        String p95s = p95 == null ? "n/a" : p95 + " ms";
        StringBuilder err = new StringBuilder();
        for (Map<String, Object> row : errorTop) {
            // ASCII only: Windows PowerShell consoles often mis-render UTF-8 punctuation (e.g. U+00D7) as mojibake.
            err.append(String.format("- %s x %s%n", row.get("error_code"), row.get("count")));
        }
        if (err.isEmpty()) {
            err.append("(none)");
        }
        StringBuilder body = new StringBuilder();
        body.append(String.format(
                "# run.report v1 - `%s`%n%n- pass_rate: %s (denominator: total_cases)%n- skipped_rate: %s%n- p95_latency_ms: %s (nearest_rank_ceiling)%n%n## error_code TopN%n%s",
                runId, pr, sr, p95s, err
        ));
        if (byExpectedBehavior != null && !byExpectedBehavior.isEmpty()) {
            body.append(String.format("%n%n## slices expected_behavior (%s)%n", SLICES_VERSION));
            for (Map<String, Object> slice : byExpectedBehavior) {
                body.append(formatSliceMarkdownLine(slice, "expected_behavior"));
            }
        }
        if (byRequiresCitations != null && !byRequiresCitations.isEmpty()) {
            body.append(String.format("%n%n## slices requires_citations (%s)%n", SLICES_VERSION));
            for (Map<String, Object> slice : byRequiresCitations) {
                body.append(formatSliceMarkdownLine(slice, "requires_citations"));
            }
        }
        return body.toString();
    }

    /**
     * 单行 ASCII 摘要，便于 PowerShell / 纯英文周报粘贴。
     */
    private static String formatSliceMarkdownLine(Map<String, Object> slice, String dimKey) {
        Object dimVal = slice.get(dimKey);
        Object p95m = slice.get("p95_latency_ms");
        String p95s = p95m == null ? "n/a" : p95m + " ms";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ec = (List<Map<String, Object>>) slice.get("error_code_counts");
        StringBuilder err = new StringBuilder();
        if (ec != null) {
            for (Map<String, Object> row : ec) {
                err.append(String.format(" %s x%s", row.get("error_code"), row.get("count")));
            }
        }
        if (err.isEmpty()) {
            err.append(" (no error_code rows)");
        }
        return String.format(
                "- %s=%s pass_rate=%s skipped_rate=%s fail_rate=%s p95=%s err:%s%n",
                dimKey,
                dimVal,
                slice.get("pass_rate") == null ? "n/a" : String.format("%.4f", slice.get("pass_rate")),
                slice.get("skipped_rate") == null ? "n/a" : String.format("%.4f", slice.get("skipped_rate")),
                slice.get("fail_rate") == null ? "n/a" : String.format("%.4f", slice.get("fail_rate")),
                p95s,
                err
        );
    }
}
