package com.vagent.eval.web;

import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.run.CitationMembership;
import com.vagent.eval.run.TargetClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day4/Day5/Day6 本地探针：在无双 target 时模拟 {@code POST /api/v1/eval/chat}。
 * <p>
 * <strong>默认关闭</strong>：仅当 {@code eval.probe.enabled=true} 时注册，避免与真实部署误暴露。
 * <p>
 * <strong>职责</strong>：根据 {@code query} 中的关键词返回<strong>形状合法</strong>的 JSON，供
 * {@link com.vagent.eval.run.RunRunner} → {@link com.vagent.eval.run.RunEvaluator} 走完整判定链；
 * <strong>不包含</strong>业务评测逻辑（逻辑全在 {@code run} 包）。
 * <p>
 * P0+：读取 {@link TargetClient} 发出的 eval 头（run/dataset/case/target/token）与 membership 头，
 * 在 citations 场景下返回 {@code meta.retrieval_hit_id_hashes}（前 N 条），与 {@link CitationMembership} 的
 * HMAC membership 规则对齐；仍可选返回 {@code retrieval_hits} 便于人读/排障。
 */
@RestController
@ConditionalOnProperty(prefix = "eval.probe", name = "enabled", havingValue = "true")
public class EvalProbeChatController {

    private static final int MEMBERSHIP_TOP_N_MAX = 256;

    private final EvalProperties evalProperties;

    /**
     * 注入配置以读取默认 {@code topN}，并与评测侧 {@link EvalProperties#getMembership()} 缺省值一致。
     */
    public EvalProbeChatController(EvalProperties evalProperties) {
        this.evalProperties = evalProperties;
    }

    /**
     * 探针版 {@code POST /api/v1/eval/chat}。
     * <p>
     * 可选请求头 {@link TargetClient#HDR_MEMBERSHIP_TOP_N}：覆盖「本响应应模拟的候选条数上界」的提示值
     * （探针仍返回完整 {@code retrieval_hits} 列表，真正截断由 {@link com.vagent.eval.run.RunEvaluator} 按配置执行）。
     *
     * @param body    评测请求体，至少可读 {@code query}
     * @param topNHdr 可选，正整数字符串；非法则回退到配置（与 {@link TargetClient#HDR_MEMBERSHIP_TOP_N} 对应）
     */
    @PostMapping(path = "/api/v1/eval/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> evalChat(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = TargetClient.HDR_MEMBERSHIP_TOP_N, required = false) String topNHdr,
            @RequestHeader(value = "X-Eval-Token", required = false) String evalToken,
            @RequestHeader(value = "X-Eval-Dataset-Id", required = false) String datasetId,
            @RequestHeader(value = "X-Eval-Case-Id", required = false) String caseId,
            @RequestHeader(value = "X-Eval-Target-Id", required = false) String targetId
    ) {
        int configuredTopN = evalProperties.getMembership().getTopN();
        int hintedTopN = parseTopNHeader(topNHdr, configuredTopN);

        Object q = body == null ? null : body.get("query");
        String query = q == null ? "" : String.valueOf(q);
        String token = evalToken == null ? "" : evalToken.trim();
        String tid = targetId == null ? "" : targetId.trim();
        String ds = datasetId == null ? "" : datasetId.trim();
        String cid = caseId == null ? "" : caseId.trim();

        // Day5：为了验收判定器 v1，提供几个稳定的“脚本关键词”来控制返回形态。
        // - BAD_CONTRACT：缺 latency_ms → CONTRACT_VIOLATION
        // - DENY_OK：behavior=deny（用于 expected_behavior 覆盖）
        // - CITATIONS_OK：retrieval.supported=true 且 sources>=1 + meta.retrieval_hit_id_hashes（membership 通过）
        // - CITATIONS_BAD_MEMBER：hashes 与 sources 故意不一致 → SOURCE_NOT_IN_HITS
        // - TOOL_UNSUPPORTED：tools.supported=false（用于 SKIPPED_UNSUPPORTED 覆盖）
        if (query.contains("BAD_CONTRACT")) {
            Map<String, Object> caps = new LinkedHashMap<>();
            caps.put("retrieval", Map.of("supported", false, "score", false));
            caps.put("tools", Map.of("supported", false, "outcome", false));
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("answer", "incomplete");
            bad.put("behavior", "answer");
            // 故意缺少 latency_ms → CONTRACT_VIOLATION
            bad.put("capabilities", caps);
            bad.put("meta", Map.of("mode", "EVAL"));
            return bad;
        }

        if (query.contains("CITATIONS_BAD_MEMBER")) {
            return buildCitationsBadMember(query, token, tid, ds, cid, hintedTopN);
        }

        Map<String, Object> caps = new LinkedHashMap<>();
        boolean retrievalSupported = query.contains("CITATIONS_OK");
        boolean toolsSupported = !query.contains("TOOL_UNSUPPORTED");
        caps.put("retrieval", Map.of("supported", retrievalSupported, "score", false));
        caps.put("tools", Map.of("supported", toolsSupported, "outcome", toolsSupported));
        caps.put("streaming", Map.of("ttft", false));
        caps.put("guardrails", Map.of("quoteOnly", false, "evidenceMap", false, "reflection", false));

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("answer", "probe ok");
        ok.put("behavior", query.contains("DENY_OK") ? "deny" : "answer");
        ok.put("latency_ms", 1);
        ok.put("capabilities", caps);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", "EVAL");

        if (query.contains("CITATIONS_OK")) {
            // 故意使用大写 source id，与 hits 中小写 canonical 对齐，验收 Day6 canonical 规则
            ok.put("sources", new Object[]{
                    Map.of("id", "KB_CHUNK_1", "title", "t1", "snippet", "s1")
            });
            // 检索排序：靠前者优先进入「前 N」；第 2 条用于填充列表（topN=1 时仅 kb_chunk_1 有效）
            List<Map<String, String>> hits = buildRetrievalHitsList(hintedTopN);
            ok.put("retrieval_hits", hits);
            meta.put("retrieval_hit_id_hashes", buildHitIdHashes(token, tid, ds, cid, hits, hintedTopN));
            meta.put("canonical_hit_id_scheme", "lower_trim_v1");
        }
        ok.put("meta", meta);
        return ok;
    }

    /**
     * 构造「引用 id 不在候选 hashed 集合」场景：hits 仅含 only_hit，sources 引用 forged_chunk。
     */
    private static Map<String, Object> buildCitationsBadMember(
            String query,
            String evalToken,
            String targetId,
            String datasetId,
            String caseId,
            int hintedTopN
    ) {
        Map<String, Object> caps = new LinkedHashMap<>();
        boolean toolsSupported = !query.contains("TOOL_UNSUPPORTED");
        caps.put("retrieval", Map.of("supported", true, "score", false));
        caps.put("tools", Map.of("supported", toolsSupported, "outcome", toolsSupported));
        caps.put("streaming", Map.of("ttft", false));
        caps.put("guardrails", Map.of("quoteOnly", false, "evidenceMap", false, "reflection", false));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("answer", "probe bad member");
        m.put("behavior", "answer");
        m.put("latency_ms", 1);
        m.put("capabilities", caps);
        List<Map<String, String>> hits = List.of(Map.of("id", "only_hit", "title", "h1", "snippet", "hs"));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", "EVAL");
        meta.put("retrieval_hit_id_hashes", buildHitIdHashes(evalToken, targetId, datasetId, caseId, hits, hintedTopN));
        meta.put("canonical_hit_id_scheme", "lower_trim_v1");
        m.put("meta", meta);
        m.put("retrieval_hits", hits);
        m.put("sources", new Object[]{
                Map.of("id", "forged_chunk", "title", "x", "snippet", "y")
        });
        return m;
    }

    /**
     * 按「提示 topN」构造略长于 N 的 hits 列表，便于集成测试验证「仅前 N 条入集」行为（若用例将 topN 设为 1）。
     */
    private static List<Map<String, String>> buildRetrievalHitsList(int hintedTopN) {
        int n = Math.min(Math.max(hintedTopN, 1), MEMBERSHIP_TOP_N_MAX);
        List<Map<String, String>> list = new ArrayList<>();
        list.add(Map.of("id", "kb_chunk_1", "title", "hit1", "snippet", "a"));
        if (n >= 2) {
            list.add(Map.of("id", "kb_chunk_2", "title", "hit2", "snippet", "b"));
        }
        for (int i = list.size(); i < Math.min(n, 4); i++) {
            list.add(Map.of("id", "kb_chunk_fill_" + i, "title", "f", "snippet", "f"));
        }
        return list;
    }

    private static List<String> buildHitIdHashes(
            String evalToken,
            String targetId,
            String datasetId,
            String caseId,
            List<Map<String, String>> hits,
            int hintedTopN
    ) {
        int topN = Math.min(Math.max(hintedTopN, 1), MEMBERSHIP_TOP_N_MAX);
        byte[] caseKey = CitationMembership.deriveCaseKeyV1(evalToken, targetId, datasetId, caseId);
        List<String> out = new ArrayList<>();
        int limit = Math.min(topN, hits == null ? 0 : hits.size());
        for (int i = 0; i < limit; i++) {
            String raw = hits.get(i).get("id");
            String canonical = CitationMembership.canonicalChunkId(raw);
            out.add(CitationMembership.hitIdHashHexV1(caseKey, canonical));
        }
        return out;
    }

    /**
     * 解析评测客户端传来的 topN 头；非法或缺失时使用配置默认值并限制在 [1, {@link #MEMBERSHIP_TOP_N_MAX}]。
     */
    private int parseTopNHeader(String topNHdr, int defaultTopN) {
        if (topNHdr == null || topNHdr.isBlank()) {
            return clampTopN(defaultTopN);
        }
        try {
            return clampTopN(Integer.parseInt(topNHdr.trim()));
        } catch (NumberFormatException e) {
            return clampTopN(defaultTopN);
        }
    }

    private static int clampTopN(int v) {
        return Math.min(MEMBERSHIP_TOP_N_MAX, Math.max(1, v));
    }
}
