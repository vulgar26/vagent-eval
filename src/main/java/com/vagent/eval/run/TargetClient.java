package com.vagent.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Day3/Day6：向被测 target 调用 {@code POST /api/v1/eval/chat} 的 HTTP 客户端。
 * <p>
 * <strong>职责边界</strong>：只负责构造 URL、设置头与 JSON body、发送请求、把状态码与解析后的 {@link JsonNode}
 * 交给 {@link RunRunner}；<strong>不做</strong>业务判定（判定在 {@link RunEvaluator}）。
 * <p>
 * <strong>Day6 补充</strong>：除原有的 {@code X-Eval-Run-Id} 等头外，增加 {@code X-Eval-Membership-Salt}、
 * {@code X-Eval-Membership-Top-N}，使被测侧（或本地 {@link com.vagent.eval.web.EvalProbeChatController}）
 * 能按与 {@link CitationMembership} 一致的盐与「前 N」口径构造 {@code retrieval_hits}。
 * <strong>P0+</strong>：{@code X-Eval-Token} 来自 {@link EvalProperties#getDefaultEvalToken()} 与
 * {@link EvalProperties.TargetConfig#getEvalToken()}（见 {@link #resolveEvalToken}），供被测填充 {@code meta.retrieval_hit_id_hashes} 等观测路径。
 */
@Component
public class TargetClient {

    private static final String EVAL_CHAT_PATH = "/api/v1/eval/chat";

    /** HTTP 头：把配置的 membership salt传给上游（勿在日志中打印完整值）。 */
    public static final String HDR_MEMBERSHIP_SALT = "X-Eval-Membership-Salt";

    /** HTTP 头：把「候选集前 N」的 N 传给上游，便于探针/适配器对齐评测配置。 */
    public static final String HDR_MEMBERSHIP_TOP_N = "X-Eval-Membership-Top-N";

    private final EvalProperties evalProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * @param evalProperties 读取 targets 与 {@code eval.membership.*}
     * @param objectMapper   与 Spring MVC 一致的 JSON 序列化（请求体）
     */
    public TargetClient(EvalProperties evalProperties, ObjectMapper objectMapper) {
        this.evalProperties = evalProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * 一次 eval/chat 调用的结构化结果：HTTP 状态、解析后的 JSON（可能为 null）、客户端观测耗时。
     */
    public record TargetResponse(int statusCode, JsonNode json, long latencyMs) {
    }

    /**
     * 向指定 target 根地址发起 {@code POST /api/v1/eval/chat}。
     * <p>
     * 关键步骤：1) 组装 body（query/mode/conversation_id）；2) 设置 eval 相关头（含 Day6 membership）；
     * 3) {@link HttpClient#send}；4) 尝试 parse body 为 JSON。
     *
     * @param targetId  写入 {@code X-Eval-Target-Id}，且参与 membership 哈希
     * @param baseUrl     target 根 URL（来自配置）
     * @param runId       当前 run
     * @param datasetId   数据集 id
     * @param caseId      题号
     * @param query       题干/提示词原文
     * @return 非 2xx 时 json 仍可能解析成功，由 {@link RunRunner} 分支处理
     */
    public TargetResponse postEvalChat(String targetId, String baseUrl, String runId, String datasetId, String caseId, String query) throws Exception {
        long t0 = System.nanoTime();

        String token = resolveEvalToken(targetId);

        // 使用 ObjectNode 显式写字段名，避免与全局 Jackson SNAKE_CASE 对 Java record 的序列化细节耦合导致下游解析异常。
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("query", query == null ? "" : query);
        payload.put("mode", "EVAL");
        payload.put("conversation_id", "conv_" + runId + "_" + caseId);
        String body = objectMapper.writeValueAsString(payload);

        URI uri = toEvalChatUri(baseUrl);

        String salt = evalProperties.getMembership().getSalt() == null ? "" : evalProperties.getMembership().getSalt();
        int topN = evalProperties.getMembership().getTopN();

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-Eval-Token", token)
                .header("X-Eval-Run-Id", runId)
                .header("X-Eval-Dataset-Id", datasetId)
                .header("X-Eval-Case-Id", caseId)
                .header("X-Eval-Target-Id", targetId)
                .header(HDR_MEMBERSHIP_SALT, salt)
                .header(HDR_MEMBERSHIP_TOP_N, Integer.toString(topN))
                .POST(HttpRequest.BodyPublishers.ofString(body));

        HttpRequest req = rb.build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

        JsonNode json = null;
        try {
            json = objectMapper.readTree(resp.body());
        } catch (Exception ignored) {
        }
        return new TargetResponse(resp.statusCode(), json, latencyMs);
    }

    /**
     * 解析发往被测端的 {@code X-Eval-Token}：优先匹配 {@code eval.targets[].eval-token}，否则
     * {@link EvalProperties#getDefaultEvalToken()}。与 Day6 membership 头独立。
     */
    String resolveEvalToken(String targetId) {
        if (targetId != null) {
            Optional<EvalProperties.TargetConfig> opt = findTarget(targetId);
            if (opt.isPresent()) {
                String t = opt.get().getEvalToken();
                if (t != null && !t.isBlank()) {
                    return t.trim();
                }
            }
        }
        String d = evalProperties.getDefaultEvalToken();
        return d == null ? "" : d.trim();
    }

    /**
     * 在配置列表中按 targetId（大小写不敏感）查找启用的 target 配置。
     *
     * @param targetId 请求中的 target 标识
     * @return 匹配的首条配置
     */
    public Optional<EvalProperties.TargetConfig> findTarget(String targetId) {
        if (targetId == null) {
            return Optional.empty();
        }
        String key = targetId.trim().toLowerCase(Locale.ROOT);
        return evalProperties.getTargets().stream()
                .filter(t -> t.getTargetId() != null && t.getTargetId().trim().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
    }

    /**
     * 将配置中的 baseUrl 规范化为合法 HTTP(S) URI，并解析出 eval/chat 的绝对地址。
     * <p>
     * 使用 {@link URI#resolve(String)} 避免手写拼接产生非法 URI 或双斜杠歧义。
     *
     * @param baseUrl 非空、trim 后的服务根
     * @return 绝对 URI
     */
    static URI toEvalChatUri(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is blank");
        }
        String b = baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String path = EVAL_CHAT_PATH.startsWith("/") ? EVAL_CHAT_PATH.substring(1) : EVAL_CHAT_PATH;
        URI base = URI.create(b);
        return base.resolve(path);
    }
}
