package com.vagent.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.Verdict;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Day3：最小判定器（P0）。
 * <p>
 * 目的：把每条 case 的执行结果落成一致的 verdict + error_code，便于 report/compare。
 * P0 不做“答案语义好不好”的主观判分，只做硬规则：
 * - 协议/字段形态是否完整（缺字段 = CONTRACT_VIOLATION）
 * - behavior 是否匹配 expected_behavior
 * - requires_citations 时是否有 sources，或是否声明不支持（SKIPPED_UNSUPPORTED）
 */
@Component
public class RunEvaluator {

    public record EvalOutcome(Verdict verdict, ErrorCode errorCode, Map<String, Object> debug) {
    }

    public EvalOutcome evaluate(EvalCase c, JsonNode respJson) {
        Map<String, Object> debug = new HashMap<>();
        if (respJson == null || respJson.isNull()) {
            return new EvalOutcome(Verdict.FAIL, ErrorCode.PARSE_ERROR, debug);
        }

        // 必填字段：answer/behavior/latency_ms/capabilities/meta（SSOT 附录 C2）
        if (respJson.get("answer") == null || respJson.get("behavior") == null || respJson.get("latency_ms") == null
                || respJson.get("capabilities") == null || respJson.get("meta") == null) {
            return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
        }

        String behavior = asText(respJson.get("behavior"));
        debug.put("behavior", behavior);

        // expected_behavior 对齐（P0 最小：只做严格匹配）
        String expected = c.expectedBehavior().toJson();
        if (!expected.equalsIgnoreCase(behavior)) {
            debug.put("expected_behavior", expected);
            return new EvalOutcome(Verdict.FAIL, ErrorCode.UNKNOWN, debug);
        }

        // requires_citations：若不支持 retrieval 则 SKIPPED，否则要求 sources>=1
        if (c.requiresCitations()) {
            JsonNode caps = respJson.get("capabilities");
            boolean retrievalSupported = caps != null && caps.get("retrieval") != null && bool(caps.get("retrieval").get("supported"));
            if (!retrievalSupported) {
                return new EvalOutcome(Verdict.SKIPPED_UNSUPPORTED, ErrorCode.SKIPPED_UNSUPPORTED, debug);
            }
            JsonNode sources = respJson.get("sources");
            if (sources == null || !sources.isArray() || sources.size() < 1) {
                return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
            }
        }

        // expected_behavior=tool：若 tools 不支持则 SKIPPED；否则 tool.required/used/succeeded 必须为 true
        if (c.expectedBehavior().toJson().equals("tool")) {
            JsonNode caps = respJson.get("capabilities");
            boolean toolsSupported = caps != null && caps.get("tools") != null && bool(caps.get("tools").get("supported"));
            if (!toolsSupported) {
                return new EvalOutcome(Verdict.SKIPPED_UNSUPPORTED, ErrorCode.SKIPPED_UNSUPPORTED, debug);
            }
            JsonNode tool = respJson.get("tool");
            if (tool == null) {
                return new EvalOutcome(Verdict.FAIL, ErrorCode.CONTRACT_VIOLATION, debug);
            }
            boolean required = bool(tool.get("required"));
            boolean used = bool(tool.get("used"));
            boolean succeeded = bool(tool.get("succeeded"));
            if (!(required && used && succeeded)) {
                debug.put("tool_required", required);
                debug.put("tool_used", used);
                debug.put("tool_succeeded", succeeded);
                return new EvalOutcome(Verdict.FAIL, ErrorCode.UNKNOWN, debug);
            }
        }

        return new EvalOutcome(Verdict.PASS, null, debug);
    }

    private static String asText(JsonNode n) {
        if (n == null || n.isNull()) return "";
        return n.asText("");
    }

    private static boolean bool(JsonNode n) {
        if (n == null || n.isNull()) return false;
        if (n.isBoolean()) return n.asBoolean(false);
        if (n.isTextual()) {
            String s = n.asText("").trim().toLowerCase();
            return s.equals("true") || s.equals("1") || s.equals("yes");
        }
        if (n.isNumber()) return n.asInt(0) != 0;
        return false;
    }
}

