-- Dataset / case 持久化：重启后仍可 list/import、run.report 切片可关联题面

CREATE TABLE IF NOT EXISTS eval_dataset (
    dataset_id   TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    version      TEXT NOT NULL,
    description  TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_dataset_created_at_desc
    ON eval_dataset (created_at DESC);


CREATE TABLE IF NOT EXISTS eval_case (
    id                   BIGSERIAL PRIMARY KEY,
    dataset_id           TEXT NOT NULL REFERENCES eval_dataset (dataset_id) ON DELETE CASCADE,
    case_id              TEXT NOT NULL,
    question             TEXT NOT NULL,
    expected_behavior    TEXT NOT NULL,
    requires_citations   BOOLEAN NOT NULL,
    tags_json            JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_case_dataset_id_id
    ON eval_case (dataset_id, id);

COMMENT ON TABLE eval_dataset IS 'Imported eval datasets; survives process restart.';
COMMENT ON TABLE eval_case IS 'Cases per dataset; insert order preserved by id for listAllCases.';
