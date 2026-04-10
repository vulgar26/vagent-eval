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

    /**
     * P0 判定器版本号（Day5 要求：规则版本化）。
     * <p>
     * 作用：当规则迭代导致判定变化时，报告可以追溯“当时用的是哪版规则”。\n
     * 后续可以把它提升为配置项或写入 run 级快照。
     */
    public static final String EVAL_RULE_VERSION = "p0.v1";

    private final EvalChatContractValidator contractValidator;

    public RunEvaluator(EvalChatContractValidator contractValidator) {
        this.contractValidator = contractValidator;
    }

    public record EvalOutcome(Verdict verdict, ErrorCode errorCode, Map<String, Object> debug) {
    }

    public EvalOutcome evaluate(EvalCase c, JsonNode respJson) {
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
