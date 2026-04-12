package com.vagent.eval.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Day9：对 {@link com.vagent.eval.dataset} 与 {@link com.vagent.eval.run} 包内控制器的 JSON 响应做出站检查：
 * 未带 {@link EvalSecurityConstants#HDR_EVAL_DEBUG} 时，禁止在 {@link com.vagent.eval.run.RunModel.EvalResult#debug()} 中出现
 * {@link EvalSecurityConstants#FORBIDDEN_DEBUG_KEYS_WITHOUT_EVAL_DEBUG} 中的键；否则返回 403 与
 * {@link com.vagent.eval.run.RunModel.ErrorCode#SECURITY_BOUNDARY_VIOLATION} 语义。
 * <p>
 * 不作用于 {@code com.vagent.eval.web} 下的探针等，避免误伤 {@code /api/v1/eval/chat}。
 */
@ControllerAdvice(basePackages = {"com.vagent.eval.dataset", "com.vagent.eval.run"})
public class EvalDebugResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final EvalAuditLogger auditLogger;

    public EvalDebugResponseBodyAdvice(EvalAuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        if (body == null || !MediaType.APPLICATION_JSON.includes(selectedContentType)) {
            return body;
        }
        HttpServletRequest req = null;
        if (request instanceof ServletServerHttpRequest s) {
            req = s.getServletRequest();
        }
        if (req == null) {
            return body;
        }
        String debugHdr = req.getHeader(EvalSecurityConstants.HDR_EVAL_DEBUG);
        if (EvalDebugExposureChecker.violates(body, debugHdr)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            auditLogger.record(
                    AuditReason.EVAL_DEBUG_REQUIRED,
                    req.getMethod(),
                    req.getRequestURI(),
                    EvalApiSecurityFilter.clientIp(req)
            );
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "SECURITY_BOUNDARY_VIOLATION");
            err.put("error_code", "SECURITY_BOUNDARY_VIOLATION");
            err.put("audit_reason", AuditReason.EVAL_DEBUG_REQUIRED.name());
            err.put("message", "debug contains fields that require " + EvalSecurityConstants.HDR_EVAL_DEBUG + " header");
            return err;
        }
        return body;
    }
}
