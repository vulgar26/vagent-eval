-- 阶段一：持久化（最小可用版）
--
-- 目标：
-- - vagent-eval 重启后仍能查询历史 run / results / report
-- - 保持现有 API 形状不变（RunApi / RunReportService 仍只需要 runId）
-- - 为后续阶段（指标、审计、并发、队列/worker）预留扩展空间

CREATE TABLE IF NOT EXISTS eval_run (
    run_id            TEXT PRIMARY KEY,
    dataset_id        TEXT NOT NULL,
    target_id         TEXT NOT NULL,
    status            TEXT NOT NULL,
    total_cases       INTEGER NOT NULL,
    completed_cases   INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL,
    started_at        TIMESTAMPTZ NULL,
    finished_at       TIMESTAMPTZ NULL,
    cancel_requested  BOOLEAN NOT NULL DEFAULT FALSE,
    cancel_reason     TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_eval_run_created_at_desc
    ON eval_run (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_eval_run_status
    ON eval_run (status);

CREATE INDEX IF NOT EXISTS idx_eval_run_target_created_at_desc
    ON eval_run (target_id, created_at DESC);


CREATE TABLE IF NOT EXISTS eval_result (
    run_id      TEXT NOT NULL,
    case_id     TEXT NOT NULL,
    dataset_id  TEXT NOT NULL,
    target_id   TEXT NOT NULL,
    verdict     TEXT NOT NULL,
    error_code  TEXT NULL,
    latency_ms  BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    debug_json  JSONB NULL,

    CONSTRAINT pk_eval_result PRIMARY KEY (run_id, case_id),
    CONSTRAINT fk_eval_result_run FOREIGN KEY (run_id) REFERENCES eval_run (run_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_eval_result_run_created_at
    ON eval_result (run_id, created_at);

CREATE INDEX IF NOT EXISTS idx_eval_result_run_error_code
    ON eval_result (run_id, error_code);

CREATE INDEX IF NOT EXISTS idx_eval_result_case_id
    ON eval_result (case_id);

