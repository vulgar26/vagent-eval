package com.vagent.eval.run;

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
import java.util.stream.Collectors;

/**
 * Day7：{@code run.report} v1 —— 对单次 run 的 {@link EvalResult} 做聚合，供日报/门禁/对比。
 * <p>
 * <strong>与明细 API 的关系</strong>：{@link RunApi} 的 {@code GET .../results} 返回逐题明细；本服务在此基础上
 * 计算通过率、跳过率、p95 时延、错误码 TopN，<strong>不修改</strong>存储、不改变判定规则。
 * <p>
 * <strong>报告版本</strong>：响应内 {@code report_version} 固定为 {@value #REPORT_VERSION}，后续若改公式或字段需递增版本。
 */
@Service
public class RunReportService {

    /**
     * 对外声明的报告 schema 版本（与 {@link RunEvaluator#EVAL_RULE_VERSION} 不同：那是判定规则，这是汇总报表）。
     */
    public static final String REPORT_VERSION = "run.report.v1";

    /**
     * p95 算法标识，写入 JSON，避免读者猜测用的是哪种分位数定义。
     */
    public static final String P95_METHOD_NEAREST_RANK = "nearest_rank_ceiling";

    /**
     * 通过率/跳过率的分母语义标识（见 {@link #computeReport(EvalRun, List, int)}）。
     */
    public static final String RATE_DENOMINATOR_TOTAL_CASES = "total_cases";

    private final RunStore runStore;

    public RunReportService(RunStore runStore) {
        this.runStore = runStore;
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
        return computeReport(run, results, errorCodeTopN);
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
        m.put("markdown_summary", buildMarkdownSummary(run.runId(), passRate, skippedRate, p95, errorTop));
        return m;
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
            List<Map<String, Object>> errorTop
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
        return String.format(
                "# run.report v1 - `%s`%n%n- pass_rate: %s (denominator: total_cases)%n- skipped_rate: %s%n- p95_latency_ms: %s (nearest_rank_ceiling)%n%n## error_code TopN%n%s",
                runId, pr, sr, p95s, err
        );
    }
}
