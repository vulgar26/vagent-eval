package com.vagent.eval.security;

import com.vagent.eval.run.RunModel.EvalResult;

import java.util.List;
import java.util.Map;

/**
 * Day9：检查序列化前的响应体是否违反 EVAL_DEBUG 边界（未授权却携带敏感 debug 键）。
 */
public final class EvalDebugExposureChecker {

    private EvalDebugExposureChecker() {
    }

    /**
     * @param body               控制器返回值（多为 Map）
     * @param evalDebugHeaderRaw {@link EvalSecurityConstants#HDR_EVAL_DEBUG} 原始头值
     * @return true 表示应拒绝响应（SECURITY_BOUNDARY_VIOLATION）
     */
    public static boolean violates(Object body, String evalDebugHeaderRaw) {
        if (evalDebugAllowed(evalDebugHeaderRaw)) {
            return false;
        }
        if (!(body instanceof Map<?, ?> map)) {
            return false;
        }
        Object results = map.get("results");
        if (results instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof EvalResult er && debugHasForbiddenKeys(er.debug())) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean evalDebugAllowed(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    static boolean debugHasForbiddenKeys(Map<String, Object> debug) {
        if (debug == null || debug.isEmpty()) {
            return false;
        }
        for (String k : EvalSecurityConstants.FORBIDDEN_DEBUG_KEYS_WITHOUT_EVAL_DEBUG) {
            if (debug.containsKey(k)) {
                return true;
            }
        }
        return false;
    }
}
