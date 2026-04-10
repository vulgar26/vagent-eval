package com.vagent.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.vagent.eval.run.RunModel.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Day4：{@code POST /api/v1/eval/chat} 响应契约校验（SSOT：p0-execution-map 附录 C2/C3）。
 * <p>
 * 口径（与实习生预考一致）：
 * <ul>
 *   <li>HTTP 2xx 但 body 无法解析为 JSON → 由调用方（{@link TargetClient}/{@link RunRunner}）判 {@link ErrorCode#PARSE_ERROR}</li>
 *   <li>能解析为 JSON，但缺必填字段或类型不对 → {@link ErrorCode#CONTRACT_VIOLATION}</li>
 * </ul>
 * 必填顶层：{@code answer}、{@code behavior}、{@code latency_ms}、{@code capabilities}、{@code meta}。
 * {@code meta} 至少含 {@code mode}（附录 C2）。
 */
@Component
public class EvalChatContractValidator {

    public record ContractOutcome(boolean ok, ErrorCode errorCode, String reason) {
        public static ContractOutcome pass() {
            return new ContractOutcome(true, null, "");
        }

        public static ContractOutcome fail(ErrorCode code, String reason) {
            return new ContractOutcome(false, code, reason == null ? "" : reason);
        }
    }

    /**
     * @param root 已解析成功的 JSON 根节点（非 null）
     */
    public ContractOutcome validate(JsonNode root) {
        if (root == null || root.isNull()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "root_is_null");
        }
        if (!root.isObject()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "root_must_be_object");
        }

        JsonNode answer = root.get("answer");
        if (answer == null || answer.isNull()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "missing_answer");
        }
        if (!answer.isTextual()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "answer_must_be_string");
        }

        JsonNode behavior = root.get("behavior");
        if (behavior == null || behavior.isNull()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "missing_behavior");
        }
        if (!behavior.isTextual()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "behavior_must_be_string");
        }

        JsonNode latency = root.get("latency_ms");
        if (latency == null || latency.isNull()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "missing_latency_ms");
        }
        if (!latency.isNumber()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "latency_ms_must_be_number");
        }

        JsonNode caps = root.get("capabilities");
        if (caps == null || caps.isNull()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "missing_capabilities");
        }
        if (!caps.isObject()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "capabilities_must_be_object");
        }

        JsonNode meta = root.get("meta");
        if (meta == null || meta.isNull()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "missing_meta");
        }
        if (!meta.isObject()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "meta_must_be_object");
        }
        JsonNode mode = meta.get("mode");
        if (mode == null || mode.isNull()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "meta_missing_mode");
        }
        if (!mode.isTextual()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, "meta_mode_must_be_string");
        }

        return ContractOutcome.pass();
    }
}
