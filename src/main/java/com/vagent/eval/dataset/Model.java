package com.vagent.eval.dataset;

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
            Instant createdAt
    ) {
    }
}

