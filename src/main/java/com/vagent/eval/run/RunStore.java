package com.vagent.eval.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import com.vagent.eval.run.RunModel.RunStatus;
import com.vagent.eval.run.RunModel.Verdict;
import com.vagent.eval.security.DebugSanitizer;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 阶段一：JDBC 落库版 Run/Result 存储。
 * <p>
 * 为什么要从内存切到 DB：
 * <ul>
 *   <li>服务重启不丢 run/results（可审计、可对比、可回归）；</li>
 *   <li>{@code DELETE /runs/{id}} 与 retention 才真正生效（而不是“清内存”）；</li>
 *   <li>为后续 B（队列/worker、多实例、配额）准备统一事实来源。</li>
 * </ul>
 * <p>
 * 兼容性原则：对外 API 形状不变；上层（{@link RunApi}/{@link RunRunner}/{@link RunReportService}）仍调用本类方法。
 */
@Component
public class RunStore {

    private static final TypeReference<java.util.Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final RowMapper<EvalRun> RUN_ROW_MAPPER = (rs, rowNum) -> new EvalRun(
            rs.getString("run_id"),
            rs.getString("dataset_id"),
            rs.getString("target_id"),
            RunStatus.valueOf(rs.getString("status")),
            rs.getInt("total_cases"),
            rs.getInt("completed_cases"),
            readInstant(rs, "created_at"),
            readInstant(rs, "started_at"),
            readInstant(rs, "finished_at"),
            rs.getString("cancel_reason")
    );

    /**
     * 结果行映射依赖 {@link #objectMapper} 解析 JSONB，因此必须在构造器里初始化（避免“尚未初始化变量”编译错误）。
     */
    private final RowMapper<EvalResult> RESULT_ROW_MAPPER;

    public RunStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.RESULT_ROW_MAPPER = (rs, rowNum) -> {
            String debugJson = rs.getString("debug_json");
            java.util.Map<String, Object> debug = null;
            if (debugJson != null && !debugJson.isBlank()) {
                try {
                    debug = this.objectMapper.readValue(debugJson, MAP_TYPE);
                } catch (Exception e) {
                    // debug 仅用于排障，不应因为某次历史脏数据导致整个 results/report 无法读取。
                    debug = java.util.Map.of("debug_parse_error", e.getClass().getSimpleName());
                }
            }
            String metaJson = rs.getString("target_meta_json");
            java.util.Map<String, Object> meta = null;
            if (metaJson != null && !metaJson.isBlank()) {
                try {
                    meta = this.objectMapper.readValue(metaJson, MAP_TYPE);
                } catch (Exception e) {
                    meta = java.util.Map.of("meta_parse_error", e.getClass().getSimpleName());
                }
            }
            String ec = rs.getString("error_code");
            return new EvalResult(
                    rs.getString("run_id"),
                    rs.getString("dataset_id"),
                    rs.getString("target_id"),
                    rs.getString("case_id"),
                    Verdict.valueOf(rs.getString("verdict")),
                    ec == null ? null : RunModel.ErrorCode.valueOf(ec),
                    rs.getLong("latency_ms"),
                    readInstant(rs, "created_at"),
                    meta,
                    debug
            );
        };
    }

    /**
     * 兼容不同 JDBC 驱动/配置对 timestamptz 的映射差异：
     * <ul>
     *   <li>有的驱动支持直接 {@code getObject(..., Instant.class)}</li>
     *   <li>也可能返回 {@link OffsetDateTime} 或 {@link Timestamp}</li>
     * </ul>
     * <p>
     * 这里统一转为 {@link Instant}，避免在 RowMapper 阶段抛异常导致整个 API 500。
     */
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
        if (o instanceof Timestamp tst) {
            return tst.toInstant();
        }
        // 最后兜底：让异常尽量可读
        throw new java.sql.SQLException("unsupported timestamp type for " + col + ": " + o.getClass().getName());
    }

    /**
     * 写入侧时间戳统一用 {@link Timestamp}：
     * <p>
     * 部分 JDBC/驱动组合对 {@code PreparedStatement#setObject(..., Instant)} 支持不完整，
     * 会在 INSERT/UPDATE 时直接抛 {@link DataAccessException}，表现为管理 API 500 且数据库无新增行。
     * {@link Timestamp} 是最保守、跨版本最稳的绑定方式。
     */
    private static Timestamp ts(Instant i) {
        return Timestamp.from(i == null ? Instant.now() : i);
    }

    public EvalRun createRun(String datasetId, String targetId, int totalCases) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        int n = jdbc.update("""
                        INSERT INTO eval_run (
                          run_id, dataset_id, target_id, status,
                          total_cases, completed_cases,
                          created_at, started_at, finished_at,
                          cancel_requested, cancel_reason
                        ) VALUES (?, ?, ?, ?, ?, 0, ?, NULL, NULL, FALSE, '')
                        """,
                runId,
                datasetId,
                targetId,
                RunStatus.PENDING.name(),
                totalCases,
                ts(now)
        );
        if (n != 1) {
            throw new IllegalStateException("failed to create run");
        }
        return new EvalRun(runId, datasetId, targetId, RunStatus.PENDING, totalCases, 0, now, null, null, "");
    }

    public Optional<EvalRun> getRun(String runId) {
        try {
            EvalRun run = jdbc.queryForObject(
                    "SELECT * FROM eval_run WHERE run_id = ?",
                    RUN_ROW_MAPPER,
                    runId
            );
            return Optional.ofNullable(run);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<EvalRun> listRuns() {
        return jdbc.query("SELECT * FROM eval_run ORDER BY created_at DESC", RUN_ROW_MAPPER);
    }

    public void markStarted(String runId) {
        int n = jdbc.update(
                "UPDATE eval_run SET status = ?, started_at = ? WHERE run_id = ?",
                RunStatus.RUNNING.name(),
                ts(Instant.now()),
                runId
        );
        if (n != 1) {
            throw new IllegalArgumentException("run not found");
        }
    }

    /**
     * 仅在当前仍为 {@link RunStatus#PENDING} 时原子推进为 {@link RunStatus#RUNNING}；用于多实例/Redis 队列下防止同一 run 被重复执行。
     *
     * @return true 表示本调用赢得了启动权；false 表示 run 不存在或已不是 PENDING
     */
    public boolean tryMarkStarted(String runId) {
        int n = jdbc.update(
                "UPDATE eval_run SET status = ?, started_at = ? WHERE run_id = ? AND status = ?",
                RunStatus.RUNNING.name(),
                ts(Instant.now()),
                runId,
                RunStatus.PENDING.name()
        );
        return n == 1;
    }

    public void markFinished(String runId) {
        int n = jdbc.update(
                "UPDATE eval_run SET status = ?, finished_at = ? WHERE run_id = ?",
                RunStatus.FINISHED.name(),
                ts(Instant.now()),
                runId
        );
        if (n != 1) {
            throw new IllegalArgumentException("run not found");
        }
    }

    public void markCancelled(String runId, String reason) {
        String r = reason == null ? "" : reason;
        int n = jdbc.update(
                "UPDATE eval_run SET status = ?, cancel_reason = ?, finished_at = ?, cancel_requested = TRUE WHERE run_id = ?",
                RunStatus.CANCELLED.name(),
                r,
                ts(Instant.now()),
                runId
        );
        if (n != 1) {
            throw new IllegalArgumentException("run not found");
        }
    }

    public boolean isCancelRequested(String runId) {
        try {
            Boolean b = jdbc.queryForObject(
                    "SELECT cancel_requested FROM eval_run WHERE run_id = ?",
                    Boolean.class,
                    runId
            );
            if (b == null) {
                throw new IllegalArgumentException("run not found");
            }
            return b;
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("run not found");
        }
    }

    public void requestCancel(String runId, String reason) {
        String r = reason == null ? "" : reason;
        int n = jdbc.update(
                "UPDATE eval_run SET cancel_requested = TRUE, cancel_reason = ? WHERE run_id = ?",
                r,
                runId
        );
        if (n != 1) {
            throw new IllegalArgumentException("run not found");
        }
    }

    /**
     * 写入一条 case 结果，并同步推进 {@code completed_cases}。
     * <p>
     * 事务性要求：插入 result 与 completed_cases++ 必须一致，否则进度与结果数可能不一致。
     */
    @Transactional
    public void appendResult(EvalResult r) {
        if (r == null) {
            throw new IllegalArgumentException("result is null");
        }
        String debugJson = null;
        // 阶段三：写库前先清洗 debug，避免敏感信息进入 Postgres。
        Map<String, Object> safeDebug = DebugSanitizer.sanitizeForStorage(r.debug());
        if (safeDebug != null && !safeDebug.isEmpty()) {
            try {
                debugJson = objectMapper.writeValueAsString(safeDebug);
            } catch (Exception e) {
                // debug 写入失败不应影响判定主流程；回退为最小可读错误信息。
                debugJson = "{\"debug_serialize_error\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        }

        String metaJson = null;
        Map<String, Object> metaRow = r.meta();
        if (metaRow != null && !metaRow.isEmpty()) {
            try {
                metaJson = objectMapper.writeValueAsString(metaRow);
            } catch (Exception e) {
                metaJson = "{\"meta_serialize_error\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        }

        try {
            // 关键点：completed_cases 只在“确实插入了一条新 result”时 +1。
            // 通过 CTE + ON CONFLICT DO NOTHING 达到“幂等写入”的效果，便于后续多实例/重试安全。
            int updated = jdbc.update("""
                            WITH ins AS (
                              INSERT INTO eval_result (
                                run_id, case_id, dataset_id, target_id,
                                verdict, error_code, latency_ms, created_at, debug_json, target_meta_json
                              )
                              VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                              ON CONFLICT (run_id, case_id) DO NOTHING
                              RETURNING 1
                            )
                            UPDATE eval_run
                            SET completed_cases = completed_cases + (SELECT COUNT(*) FROM ins)
                            WHERE run_id = ?
                            """,
                    r.runId(),
                    r.caseId(),
                    r.datasetId(),
                    r.targetId(),
                    r.verdict() == null ? Verdict.FAIL.name() : r.verdict().name(),
                    r.errorCode() == null ? null : r.errorCode().name(),
                    r.latencyMs(),
                    ts(r.createdAt()),
                    debugJson,
                    metaJson,
                    r.runId()
            );
            if (updated != 1) {
                throw new IllegalArgumentException("run not found");
            }
        } catch (DataAccessException e) {
            throw e;
        }
    }

    /**
     * 分页返回某 run 的结果；{@code caseIdFilter} 非空时先按 {@link EvalResult#caseId()} 精确匹配再分页（Day8 便于从 compare 直达单题）。
     */
    public List<EvalResult> listResults(String runId, int offset, int limit) {
        return listResults(runId, offset, limit, null);
    }

    public List<EvalResult> listResults(String runId, int offset, int limit, String caseIdFilter) {
        int off = Math.max(0, offset);
        int lim = Math.max(0, limit);

        String cid = (caseIdFilter == null || caseIdFilter.isBlank()) ? null : caseIdFilter.trim();
        if (cid != null) {
            return jdbc.query("""
                            SELECT * FROM eval_result
                            WHERE run_id = ? AND case_id = ?
                            ORDER BY created_at ASC
                            OFFSET ? LIMIT ?
                            """,
                    RESULT_ROW_MAPPER,
                    runId,
                    cid,
                    off,
                    lim
            );
        }
        return jdbc.query("""
                        SELECT * FROM eval_result
                        WHERE run_id = ?
                        ORDER BY created_at ASC
                        OFFSET ? LIMIT ?
                        """,
                RESULT_ROW_MAPPER,
                runId,
                off,
                lim
        );
    }

    /**
     * Day7：报表聚合需要完整结果集，不分页；返回不可变拷贝，避免外部修改内部列表。
     *
     * @param runId 已存在的 run
     * @return 当前已追加的所有 {@link EvalResult}，顺序同写入顺序
     */
    public List<EvalResult> listAllResults(String runId) {
        return jdbc.query("""
                        SELECT * FROM eval_result
                        WHERE run_id = ?
                        ORDER BY created_at ASC
                        """,
                RESULT_ROW_MAPPER,
                runId
        );
    }

    /**
     * P1：删除 run（含结果）。用于合规/删除权与 retention 清理。
     *
     * @return true 表示删除成功；false 表示不存在
     */
    public boolean deleteRun(String runId) {
        if (runId == null) {
            return false;
        }
        int n = jdbc.update("DELETE FROM eval_run WHERE run_id = ?", runId);
        return n == 1;
    }

    /**
     * P1：批量清理已结束且早于 cutoff 的 run。
     */
    public int deleteRunsFinishedBefore(Instant cutoffExclusive) {
        if (cutoffExclusive == null) {
            return 0;
        }
        return jdbc.update("""
                        DELETE FROM eval_run
                        WHERE finished_at IS NOT NULL
                          AND finished_at < ?
                          AND status IN (?, ?)
                        """,
                ts(cutoffExclusive),
                RunStatus.FINISHED.name(),
                RunStatus.CANCELLED.name()
        );
    }
}

