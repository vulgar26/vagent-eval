package com.vagent.eval.run;

import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import com.vagent.eval.run.RunModel.Verdict;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

/**
 * Day8：{@code compare} v1 —— 对两次 run（base / cand）做离线差分，不重新执行评测。
 * <p>
 * <strong>数据来源</strong>：仅读取 {@link RunStore} 中已落盘的结果，与实习生结论一致：重跑成本高且结果可能漂移。
 * <p>
 * <strong>与 Day7 的关系</strong>：{@link #pass_rate_delta} 使用 {@link RunReportService#computeReport} 算出的
 * {@code pass_rate}（分母 {@code total_cases}、分子 PASS 数），再作 {@code cand_pass_rate - base_pass_rate}，
 * 与单次 {@code run.report} 口径一致。
 * <p>
 * <strong>对齐键</strong>：{@link EvalResult#caseId()}；同一 run 内若重复 case_id，后写入覆盖先写入（与 Map 行为一致）。
 * <p>
 * <strong>判定（v1）</strong>：
 * <ul>
 *   <li>{@code regressions}/{@code improvements} 的每行含 {@code base_meta_trace}/{@code cand_meta_trace}：按<strong>各题结果行</strong>
 *       {@link EvalResult#targetId()} 匹配 {@code eval.targets[].meta-trace-keys}，从落库 {@code meta} 中拷贝对应键（无配置则为空 map），避免写死某一被测方 schema；</li>
 *   <li>{@code regressions}：base 为 {@link Verdict#PASS}，且 cand 侧<strong>有结果</strong>且 verdict 非 PASS；</li>
 *   <li>{@code improvements}：base 非 PASS，且 cand 侧有结果且为 PASS；</li>
 *   <li>{@code missing_in_cand}：base 有该 case，cand 无该 case 行——视为「未产出/未对齐」，单独列出便于排障（不混入 regression 以免与「判了但挂了」混淆）。</li>
 *   <li>{@code missing_in_base}：对称情况，仅 cand 有而 base 无（少见）。</li>
 *   <li>{@code missing_in_*} 行含 {@code present_meta_trace}：仅存一侧结果时，按该行的 {@code target_id} 与 {@code meta-trace-keys} 从 {@code meta} 拷贝摘要。</li>
 * </ul>
 */
@Service
public class RunCompareService {

    /**
     * compare 响应 schema 版本。
     */
    public static final String COMPARE_VERSION = "compare.v1";

    private final RunStore runStore;
    private final EvalProperties evalProperties;

    public RunCompareService(RunStore runStore, EvalProperties evalProperties) {
        this.runStore = runStore;
        this.evalProperties = evalProperties;
    }

    /**
     * 生成 base vs cand 的 v1 对比报告。
     *
     * @param baseRunId 基线 run
     * @param candRunId 候选 run
     * @return 含 pass_rate_delta、regressions、improvements、missing 列表及可查询路径
     * @throws NoSuchElementException     run 不存在
     * @throws IllegalArgumentException   两 run 的 {@link EvalRun#datasetId()} 不一致（映射 400）
     */
    public Map<String, Object> compare(String baseRunId, String candRunId) {
        if (baseRunId == null || baseRunId.isBlank() || candRunId == null || candRunId.isBlank()) {
            throw new IllegalArgumentException("base_run_id and cand_run_id are required");
        }
        if (baseRunId.equals(candRunId)) {
            throw new IllegalArgumentException("base_run_id and cand_run_id must differ");
        }

        EvalRun baseRun = runStore.getRun(baseRunId).orElseThrow(() -> new NoSuchElementException("base run not found"));
        EvalRun candRun = runStore.getRun(candRunId).orElseThrow(() -> new NoSuchElementException("cand run not found"));

        List<EvalResult> baseResults = runStore.listAllResults(baseRunId);
        List<EvalResult> candResults = runStore.listAllResults(candRunId);
        return compareRuns(baseRunId, candRunId, baseRun, baseResults, candRun, candResults, evalProperties);
    }

    /**
     * 纯内存对比（单测直接调用）；校验 {@code dataset_id} 一致。
     * <p>
     * {@code evalProperties} 为 null 时不对 {@code meta} 做按 target 的摘要（{@code *_meta_trace} 均为空 map）。
     */
    public static Map<String, Object> compareRuns(
            String baseRunId,
            String candRunId,
            EvalRun baseRun,
            List<EvalResult> baseResults,
            EvalRun candRun,
            List<EvalResult> candResults
    ) {
        return compareRuns(baseRunId, candRunId, baseRun, baseResults, candRun, candResults, null);
    }

    /**
     * @param evalProperties 用于解析各 target 的 {@code meta-trace-keys}；可为 null
     */
    public static Map<String, Object> compareRuns(
            String baseRunId,
            String candRunId,
            EvalRun baseRun,
            List<EvalResult> baseResults,
            EvalRun candRun,
            List<EvalResult> candResults,
            EvalProperties evalProperties
    ) {
        if (!baseRun.datasetId().equals(candRun.datasetId())) {
            throw new IllegalArgumentException("base_run_id and cand_run_id must use the same dataset_id");
        }

        Map<String, EvalResult> baseByCase = indexByCaseId(baseResults);
        Map<String, EvalResult> candByCase = indexByCaseId(candResults);

        Map<String, Object> baseReport = RunReportService.computeReport(baseRun, baseResults, 1);
        Map<String, Object> candReport = RunReportService.computeReport(candRun, candResults, 1);

        Double basePassRate = (Double) baseReport.get("pass_rate");
        Double candPassRate = (Double) candReport.get("pass_rate");
        Double passRateDelta = null;
        if (basePassRate != null && candPassRate != null) {
            passRateDelta = candPassRate - basePassRate;
        }

        Set<String> allCaseIds = new TreeSet<>();
        allCaseIds.addAll(baseByCase.keySet());
        allCaseIds.addAll(candByCase.keySet());

        List<Map<String, Object>> regressions = new ArrayList<>();
        List<Map<String, Object>> improvements = new ArrayList<>();
        List<Map<String, Object>> missingInCand = new ArrayList<>();
        List<Map<String, Object>> missingInBase = new ArrayList<>();

        for (String caseId : allCaseIds) {
            EvalResult br = baseByCase.get(caseId);
            EvalResult cr = candByCase.get(caseId);

            if (br != null && cr == null) {
                missingInCand.add(missingEntry(caseId, baseRunId, candRunId, br, true, evalProperties));
                continue;
            }
            if (br == null && cr != null) {
                missingInBase.add(missingEntry(caseId, baseRunId, candRunId, cr, false, evalProperties));
                continue;
            }
            if (br == null) {
                continue;
            }

            Verdict bv = br.verdict();
            Verdict cv = cr.verdict();
            if (bv == Verdict.PASS && cv != Verdict.PASS) {
                regressions.add(diffEntry(caseId, baseRunId, candRunId, br, cr, evalProperties));
            } else if (bv != Verdict.PASS && cv == Verdict.PASS) {
                improvements.add(diffEntry(caseId, baseRunId, candRunId, br, cr, evalProperties));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compare_version", COMPARE_VERSION);
        out.put("dataset_id", baseRun.datasetId());
        out.put("base_run_id", baseRunId);
        out.put("cand_run_id", candRunId);
        out.put("base_pass_rate", basePassRate);
        out.put("cand_pass_rate", candPassRate);
        out.put("pass_rate_delta", passRateDelta);
        out.put("pass_rate_delta_note", "cand_pass_rate - base_pass_rate; pass_rate from run.report v1 (denominator total_cases)");
        out.put("regressions", regressions);
        out.put("improvements", improvements);
        out.put("missing_in_cand", missingInCand);
        out.put("missing_in_base", missingInBase);
        out.put("markdown_summary", buildMarkdownSummary(baseRunId, candRunId, passRateDelta, regressions, improvements, missingInCand));
        return out;
    }

    private static Map<String, EvalResult> indexByCaseId(List<EvalResult> results) {
        Map<String, EvalResult> m = new LinkedHashMap<>();
        for (EvalResult r : results) {
            m.put(r.caseId(), r);
        }
        return m;
    }

    /**
     * 单题差分行：附带 cand/base 的 results 查询路径，便于 Apifox / 脚本直达 {@link RunApi#results}。
     */
    private static Map<String, Object> diffEntry(
            String caseId,
            String baseRunId,
            String candRunId,
            EvalResult br,
            EvalResult cr,
            EvalProperties evalProperties
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case_id", caseId);
        row.put("base_run_id", baseRunId);
        row.put("cand_run_id", candRunId);
        row.put("base_verdict", br.verdict().name());
        row.put("cand_verdict", cr.verdict().name());
        row.put("base_error_code", br.errorCode() == null ? null : br.errorCode().name());
        row.put("cand_error_code", cr.errorCode() == null ? null : cr.errorCode().name());
        row.put("base_meta_trace", metaTracePreview(br, evalProperties));
        row.put("cand_meta_trace", metaTracePreview(cr, evalProperties));
        row.put("cand_results_path", resultsPath(candRunId, caseId));
        row.put("base_results_path", resultsPath(baseRunId, caseId));
        return row;
    }

    /**
     * 从单条结果的 {@code meta} 中按该题 {@link EvalResult#targetId()} 对应 target 的 {@code meta-trace-keys} 拷贝子集。
     */
    private static Map<String, Object> metaTracePreview(EvalResult r, EvalProperties evalProperties) {
        Map<String, Object> meta = r.meta();
        Map<String, Object> out = new LinkedHashMap<>();
        if (meta == null || meta.isEmpty()) {
            return out;
        }
        List<String> keys = evalProperties == null ? List.of() : evalProperties.resolveMetaTraceKeys(r.targetId());
        for (String key : keys) {
            if (key != null && meta.containsKey(key)) {
                out.put(key, meta.get(key));
            }
        }
        return out;
    }

    /**
     * cand 缺失时：仍给出 base  verdict 与两条查询路径（cand 侧通常为空列表，但路径统一便于工具复用）。
     */
    private static Map<String, Object> missingEntry(
            String caseId,
            String baseRunId,
            String candRunId,
            EvalResult present,
            boolean missingOnCandSide,
            EvalProperties evalProperties
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case_id", caseId);
        row.put("base_run_id", baseRunId);
        row.put("cand_run_id", candRunId);
        if (missingOnCandSide) {
            row.put("base_verdict", present.verdict().name());
            row.put("cand_verdict", null);
            row.put("note", "no EvalResult row for this case_id on cand run");
        } else {
            row.put("base_verdict", null);
            row.put("cand_verdict", present.verdict().name());
            row.put("note", "no EvalResult row for this case_id on base run");
        }
        row.put("present_meta_trace", metaTracePreview(present, evalProperties));
        row.put("cand_results_path", resultsPath(candRunId, caseId));
        row.put("base_results_path", resultsPath(baseRunId, caseId));
        return row;
    }

    /**
     * 相对 URL（不含 host），{@code case_id} 经 UTF-8 百分号编码。
     */
    static String resultsPath(String runId, String caseId) {
        String enc = URLEncoder.encode(caseId == null ? "" : caseId, StandardCharsets.UTF_8);
        return "/api/v1/eval/runs/" + runId + "/results?case_id=" + enc;
    }

    private static String buildMarkdownSummary(
            String baseRunId,
            String candRunId,
            Double passRateDelta,
            List<Map<String, Object>> regressions,
            List<Map<String, Object>> improvements,
            List<Map<String, Object>> missingInCand
    ) {
        String d = passRateDelta == null ? "n/a" : String.format("%.4f", passRateDelta);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# compare v1: `%s` (base) vs `%s` (cand)%n%n", baseRunId, candRunId));
        sb.append(String.format("- pass_rate_delta: %s (cand - base)%n%n", d));
        sb.append("## regressions (case_id)\n");
        for (Map<String, Object> r : regressions) {
            sb.append(String.format("- %s → cand %s%n", r.get("case_id"), r.get("cand_verdict")));
        }
        if (regressions.isEmpty()) {
            sb.append("(none)\n");
        }
        sb.append("\n## improvements\n");
        for (Map<String, Object> r : improvements) {
            sb.append(String.format("- %s%n", r.get("case_id")));
        }
        if (improvements.isEmpty()) {
            sb.append("(none)\n");
        }
        sb.append("\n## missing_in_cand\n");
        for (Map<String, Object> r : missingInCand) {
            sb.append(String.format("- %s%n", r.get("case_id")));
        }
        if (missingInCand.isEmpty()) {
            sb.append("(none)\n");
        }
        return sb.toString();
    }
}
