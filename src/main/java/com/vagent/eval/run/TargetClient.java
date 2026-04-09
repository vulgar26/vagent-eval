package com.vagent.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.config.EvalProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Day3：向被测 target 调用 {@code POST /api/v1/eval/chat} 的最小客户端。
 * <p>
 * P0 只要求能跑通串行执行与错误归因，因此这里只实现：
 * - 拼接 baseUrl + 固定路径
 * - 传递必要的 X-Eval-* 头（为后续 hashed membership 校验做准备）
 * - 解析 JSON（JsonNode）用于最小判定
 */
@Component
public class TargetClient {

    private static final String EVAL_CHAT_PATH = "/api/v1/eval/chat";

    private final EvalProperties evalProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TargetClient(EvalProperties evalProperties, ObjectMapper objectMapper) {
        this.evalProperties = evalProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public record TargetResponse(int statusCode, JsonNode json, long latencyMs) {
    }

    public TargetResponse postEvalChat(String targetId, String baseUrl, String runId, String datasetId, String caseId, String query) throws Exception {
        long t0 = System.nanoTime();

        // P0：token 还未接入（Day4/Day5 接安全边界）。先按 SSOT 预留头位。
        String token = ""; // intentionally empty

        String body = objectMapper.writeValueAsString(new RequestBody(query, "EVAL", "conv_" + runId + "_" + caseId));
        URI uri = URI.create(baseUrl + EVAL_CHAT_PATH);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-Eval-Token", token)
                .header("X-Eval-Run-Id", runId)
                .header("X-Eval-Dataset-Id", datasetId)
                .header("X-Eval-Case-Id", caseId)
                .header("X-Eval-Target-Id", targetId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

        JsonNode json = null;
        try {
            json = objectMapper.readTree(resp.body());
        } catch (Exception ignored) {
        }
        return new TargetResponse(resp.statusCode(), json, latencyMs);
    }

    public Optional<EvalProperties.TargetConfig> findTarget(String targetId) {
        if (targetId == null) return Optional.empty();
        String key = targetId.trim().toLowerCase(Locale.ROOT);
        return evalProperties.getTargets().stream()
                .filter(t -> t.getTargetId() != null && t.getTargetId().trim().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
    }

    private record RequestBody(String query, String mode, String conversationId) {
    }
}

