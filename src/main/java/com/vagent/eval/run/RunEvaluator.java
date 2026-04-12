package com.vagent.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.run.EvalChatContractValidator.ContractOutcome;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.Verdict;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Day3/Day4/Day5/Day6：离线判定器（P0）。
 * <p>
 * <strong>执行顺序（与「为何这样排」）</strong>：
 * <ol>
 *   <li>{@link EvalChatContractValidator} — 先保证响应是「结构上合法」的 eval/chat JSON，避免在缺字段的树上做行为判定；</li>
 *   <li>解析 {@code behavior}、{@code capabilities}，写入 debug；</li>
 *   <li>若期望 {@code tool} 但 {@code tools.supported=false} — 语义上无法评测 tool 路径，直接
 *       {@link Verdict#SKIPPED_UNSUPPORTED}（与 Day5 一致，且必须先于 behavior 字符串严格相等比对）；</li>
 *   <li>{@code expected_behavior} 与 {@code behavior} 文本一致；</li>
 *   <li>{@code requires_citations}：检索不支持则跳过；否则要求非空 {@code sources}；</li>
 *   <li><strong>Day6</strong>：在 citations 路径上要求 {@code retrieval_hits}，对其前 N 条做 hashed membership，
 *       保证每条 source 的 id 落在候选集内，否则 {@link ErrorCode#SOURCE_NOT_IN_HITS}；</li>
 *   <li>最后处理 {@code expected_behavior=tool} 且工具已声明支持时的 {@code tool} 块细节。</li>
 * </ol>
 * <p>
 * 该类由 {@link RunRunner} 在每道 case 拿到上游 JSON 后调用；<strong>不负责 HTTP</strong>。
 */
@Component
public class RunEvaluator {

    /**
     * P0 判定器版本号：Day6 引入 hashed membership 后与 Day5 规则不兼容处递增次版本。
     * <p>
     * 出现在每条结果的 {@code debug.eval_rule_version}，便于报告追溯。
     */
    public static final String EVAL_RULE_VERSION = "p0.v2";

    private final EvalChatContractValidator contractValidator;
    private final EvalProperties evalProperties;

    /**
     * @param contractValidator 契约校验器（无状态，可复用）
     * @param evalProperties    读取 {@code eval.membership.*}（盐、前 N）
     */
    public RunEvaluator(EvalChatContractValidator contractValidator, EvalProperties evalProperties) {
        this.contractValidator = contractValidator;
        this.evalProperties = evalProperties;
    }

    /**
     * 单条 case 的判定结果：终态枚举 + 可选错误码 + 可序列化 debug Map（写入 {@link com.vagent.eval.run.RunModel.EvalResult}）。
     */
    public record EvalOutcome(Verdict verdict, ErrorCode errorCode, Map<String, Object> debug) {
    }

    /**
     * 对一道 {@link EvalCase} 与已解析的上游 {@code eval/chat} JSON 根节点做完整 P0 判定。
     *
     * @param c          数据集行（含 expected_behavior、requires_citations 等）
     * @param respJson   上游响应根节点；null 表示解析失败（通常 RunRunner 已拦到 PARSE_ERROR，此处兜底）
     * @param targetId   与 {@code X-Eval-Target-Id} 一致，参与 membership 哈希绑定，避免跨 target 撞 id
     * @return 终态与 debug（debug 始终尽量带上 eval_rule_version 等上下文）
     */
    public EvalOutcome evaluate(EvalCase c, JsonNode respJson, String targetId) {
        Map<String, Object> debug = new HashMap<>();
        debug.put("eval_rule_version", EVAL_RULE_VERSION);
        debug.put("expected_behavior", c.expectedBehavior().toJson());
        debug.put("requires_citations", c.requiresCitations());

        if (respJson == null || respJson.isNull()) {
            return new EvalOutcome(Verdict.FAIL, ErrorCode.PARSE_ERROR, debug);
        }

        ContractOutcome co = contractValidator.validate(respJson);
        if (!co.ok()) {
            debug.put("contract_reason", co.reason());
            return new EvalOutcome(Verdict.FAIL, co.errorCode(), debug);
        }

        String behavior = asText(respJson.get("behavior"));
        debug.put("actual_behavior", behavior);

        JsonNode caps = respJson.get("capabilities");
        boolean retrievalSupported = caps != null && caps.get("retrieval") != null && bool(caps.get("retrieval").get("supported"));
        boolean toolsSupported = caps != null && caps.get("tools") != null && bool(caps.get("tools").get("supported"));
        debug.put("retrieval_supported", retrievalSupported);
        debug.put("tools_supported", toolsSupported);

        JsonNode sources = respJson.get("sources");
        if (sources != null && sources.isArray()) {
            debug.put("sources_count", sources.size());
        }

        String expectedBehavior = c.expectedBehavior().toJson();
        // 期望 tool 但上游声明不支持工具时，与 citations+retrieval 同理：无法评测 → 跳过，不因 behavior=answer 判 mismatch。
        if ("tool".equalsIgnoreCase(expectedBehavior) && !toolsSupported) {
            debug.put("verdict_reason", "tools_unsupported");
            return new EvalOutcome(Verdict.SKIPPED_UNSUPPORTED, ErrorCode.SKIPPED_UNSUPPORTED, debug);
        }

        if (!expectedBehavior.equalsIgnoreCase(behavior)) {
            debug.put("verdict_reason", "behavior_mismatch");
            return new EvalOutcome(Verdict.FAIL, ErrorCode.UNKNOWN, debug);
        }

        if (c.requiresCitations()) {
            if (!retrievalSupported) {
                debug.put("verdict_reason", "retrieval_unsupported");
                return new EvalOutcome(Verdict.SKIPPED_UNSUPPORTED, ErrorCode.SKIPPED_UNSUPPORTED, debug);
            }
            if (sources == null || !sources.isArray() || sources.size() < 1) {
                debug.put("verdict_reason", "missing_sources");
                return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
            }
            // Day6：引用必须落在检索候选（前 N 条 hits）的 hashed 集合内。
            EvalOutcome membershipOutcome = verifyCitationMembership(respJson, sources, targetId, debug);
            if (membershipOutcome != null) {
                return membershipOutcome;
            }
        }

        if (c.expectedBehavior().toJson().equals("tool")) {
            JsonNode tool = respJson.get("tool");
            if (tool == null) {
                debug.put("verdict_reason", "missing_tool_block");
                return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
            }
            boolean required = bool(tool.get("required"));
            boolean used = bool(tool.get("used"));
            boolean succeeded = bool(tool.get("succeeded"));
            debug.put("tool_required", required);
            debug.put("tool_used", used);
            debug.put("tool_succeeded", succeeded);
            if (!(required && used && succeeded)) {
                debug.put("verdict_reason", "tool_not_satisfied");
                return new EvalOutcome(Verdict.FAIL, ErrorCode.UNKNOWN, debug);
            }
        }

        debug.put("verdict_reason", "ok");
        return new EvalOutcome(Verdict.PASS, null, debug);
    }

    /**
     * Day6：校验 {@code sources[*].id} 是否均属于 {@code retrieval_hits} 前 N 条所构成的 hashed 候选集。
     * <p>
     * 若本方法返回非 null，调用方应<strong>直接返回</strong>该 outcome（失败或契约问题）；
     * 返回 null 表示 membership 通过，调用方继续后续 tool 等规则。
     *
     * @param respJson 合法契约下的完整响应根节点
     * @param sources  已确认为数组且 size≥1
     * @param targetId 当前 target，与哈希绑定
     * @param debug    同源 debug Map，本方法会追加 membership 相关键
     * @return null 表示通过；非 null 为终态
     */
    private EvalOutcome verifyCitationMembership(JsonNode respJson, JsonNode sources, String targetId, Map<String, Object> debug) {
        String salt = evalProperties.getMembership().getSalt();
        int topN = evalProperties.getMembership().getTopN();
        debug.put("membership_top_n", topN);
        debug.put("membership_salt_configured", salt != null && !salt.isEmpty());

        JsonNode hitsNode = respJson.get("retrieval_hits");
        if (hitsNode == null || hitsNode.isNull() || !hitsNode.isArray() || hitsNode.size() < 1) {
            debug.put("verdict_reason", "missing_retrieval_hits");
            return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
        }

        int considered = Math.min(topN, hitsNode.size());
        debug.put("membership_hits_considered", considered);

        Set<String> allowedHashes = new HashSet<>();
        for (int i = 0; i < considered; i++) {
            JsonNode hit = hitsNode.get(i);
            if (hit == null || !hit.isObject()) {
                debug.put("verdict_reason", "retrieval_hit_not_object");
                return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
            }
            JsonNode idNode = hit.get("id");
            if (idNode == null || !idNode.isTextual()) {
                debug.put("verdict_reason", "retrieval_hit_missing_id");
                return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
            }
            String canonical = CitationMembership.canonicalChunkId(idNode.asText());
            allowedHashes.add(CitationMembership.membershipHashHex(salt, targetId, canonical));
        }

        for (int i = 0; i < sources.size(); i++) {
            JsonNode src = sources.get(i);
            if (src == null || !src.isObject()) {
                debug.put("verdict_reason", "source_not_object");
                debug.put("source_index", i);
                return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
            }
            JsonNode sid = src.get("id");
            if (sid == null || !sid.isTextual()) {
                debug.put("verdict_reason", "citation_source_id_invalid");
                debug.put("source_index", i);
                return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
            }
            String rawId = sid.asText();
            String canonical = CitationMembership.canonicalChunkId(rawId);
            String h = CitationMembership.membershipHashHex(salt, targetId, canonical);
            if (!allowedHashes.contains(h)) {
                debug.put("verdict_reason", "source_not_in_hits");
                debug.put("rejected_source_index", i);
                debug.put("rejected_source_id_prefix", prefix(rawId, 48));
                debug.put("rejected_membership_hash_prefix", h.length() >= 12 ? h.substring(0, 12) : h);
                return new EvalOutcome(Verdict.FAIL, ErrorCode.SOURCE_NOT_IN_HITS, debug);
            }
        }

        debug.put("membership_ok", true);
        return null;
    }

    /**
     * 截断字符串用于 debug 输出，避免在结果表里灌入过长字段。
     */
    private static String prefix(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "…";
    }

    private static String asText(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        return n.asText("");
    }

    private static boolean bool(JsonNode n) {
        if (n == null || n.isNull()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.asBoolean(false);
        }
        if (n.isTextual()) {
            String s = n.asText("").trim().toLowerCase();
            return s.equals("true") || s.equals("1") || s.equals("yes");
        }
        if (n.isNumber()) {
            return n.asInt(0) != 0;
        }
        return false;
    }
}
