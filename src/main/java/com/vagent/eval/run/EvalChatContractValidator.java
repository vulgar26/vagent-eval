package com.vagent.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.vagent.eval.run.RunModel.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public record ContractOutcome(boolean ok, ErrorCode errorCode, String reason, List<String> violations) {
        public static ContractOutcome pass() {
            return new ContractOutcome(true, null, "", List.of());
        }

        public static ContractOutcome fail(ErrorCode code, List<String> violations) {
            String reason = (violations == null || violations.isEmpty()) ? "" : String.valueOf(violations.getFirst());
            List<String> v = violations == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(violations));
            return new ContractOutcome(false, code, reason, v);
        }
    }

    /**
     * 校验 eval/chat 响应是否满足 P0 附录中的「最小必填」形状。
     * <p>
     * <strong>与 Day6 的分工</strong>：本方法<strong>不</strong>校验 {@code retrieval_hits}、{@code sources} 的语义与 membership；
     * 那些由 {@link RunEvaluator} 在业务分支中处理，以便区分 {@link ErrorCode#CONTRACT_VIOLATION} 与
     * {@link ErrorCode#SOURCE_NOT_IN_HITS} 等不同归因。
     *
     * @param root 已解析成功的 JSON 根节点（非 null）
     * @return 通过时 {@link ContractOutcome#ok()} 为 true；失败时带固定 {@link ErrorCode#CONTRACT_VIOLATION} 与简短 reason 机器码
     */
    public ContractOutcome validate(JsonNode root) {
        if (root == null || root.isNull()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, List.of("root_is_null"));
        }
        if (!root.isObject()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, List.of("root_must_be_object"));
        }

        List<String> violations = new ArrayList<>();

        JsonNode answer = root.get("answer");
        if (answer == null || answer.isNull()) {
            violations.add("missing_answer");
        }
        if (answer != null && !answer.isNull() && !answer.isTextual()) {
            violations.add("answer_must_be_string");
        }

        JsonNode behavior = root.get("behavior");
        if (behavior == null || behavior.isNull()) {
            violations.add("missing_behavior");
        }
        if (behavior != null && !behavior.isNull() && !behavior.isTextual()) {
            violations.add("behavior_must_be_string");
        }

        JsonNode latency = root.get("latency_ms");
        if (latency == null || latency.isNull()) {
            violations.add("missing_latency_ms");
        }
        if (latency != null && !latency.isNull() && !latency.isNumber()) {
            violations.add("latency_ms_must_be_number");
        }

        JsonNode caps = root.get("capabilities");
        if (caps == null || caps.isNull()) {
            violations.add("missing_capabilities");
        }
        if (caps != null && !caps.isNull() && !caps.isObject()) {
            violations.add("capabilities_must_be_object");
        }

        JsonNode meta = root.get("meta");
        if (meta == null || meta.isNull()) {
            violations.add("missing_meta");
        }
        if (meta != null && !meta.isNull() && !meta.isObject()) {
            violations.add("meta_must_be_object");
        }
        if (meta != null && meta.isObject()) {
            JsonNode mode = meta.get("mode");
            if (mode == null || mode.isNull()) {
                violations.add("meta_missing_mode");
            } else if (!mode.isTextual()) {
                violations.add("meta_mode_must_be_string");
            }
        }

        if (!violations.isEmpty()) {
            return ContractOutcome.fail(ErrorCode.CONTRACT_VIOLATION, violations);
        }
        return ContractOutcome.pass();
    }
}
