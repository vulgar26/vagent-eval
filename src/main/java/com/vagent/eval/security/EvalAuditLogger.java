package com.vagent.eval.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Day9：评测管理 API 审计日志。
 * <p>
 * P0 使用独立 logger 名称 {@value #LOGGER_NAME}，便于生产按前缀采集；每行键值对风格，兼顾人读与简单 grep。
 * 后续可换写 DB/消息队列而不改调用方。
 */
@Component
public class EvalAuditLogger {

    static final String LOGGER_NAME = "com.vagent.eval.audit";

    private final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    /**
     * 记录一次与安全策略相关的决策（多为拒绝）。
     *
     * @param reason   稳定枚举，对应交付物中的 audit_reason
     * @param method   HTTP 方法
     * @param path     请求路径（含 query 前路径）
     * @param clientIp 解析后的客户端 IP（已考虑 X-Forwarded-For 首段）
     */
    public void record(AuditReason reason, String method, String path, String clientIp) {
        log.warn("eval_audit reason={} method={} path={} client_ip={}", reason.name(), method, path, clientIp);
    }
}
