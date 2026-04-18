package com.vagent.eval.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.dataset.Model.EvalDataset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC 版题库存储：与 {@link com.vagent.eval.run.RunStore} 共用数据源，服务重启后 dataset/case 仍可查。
 */
@Component
public class JdbcDatasetStore implements DatasetStore {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final RowMapper<EvalDataset> DATASET_ROW_MAPPER = (rs, rowNum) -> new EvalDataset(
            rs.getString("dataset_id"),
            rs.getString("name"),
            rs.getString("version"),
            rs.getString("description"),
            readInstant(rs, "created_at"),
            rs.getInt("case_count")
    );

    public JdbcDatasetStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public EvalDataset createDataset(String name, String version, String description) {
        String id = "ds_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        String desc = description == null ? "" : description;
        jdbc.update(
                "INSERT INTO eval_dataset (dataset_id, name, version, description, created_at) VALUES (?,?,?,?,?)",
                id,
                name,
                version,
                desc,
                Timestamp.from(now)
        );
        return new EvalDataset(id, name, version, desc, now, 0);
    }

    @Override
    public Optional<EvalDataset> getDataset(String datasetId) {
        List<EvalDataset> rows = jdbc.query(
                """
                        SELECT d.dataset_id, d.name, d.version, d.description, d.created_at,
                               COUNT(c.id)::int AS case_count
                        FROM eval_dataset d
                        LEFT JOIN eval_case c ON c.dataset_id = d.dataset_id
                        WHERE d.dataset_id = ?
                        GROUP BY d.dataset_id, d.name, d.version, d.description, d.created_at
                        """,
                DATASET_ROW_MAPPER,
                datasetId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

    @Override
    public List<EvalDataset> listDatasets() {
        return jdbc.query(
                """
                        SELECT d.dataset_id, d.name, d.version, d.description, d.created_at,
                               COUNT(c.id)::int AS case_count
                        FROM eval_dataset d
                        LEFT JOIN eval_case c ON c.dataset_id = d.dataset_id
                        GROUP BY d.dataset_id, d.name, d.version, d.description, d.created_at
                        ORDER BY d.created_at DESC
                        """,
                DATASET_ROW_MAPPER
        );
    }

    @Override
    @Transactional
    public int appendCases(String datasetId, List<EvalCase> newCases) {
        if (newCases == null || newCases.isEmpty()) {
            return caseCount(datasetId);
        }
        if (!datasetExists(datasetId)) {
            throw new IllegalArgumentException("dataset not found");
        }
        for (EvalCase c : newCases) {
            String tagsJson = writeTagsJson(c.tags());
            // 与 RunStore 写入 JSONB 一致：须 CAST，否则驱动按 varchar 绑定会触发「jsonb vs character varying」SQL 错误 → import 500。
            jdbc.update(
                    """
                            INSERT INTO eval_case (dataset_id, case_id, question, expected_behavior, requires_citations, tags_json, created_at)
                            VALUES (?,?,?,?,?,CAST(? AS jsonb),?)
                            """,
                    datasetId,
                    c.caseId(),
                    c.question(),
                    c.expectedBehavior().toJson(),
                    c.requiresCitations(),
                    tagsJson,
                    Timestamp.from(c.createdAt())
            );
        }
        return caseCount(datasetId);
    }

    @Override
    public List<EvalCase> listCases(String datasetId, int offset, int limit) {
        if (!datasetExists(datasetId)) {
            throw new IllegalArgumentException("dataset not found");
        }
        int lim = Math.max(0, limit);
        int off = Math.max(0, offset);
        return jdbc.query(
                """
                        SELECT case_id, dataset_id, question, expected_behavior, requires_citations, tags_json, created_at
                        FROM eval_case
                        WHERE dataset_id = ?
                        ORDER BY id ASC
                        LIMIT ? OFFSET ?
                        """,
                (rs, rowNum) -> mapCase(rs),
                datasetId,
                lim,
                off
        );
    }

    @Override
    public List<EvalCase> listAllCases(String datasetId) {
        if (!datasetExists(datasetId)) {
            throw new IllegalArgumentException("dataset not found");
        }
        return jdbc.query(
                """
                        SELECT case_id, dataset_id, question, expected_behavior, requires_citations, tags_json, created_at
                        FROM eval_case
                        WHERE dataset_id = ?
                        ORDER BY id ASC
                        """,
                (rs, rowNum) -> mapCase(rs),
                datasetId
        );
    }

    @Override
    public int caseCount(String datasetId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM eval_case WHERE dataset_id = ?",
                Integer.class,
                datasetId
        );
        return n == null ? 0 : n;
    }

    @Override
    @Transactional
    public boolean deleteDataset(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            return false;
        }
        jdbc.update("DELETE FROM eval_run WHERE dataset_id = ?", datasetId);
        int n = jdbc.update("DELETE FROM eval_dataset WHERE dataset_id = ?", datasetId);
        return n > 0;
    }

    private boolean datasetExists(String datasetId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM eval_dataset WHERE dataset_id = ?",
                Integer.class,
                datasetId
        );
        return n != null && n > 0;
    }

    private EvalCase mapCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> tags = readTagsJson(rs.getString("tags_json"));
        return new EvalCase(
                rs.getString("case_id"),
                rs.getString("dataset_id"),
                rs.getString("question"),
                EvalExpectedBehavior.parse(rs.getString("expected_behavior")),
                rs.getBoolean("requires_citations"),
                tags,
                readInstant(rs, "created_at")
        );
    }

    private String writeTagsJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception e) {
            throw new IllegalStateException("tags json", e);
        }
    }

    private List<String> readTagsJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, STRING_LIST_TYPE);
            return list == null ? List.of() : List.copyOf(list);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Instant readInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Object o = rs.getObject(col);
        if (o == null) {
            return null;
        }
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (o instanceof Timestamp ts) {
            return ts.toInstant();
        }
        return Instant.parse(o.toString());
    }
}
