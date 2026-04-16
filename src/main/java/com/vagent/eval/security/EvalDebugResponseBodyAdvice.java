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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        // 阶段三：出站先清洗（更严格），再做“是否违反边界”的阻断判断。
        Object sanitized = sanitizeOutbound(body, debugHdr);
        if (EvalDebugExposureChecker.violates(sanitized, debugHdr)) {
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
        return sanitized;
    }

    /**
     * 仅对“包含 EvalResult 列表的响应”做清洗：
     * <ul>
     *   <li>RunApi.results：Map{results:[EvalResult...]}</li>
     * </ul>
     * 其它响应保持不变，避免误伤报表/compare 的结构。
     */
    private static Object sanitizeOutbound(Object body, String evalDebugHeaderRaw) {
        if (!(body instanceof Map<?, ?> map)) {
            return body;
        }
        Object results = map.get("results");
        if (!(results instanceof List<?> list)) {
            return body;
        }
        boolean allowed = EvalDebugExposureChecker.evalDebugAllowed(evalDebugHeaderRaw);
        List<Object> out = new ArrayList<>(list.size());
        boolean changed = false;
        for (Object o : list) {
            if (o instanceof com.vagent.eval.run.RunModel.EvalResult er) {
                Map<String, Object> safe = DebugSanitizer.sanitizeForOutbound(er.debug(), allowed);
                if (safe != er.debug()) {
                    changed = true;
                }
                out.add(new com.vagent.eval.run.RunModel.EvalResult(
                        er.runId(),
                        er.datasetId(),
                        er.targetId(),
                        er.caseId(),
                        er.verdict(),
                        er.errorCode(),
                        er.latencyMs(),
                        er.createdAt(),
                        safe
                ));
            } else {
                out.add(o);
            }
        }
        if (!changed) {
            return body;
        }
        // 复制一份 Map（保持原 key/value），只替换 results
        Map<String, Object> m2 = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null) {
                m2.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        m2.put("results", out);
        return m2;
    }
}
