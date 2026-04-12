package com.vagent.eval.security;

import java.util.Set;

/**
 * Day9：安全相关 HTTP 头名与 debug 敏感键集合（集中定义避免散落魔法字符串）。
 */
public final class EvalSecurityConstants {

    private EvalSecurityConstants() {
    }

    /**
     * 调用方提供的明文 API token；服务端与配置中的 {@code eval.api.token-hash}（SHA-256 hex）比对。
     */
    public static final String HDR_API_TOKEN = "X-Eval-Api-Token";

    /**
     * 置为 {@code 1} 或 {@code true}（忽略大小写）时，允许响应中携带 {@link #FORBIDDEN_DEBUG_KEYS_WITHOUT_EVAL_DEBUG} 所列 debug 字段。
     */
    public static final String HDR_EVAL_DEBUG = "X-Eval-Debug";

    /**
     * 未开启 EVAL_DEBUG 时，若 {@code debug} Map 中出现任一 key，则整响应拒绝为 {@code SECURITY_BOUNDARY_VIOLATION}。
     * <p>
     * 选取标准：易泄露排障细节或可用于推断内部实现/哈希的字段（与 {@link com.vagent.eval.run.RunRunner}/{@link com.vagent.eval.run.RunEvaluator} 写入键对齐）。
     */
    public static final Set<String> FORBIDDEN_DEBUG_KEYS_WITHOUT_EVAL_DEBUG = Set.of(
            "exception_message",
            "rejected_membership_hash_prefix",
            "rejected_source_id_prefix"
    );
}
