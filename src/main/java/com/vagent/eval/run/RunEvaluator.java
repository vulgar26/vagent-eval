package com.vagent.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.run.EvalChatContractValidator.ContractOutcome;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.Verdict;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Day3/Day4：判定器（P0）。
 * <p>
 * Day4 先走 {@link EvalChatContractValidator}：能解析 JSON 但缺字段/类型错 → {@link ErrorCode#CONTRACT_VIOLATION}；
 * 再跑行为规则（expected_behavior / requires_citations / tool）。
 */
@Component
public class RunEvaluator {

    private final EvalChatContractValidator contractValidator;

    public RunEvaluator(EvalChatContractValidator contractValidator) {
        this.contractValidator = contractValidator;
    }

    public record EvalOutcome(Verdict verdict, ErrorCode errorCode, Map<String, Object> debug) {
    }

    public EvalOutcome evaluate(EvalCase c, JsonNode respJson) {
        Map<String, Object> debug = new HashMap<>();
        if (respJson == null || respJson.isNull()) {
            return new EvalOutcome(Verdict.FAIL, ErrorCode.PARSE_ERROR, debug);
        }

        ContractOutcome co = contractValidator.validate(respJson);
        if (!co.ok()) {
            debug.put("contract_reason", co.reason());
            return new EvalOutcome(Verdict.FAIL, co.errorCode(), debug);
        }

        String behavior = asText(respJson.get("behavior"));
        debug.put("behavior", behavior);

        String expected = c.expectedBehavior().toJson();
        if (!expected.equalsIgnoreCase(behavior)) {
            debug.put("expected_behavior", expected);
            return new EvalOutcome(Verdict.FAIL, ErrorCode.UNKNOWN, debug);
        }

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
