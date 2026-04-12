package com.vagent.eval.security;

/**
 * Day9：评测管理 API 拒绝原因枚举，写入审计日志与部分错误 JSON 的 {@code audit_reason} 字段。
 * <p>
 * 取值稳定便于检索与告警；若 SSOT 扩展新原因，在此追加枚举并同步 Filter / Advice。
 */
public enum AuditReason {

    /** {@code eval.api.enabled=false}，对外表现为未暴露资源。 */
    DISABLED,

    /** 客户端 IP 不在 {@code eval.api.allow-cidrs} 任一 CIDR 内。 */
    CIDR_DENIED,

    /** 配置了 {@code token-hash} 但缺少或错误 {@link EvalSecurityConstants#HDR_API_TOKEN}。 */
    INVALID_TOKEN,

    /** {@code eval.api.require-https=true} 但请求非 HTTPS。 */
    HTTPS_REQUIRED,

    /**
     * 响应体中含仅允许在 {@link EvalSecurityConstants#HDR_EVAL_DEBUG} 模式下返回的 debug 字段，
     * 与 {@link com.vagent.eval.run.RunModel.ErrorCode#SECURITY_BOUNDARY_VIOLATION} 对应。
     */
    EVAL_DEBUG_REQUIRED
}
