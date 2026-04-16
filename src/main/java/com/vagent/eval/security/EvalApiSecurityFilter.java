package com.vagent.eval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.audit.EvalAuditService;
import com.vagent.eval.config.EvalProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day9：评测<strong>管理</strong> API 的入站安全链：enabled → HTTPS（可选）→ CIDR（可选）→ token-hash（可选）。
 * <p>
 * <strong>不拦截</strong> {@code /api/v1/eval/chat}：该路径为被测/探针协议，由 {@link com.vagent.eval.run.TargetClient} 等调用，
 * 安全模型与「人调管理面」分离。
 * <p>
 * 拒绝时写 JSON 并打 {@link EvalAuditLogger}；HTTP 状态与组长交付物对齐：disabled→404，token→401，CIDR/HTTPS→403。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EvalApiSecurityFilter extends OncePerRequestFilter {

    private final EvalProperties evalProperties;
    private final EvalAuditLogger auditLogger;
    private final ObjectMapper objectMapper;
    private final EvalAuditService auditService;

    public EvalApiSecurityFilter(EvalProperties evalProperties, EvalAuditLogger auditLogger, ObjectMapper objectMapper, EvalAuditService auditService) {
        this.evalProperties = evalProperties;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        if (!p.startsWith("/api/v1/eval/")) {
            return true;
        }
        return p.startsWith("/api/v1/eval/chat");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        EvalProperties.Api api = evalProperties.getApi();
        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = clientIp(request);

        if (!api.isEnabled()) {
            auditLogger.record(AuditReason.DISABLED, method, path, ip);
            recordSecurityReject(request, "DISABLED", AuditReason.DISABLED.name(), HttpServletResponse.SC_NOT_FOUND);
            writeJson(response, HttpServletResponse.SC_NOT_FOUND, errorBody("NOT_FOUND", AuditReason.DISABLED));
            return;
        }

        if (api.isRequireHttps() && !request.isSecure()) {
            auditLogger.record(AuditReason.HTTPS_REQUIRED, method, path, ip);
            recordSecurityReject(request, "HTTPS_REQUIRED", AuditReason.HTTPS_REQUIRED.name(), HttpServletResponse.SC_FORBIDDEN);
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, errorBody("FORBIDDEN", AuditReason.HTTPS_REQUIRED));
            return;
        }

        List<String> cidrs = api.getAllowCidrs();
        if (cidrs != null && !cidrs.isEmpty()) {
            boolean ok = false;
            for (String cidr : cidrs) {
                if (cidr != null && !cidr.isBlank() && EvalIpv4Cidr.matches(cidr.trim(), ip)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                auditLogger.record(AuditReason.CIDR_DENIED, method, path, ip);
                recordSecurityReject(request, "CIDR_DENIED", AuditReason.CIDR_DENIED.name(), HttpServletResponse.SC_FORBIDDEN);
                writeJson(response, HttpServletResponse.SC_FORBIDDEN, errorBody("FORBIDDEN", AuditReason.CIDR_DENIED));
                return;
            }
        }

        String hash = api.getTokenHash();
        if (hash != null && !hash.isBlank()) {
            String token = request.getHeader(EvalSecurityConstants.HDR_API_TOKEN);
            if (!EvalApiTokenVerifier.valid(token, hash)) {
                auditLogger.record(AuditReason.INVALID_TOKEN, method, path, ip);
                recordSecurityReject(request, "INVALID_TOKEN", AuditReason.INVALID_TOKEN.name(), HttpServletResponse.SC_UNAUTHORIZED);
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, errorBody("UNAUTHORIZED", AuditReason.INVALID_TOKEN));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 阶段三：把“安全链拒绝”也落库到 eval_audit_event，补齐可追溯证据。
     * <p>
     * 这类拒绝不会进入 Controller，因此不会触发 {@link com.vagent.eval.web.ApiExceptionHandler}。
     */
    private void recordSecurityReject(HttpServletRequest request, String reason, String auditReason, int httpStatus) {
        if (auditService == null || request == null) {
            return;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/v1/eval/")) {
            return;
        }
        auditService.recordWithActor(
                EvalAuditService.ACTOR_SYSTEM,
                "API_SECURITY_REJECT",
                "REJECTED",
                reason,
                clientIp(request),
                request.getMethod(),
                path,
                null,
                null,
                null,
                Map.of(
                        "audit_reason", auditReason,
                        "http_status", httpStatus
                )
        );
    }

    /**
     * 提取客户端 IP（优先 X-Forwarded-For 首段；否则用 remoteAddr）。
     * <p>
     * 设为 public：业务审计（RunApi 等）也需要写入 client_ip。
     */
    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeJson(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static Map<String, Object> errorBody(String error, AuditReason reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("audit_reason", reason.name());
        return m;
    }
}
