package com.eval.security;

import com.eval.run.RunEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DebugSanitizerTest {

    @Test
    void sanitizeForStorage_removesSensitiveKeys() {
        Map<String, Object> in = Map.of(
                "eval_rule_version", RunEvaluator.EVAL_RULE_VERSION,
                "query", "hello",
                "answer", "world",
                "exception_message", "boom",
                "contract_violations", List.of("a", "b")
        );

        Map<String, Object> out = DebugSanitizer.sanitizeForStorage(in);
        assertThat(out).containsEntry("eval_rule_version", RunEvaluator.EVAL_RULE_VERSION);

        // 敏感明文不应出现
        assertThat(out).doesNotContainKeys("query", "answer");
        // 但会保留追踪用的 hash/len
        assertThat(out).containsKeys("query_len", "query_sha256", "answer_len", "answer_sha256");

        // 白名单策略：未知字段（如 exception_message）默认不落库（除非显式允许）
        assertThat(out).doesNotContainKey("exception_message");
    }

    @Test
    void sanitizeForOutbound_withoutEvalDebug_removesForbiddenKeys() {
        Map<String, Object> in = Map.of(
                "exception_message", "secret",
                "verdict_reason", "x"
        );
        Map<String, Object> out = DebugSanitizer.sanitizeForOutbound(in, false);
        assertThat(out).doesNotContainKey("exception_message");
        assertThat(out).containsEntry("verdict_reason", "x");
    }

    /**
     * A（expected_error_code 比对）回归：判定器写入的三个错误码比对键必须能<strong>活着落库</strong>。
     * <p>它们是契约枚举（被测系统业务码，如 {@code PROMPT_INJECTION_BLOCKED}），非业务明文，
     * 符合白名单「解释为何判该 verdict/error_code」的收录标准。若被白名单默默丢弃，报告里
     * 就看不到 deny 用例究竟是「比对通过」还是「根本没比对」——那正是会说谎的绿。</p>
     */
    @Test
    void sanitizeForStorage_keepsErrorCodeComparisonKeys() {
        Map<String, Object> in = Map.of(
                "expected_error_code", "TOOL_OUTPUT_INJECTION_QUERY_BLOCKED",
                "actual_error_code", "PROMPT_INJECTION_BLOCKED",
                "error_code_match", false
        );
        Map<String, Object> out = DebugSanitizer.sanitizeForStorage(in);
        assertThat(out)
                .containsEntry("expected_error_code", "TOOL_OUTPUT_INJECTION_QUERY_BLOCKED")
                .containsEntry("actual_error_code", "PROMPT_INJECTION_BLOCKED")
                .containsEntry("error_code_match", false);
    }

    @Test
    void sanitizeForOutbound_keepsErrorCodeComparisonKeys() {
        Map<String, Object> in = Map.of(
                "expected_error_code", "PROMPT_INJECTION_BLOCKED",
                "actual_error_code", "PROMPT_INJECTION_BLOCKED",
                "error_code_match", true
        );
        Map<String, Object> out = DebugSanitizer.sanitizeForOutbound(in, false);
        assertThat(out)
                .containsEntry("expected_error_code", "PROMPT_INJECTION_BLOCKED")
                .containsEntry("actual_error_code", "PROMPT_INJECTION_BLOCKED")
                .containsEntry("error_code_match", true);
    }
}

