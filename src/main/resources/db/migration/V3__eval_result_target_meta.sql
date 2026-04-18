-- Vagent 契约：检索观测 SSOT 在响应根级 meta（snake_case JSON）；eval 落库以便跨 run 对比与导出。
ALTER TABLE eval_result
    ADD COLUMN IF NOT EXISTS target_meta_json JSONB NULL;

COMMENT ON COLUMN eval_result.target_meta_json IS
    'Snapshot of upstream EvalChatResponse.meta (lossless keys except optional retrieval_hit_ids strip + size cap).';
