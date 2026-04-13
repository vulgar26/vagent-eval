package com.vagent.eval.run;

import java.time.Instant;
import java.util.Map;

/**
 * Day3 run 数据模型（P0：先内存落地）。
 * 对外 JSON 字段采用 snake_case（Jackson 全局策略）。
 */
public final class RunModel {

    private RunModel() {
    }

    public enum RunStatus {
        PENDING,
        RUNNING,
        FINISHED,
        CANCELLED
    }

    public enum Verdict {
        PASS,
        FAIL,
        SKIPPED_UNSUPPORTED
    }

    /**
     * 结果失败/跳过原因码（SSOT：p0-execution-map.md 附录 D + eval-upgrade.md）。
     * P0 先覆盖 Day3 会用到的最小集合；后续可扩展但对外输出保持枚举一致。
     */
    public enum ErrorCode {
        AUTH,
        RATE_LIMITED,
        TIMEOUT,
        UPSTREAM_UNAVAILABLE,
        PARSE_ERROR,
        RETRIEVE_EMPTY,
        RETRIEVE_LOW_CONFIDENCE,
        GUARDRAIL_TRIGGERED,
        SOURCE_NOT_IN_HITS,
        CONTRACT_VIOLATION,
        /** P0+ S1：期望 {@code behavior} 与响应 {@code behavior} 不一致（原误报为 {@link #UNKNOWN}）。 */
        BEHAVIOR_MISMATCH,
        /** P0+ S1：期望 {@code tool} 路径但 {@code required&&used&&succeeded} 未满足（原误报为 {@link #UNKNOWN}）。 */
        TOOL_EXPECTATION_NOT_MET,
        SECURITY_BOUNDARY_VIOLATION,
        POLICY_DISABLED,
        UNKNOWN,

        // 用于 verdict=SKIPPED_UNSUPPORTED 时的统一归因（eval-upgrade.md 有该枚举）
        SKIPPED_UNSUPPORTED
    }

    public record EvalRun(
            String runId,
            String datasetId,
            String targetId,
            RunStatus status,
            int totalCases,
            int completedCases,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            String cancelReason
    ) {
    }

    public record EvalResult(
            String runId,
            String datasetId,
            String targetId,
            String caseId,
            Verdict verdict,
            ErrorCode errorCode,
            long latencyMs,
            Instant createdAt,
            Map<String, Object> debug
    ) {
    }
}

