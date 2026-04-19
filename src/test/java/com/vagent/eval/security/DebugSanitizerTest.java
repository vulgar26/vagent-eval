package com.vagent.eval.security;

import com.vagent.eval.run.RunEvaluator;
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
}

