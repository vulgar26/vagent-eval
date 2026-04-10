package com.vagent.eval.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Day4 本地探针：在无双 target 时模拟 {@code POST /api/v1/eval/chat}，用于验收契约解析与 {@code CONTRACT_VIOLATION}。
 * <p>
 * <strong>默认关闭</strong>：仅当 {@code eval.probe.enabled=true} 时注册，避免与真实部署误暴露。
 * <p>
 * 约定：请求 body 中 {@code query} 包含子串 {@code BAD_CONTRACT} 时返回<strong>缺字段</strong>的 JSON（缺 {@code latency_ms}），
 * 其余情况返回符合附录 C2 的最小合法响应。
 */
@RestController
@ConditionalOnProperty(prefix = "eval.probe", name = "enabled", havingValue = "true")
public class EvalProbeChatController {

    @PostMapping(path = "/api/v1/eval/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> evalChat(@RequestBody Map<String, Object> body) {
        Object q = body == null ? null : body.get("query");
        String query = q == null ? "" : String.valueOf(q);
        if (query.contains("BAD_CONTRACT")) {
            Map<String, Object> caps = new LinkedHashMap<>();
            caps.put("retrieval", Map.of("supported", false, "score", false));
            caps.put("tools", Map.of("supported", false, "outcome", false));
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("answer", "incomplete");
            bad.put("behavior", "answer");
            // 故意缺少 latency_ms → CONTRACT_VIOLATION
            bad.put("capabilities", caps);
            bad.put("meta", Map.of("mode", "EVAL"));
            return bad;
        }

        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("retrieval", Map.of("supported", false, "score", false));
        caps.put("tools", Map.of("supported", false, "outcome", false));
        caps.put("streaming", Map.of("ttft", false));
        caps.put("guardrails", Map.of("quoteOnly", false, "evidenceMap", false, "reflection", false));

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("answer", "probe ok");
        ok.put("behavior", "answer");
        ok.put("latency_ms", 1);
        ok.put("capabilities", caps);
        ok.put("meta", Map.of("mode", "EVAL"));
        return ok;
    }
}
