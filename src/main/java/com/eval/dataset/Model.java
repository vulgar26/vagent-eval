package com.eval.dataset;

import java.time.Instant;
import java.util.List;

/**
 * Day2 数据模型（先内存落地，P0 以跑通导入/查询为主）。
 * 对外 JSON 字段采用 snake_case（由 Jackson 全局策略保证）。
 */
public final class Model {

    private Model() {
    }

    public record EvalDataset(
            String datasetId,
            String name,
            String version,
            String description,
            Instant createdAt,
            int caseCount
    ) {
    }

    public record EvalCase(
            String caseId,
            String datasetId,
            String question,
            EvalExpectedBehavior expectedBehavior,
            boolean requiresCitations,
            List<String> tags,
            String expectedErrorCode,
            Instant createdAt
    ) {
        /**
         * 向后兼容构造器：不带 {@code expectedErrorCode} 的旧 7 参形态等价于该字段为 {@code null}（不校验业务错误码）。
         * 保留它让既有调用点与历史数据路径无需逐一改写。
         */
        public EvalCase(
                String caseId,
                String datasetId,
                String question,
                EvalExpectedBehavior expectedBehavior,
                boolean requiresCitations,
                List<String> tags,
                Instant createdAt
        ) {
            this(caseId, datasetId, question, expectedBehavior, requiresCitations, tags, null, createdAt);
        }
    }
}

