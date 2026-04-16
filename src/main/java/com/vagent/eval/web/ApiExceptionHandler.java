package com.vagent.eval.web;

import com.vagent.eval.audit.EvalAuditService;
import com.vagent.eval.security.EvalApiSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0：先把错误变成稳定 JSON，便于脚本/CI 调用（避免默认 HTML 错误页）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 从管理 API path 中提取对象 id（用于审计落库，便于按 run/dataset 追溯）。
     * <p>
     * 仅覆盖最常见路径：/api/v1/eval/runs/{runId}、/api/v1/eval/datasets/{datasetId}。
     */
    private static final Pattern RUN_ID_IN_PATH = Pattern.compile("^/api/v1/eval/runs/([^/?#]+)");
    private static final Pattern DATASET_ID_IN_PATH = Pattern.compile("^/api/v1/eval/datasets/([^/?#]+)");

    private final EvalAuditService audit;

    public ApiExceptionHandler(EvalAuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e, HttpServletRequest req) {
        recordFailureAudit("NOT_FOUND", e, req);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "NOT_FOUND", "message", safeMsg(e)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e, HttpServletRequest req) {
        recordFailureAudit("BAD_REQUEST", e, req);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "BAD_REQUEST", "message", safeMsg(e)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception e, HttpServletRequest req) {
        // Log stacktrace for on-call debugging; response remains stable JSON without leaking internals.
        log.error("Unhandled exception", e);
        recordFailureAudit("INTERNAL", e, req);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "INTERNAL", "message", ""));
    }

    private void recordFailureAudit(String error, Exception e, HttpServletRequest req) {
        if (req == null) {
            return;
        }
        String path = req.getRequestURI();
        String method = req.getMethod();
        String ip = EvalApiSecurityFilter.clientIp(req);

        String runId = firstGroup(RUN_ID_IN_PATH, path);
        String datasetId = firstGroup(DATASET_ID_IN_PATH, path);

        // 事件类型粗粒度即可：失败/拒绝的“证据链”重点在 method/path/ip 与 status/reason。
        String eventType = (path != null && path.startsWith("/api/v1/eval/datasets")) ? "DATASET_API_ERROR"
                : (path != null && path.startsWith("/api/v1/eval/runs")) ? "RUN_API_ERROR"
                : "API_ERROR";

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exception", e == null ? "" : e.getClass().getSimpleName());
        String msg = safeMsg(e);
        if (!msg.isBlank()) {
            detail.put("message", truncate(msg, 500));
        }

        // 约定：BAD_REQUEST / NOT_FOUND 视为 REJECTED；INTERNAL 视为 ERROR。
        String status = "INTERNAL".equals(error) ? "ERROR" : "REJECTED";

        audit.record(
                eventType,
                status,
                error,
                ip,
                method,
                path,
                runId,
                datasetId,
                null,
                detail
        );
    }

    private static String firstGroup(Pattern p, String s) {
        if (p == null || s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        if (!m.find()) {
            return null;
        }
        return m.groupCount() >= 1 ? m.group(1) : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return m == null ? "" : m;
    }
}

