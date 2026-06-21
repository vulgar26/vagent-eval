-- A（Phase 3）：eval_case 增加可选业务错误码列。
-- 安全 deny 用例可声明期望的被测系统业务码（如 PROMPT_INJECTION_BLOCKED），
-- 让判定器从「拦没拦」(behavior=deny) 进一步验证「拦对没拦对」(error_code 正确)。
-- 可空：历史用例与不关心错误码的用例保持 NULL，判定器对 NULL 不做比对（向后兼容）。

ALTER TABLE eval_case
    ADD COLUMN IF NOT EXISTS expected_error_code TEXT;

COMMENT ON COLUMN eval_case.expected_error_code IS
    'Optional expected business error_code of the target response (e.g. PROMPT_INJECTION_BLOCKED); NULL = not asserted.';
