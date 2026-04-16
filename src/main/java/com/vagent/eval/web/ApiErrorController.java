package com.vagent.eval.web;

import com.vagent.eval.audit.EvalAuditService;
import com.vagent.eval.security.EvalApiSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段三：补齐“未命中 Controller 的错误”（典型是 404/405）为稳定 JSON，并落库审计。
 * <p>
 * 背景：{@link ApiExceptionHandler} 只能捕获“进入 Controller 之后抛出的异常”，无法覆盖：
 * <ul>
 *   <li>路由不存在（404 Not Found）</li>
 *   <li>方法不允许（405 Method Not Allowed）</li>
 * </ul>
 * 这些错误会走 Spring Boot 的 {@code /error}，因此在这里统一输出 JSON，避免返回默认 HTML/结构不稳定 JSON。
 */
@Controller
public class ApiErrorController implements ErrorController {

    private final ErrorAttributes errorAttributes;
    private final EvalAuditService audit;

    public ApiErrorController(ErrorAttributes errorAttributes, EvalAuditService audit) {
        this.errorAttributes = errorAttributes;
        this.audit = audit;
    }

    @RequestMapping(path = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest req) {
        Map<String, Object> attrs = errorAttributes.getErrorAttributes(
                (WebRequest) req,
                ErrorAttributeOptions.defaults()
        );

        int status = 500;
        Object st = attrs.get("status");
        if (st instanceof Number n) {
            status = n.intValue();
        }

        String path = String.valueOf(attrs.getOrDefault("path", req.getRequestURI()));
        String method = req.getMethod();
        String ip = EvalApiSecurityFilter.clientIp(req);

        String reason = "HTTP_" + status;
        String auditStatus = (status >= 500) ? "ERROR" : "REJECTED";

        // 仅记录“管理面路径”相关错误，避免把全站 404 噪音写爆审计表。
        if (path != null && path.startsWith("/api/v1/eval/")) {
            Map<String, Object> detail = new LinkedHashMap<>();
            Object err = attrs.get("error");
            if (err != null) {
                detail.put("error", String.valueOf(err));
            }
            Object msg = attrs.get("message");
            if (msg != null && !String.valueOf(msg).isBlank()) {
                detail.put("message", String.valueOf(msg));
            }
            audit.record(
                    "API_ERROR",
                    auditStatus,
                    reason,
                    ip,
                    method,
                    path,
                    null,
                    null,
                    null,
                    detail
            );
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", (status == 404) ? "NOT_FOUND" : (status == 400) ? "BAD_REQUEST" : (status >= 500) ? "INTERNAL" : "ERROR");
        body.put("status", status);
        body.put("path", path);

        HttpStatus hs = HttpStatus.resolve(status);
        return ResponseEntity.status(hs == null ? HttpStatus.INTERNAL_SERVER_ERROR : hs).body(body);
    }
}

