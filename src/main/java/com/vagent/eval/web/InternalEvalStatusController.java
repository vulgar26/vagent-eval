package com.vagent.eval.web;

import com.vagent.eval.config.EvalProperties;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运维/排障用的「最小可读状态」：证明 eval 进程已启动，且 {@link EvalProperties} 里的 target 列表已加载。
 * <p>
 * 与 {@code /actuator/health} 分工：后者是 Spring 通用存活探针；本接口额外返回<strong>业务配置视角</strong>（eval_api、targets 摘要）。
 * 生产环境应仅内网或管理员可达；Day9 起管理面见 {@code /api/v1/eval/**}（除 chat）的 Filter；本 {@code /internal/eval} 路径仍建议网络层收口。
 */
@RestController
@RequestMapping(path = "/internal/eval", produces = MediaType.APPLICATION_JSON_VALUE)
public class InternalEvalStatusController {

    private final EvalProperties evalProperties;
    private final HealthEndpoint healthEndpoint;

    public InternalEvalStatusController(EvalProperties evalProperties, HealthEndpoint healthEndpoint) {
        this.evalProperties = evalProperties;
        this.healthEndpoint = healthEndpoint;
    }

    /**
     * 返回体使用 snake_case 键名，与 P0 对外 JSON 契约一致，便于和未来的公开 API、报告字段统一，也方便 curl/jq 脚本。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        EvalProperties.Api api = evalProperties.getApi();
        body.put("eval_api_enabled", api.isEnabled());
        body.put("require_https", api.isRequireHttps());
        body.put("token_hash_configured", api.getTokenHash() != null && !api.getTokenHash().isBlank());
        body.put("targets", summarizeTargets(evalProperties.getTargets()));

        HealthComponent health = healthEndpoint.health();
        body.put("actuator_status", health.getStatus().getCode());
        return body;
    }

    private static List<Map<String, Object>> summarizeTargets(List<EvalProperties.TargetConfig> targets) {
        return targets.stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("target_id", t.getTargetId());
            row.put("enabled", t.isEnabled());
            row.put("base_url_configured", t.getBaseUrl() != null && !t.getBaseUrl().isBlank());
            row.put("base_url_origin", safeOrigin(t.getBaseUrl()));
            return row;
        }).collect(Collectors.toList());
    }

    /**
     * 把完整 {@code baseUrl} 压成「来源（origin）」：只保留 scheme + host + port，<strong>去掉 path 与 query</strong>。
     * <p>
     * <strong>人话</strong>：配置里有人可能写成 {@code https://gateway.internal/vagent/prod}，若原样返回给前端或打进日志，
     * 会多泄露一层内部路由结构；排障通常只需要知道连的是哪台主机、什么协议/端口。
     * <p>
     * 示例：{@code https://api.example.com:8443/v1/secret?x=1} → {@code https://api.example.com:8443}
     */
    /** public 便于跨包单测；生产仅本类调用。 */
    public static String safeOrigin(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            URI u = URI.create(baseUrl.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            String host = u.getHost() == null ? "" : u.getHost();
            int port = u.getPort();
            if (scheme.isEmpty() && host.isEmpty()) {
                return "";
            }
            if (port > 0) {
                return scheme + "://" + host + ":" + port;
            }
            return scheme + "://" + host;
        } catch (Exception ignored) {
            return "";
        }
    }
}
