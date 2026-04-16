-- 阶段三：审计与合规（最小可用版）
--
-- 目标：
-- - 记录“管理面操作”的可追溯证据（谁/何时/从哪/做了什么/结果如何）
-- - 默认 actor 先写死为 local_dev（后续接入账号体系或 token 指纹再升级）
-- - 不落明文敏感信息（token/query/answer），扩展信息统一走 detail_json（写入前需脱敏）

CREATE TABLE IF NOT EXISTS eval_audit_event (
    event_id     BIGSERIAL PRIMARY KEY,
    event_time   TIMESTAMPTZ NOT NULL,
    event_type   TEXT NOT NULL,
    actor        TEXT NOT NULL,
    client_ip    TEXT NOT NULL DEFAULT '',
    method       TEXT NOT NULL DEFAULT '',
    path         TEXT NOT NULL DEFAULT '',

    -- 便于按对象追溯（可为空：例如全局状态查询）
    run_id       TEXT NULL,
    dataset_id   TEXT NULL,
    target_id    TEXT NULL,

    -- OK / REJECTED / ERROR
    status       TEXT NOT NULL,
    -- 稳定机器码：USER_REQUEST / RETENTION / DISABLED / CIDR_DENIED / INVALID_TOKEN 等
    reason       TEXT NOT NULL DEFAULT '',

    -- 扩展字段（必须先做脱敏/截断再写入）
    detail_json  JSONB NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_audit_event_time_desc
    ON eval_audit_event (event_time DESC);

CREATE INDEX IF NOT EXISTS idx_eval_audit_event_run_time_desc
    ON eval_audit_event (run_id, event_time DESC);

CREATE INDEX IF NOT EXISTS idx_eval_audit_event_dataset_time_desc
    ON eval_audit_event (dataset_id, event_time DESC);

CREATE INDEX IF NOT EXISTS idx_eval_audit_event_type_time_desc
    ON eval_audit_event (event_type, event_time DESC);

