package com.vagent.eval.dataset;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * P0 case 行为期望枚举（SSOT：expected_behavior）。
 */
public enum EvalExpectedBehavior {
    ANSWER,
    CLARIFY,
    DENY,
    TOOL;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    public static EvalExpectedBehavior parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("expected_behavior is required");
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("expected_behavior is required");
        }
        return switch (s.toLowerCase()) {
            case "answer" -> ANSWER;
            case "clarify" -> CLARIFY;
            case "deny" -> DENY;
            case "tool" -> TOOL;
            default -> throw new IllegalArgumentException("expected_behavior must be one of answer|clarify|deny|tool");
        };
    }
}

