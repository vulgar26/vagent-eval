package com.vagent.eval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 绑定 {@code application.yml}（或环境变量）里以 {@code eval} 为根节点的配置。
 * <p>
 * 映射关系示例（YAML 用 kebab-case，Java 用驼峰，Spring 自动转换）：
 * <pre>
 * eval:                    → 本类
 *   api:                   → {@link #api}
 *     enabled: false       → {@link Api#enabled}
 *     token-hash: ""      → {@link Api#tokenHash}
 *     allow-cidrs: [...]  → {@link Api#allowCidrs}
 *     require-https: ...  → {@link Api#requireHttps}
 *   targets:               → {@link #targets}
 *     - target-id: vagent → {@link TargetConfig#targetId}
 *       base-url: ...     → {@link TargetConfig#baseUrl}
 *       enabled: true     → {@link TargetConfig#enabled}
 * </pre>
 * 与 SSOT 对齐：发起方侧前缀为 {@code eval.api.*}；多 target 的基址列表即 {@code eval.targets}。
 * 注意：对外 HTTP JSON（API 响应、dataset）必须用 snake_case；这里是<strong>内部配置对象</strong>，用 Java 驼峰即可。
 */
@Validated
@ConfigurationProperties(prefix = "eval")
public class EvalProperties {

    /**
     * eval <strong>作为服务端</strong>对外提供评测管理 API 时的开关与安全相关项（文档中的 {@code eval.api.*}）。
     * 与业务被测进程上的 {@code vagent.eval.api.*} / {@code travelai.eval.api.*} 语义对应，但配置键前缀不同。
     */
    private Api api = new Api();

    /**
     * 被测系统（考生）列表。Day1 仅读取并展示；后续 Runner 将向 {@code baseUrl + "/api/v1/eval/chat"} 发 POST（由 Adapter 拼接）。
     */
    private List<TargetConfig> targets = new ArrayList<>();

    public Api getApi() {
        return api;
    }

    public void setApi(Api api) {
        this.api = api;
    }

    public List<TargetConfig> getTargets() {
        return targets;
    }

    public void setTargets(List<TargetConfig> targets) {
        this.targets = targets;
    }

    public static class Api {

        /** 默认 false：与 eval-upgrade 建议一致，避免误暴露评测入口。 */
        private boolean enabled = false;

        /** 仅存 hash，禁止明文 token；Day1 仅占位，Filter 校验在后续里程碑接入。 */
        private String tokenHash = "";

        private List<String> allowCidrs = new ArrayList<>();

        private boolean requireHttps = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTokenHash() {
            return tokenHash;
        }

        public void setTokenHash(String tokenHash) {
            this.tokenHash = tokenHash;
        }

        public List<String> getAllowCidrs() {
            return allowCidrs;
        }

        public void setAllowCidrs(List<String> allowCidrs) {
            this.allowCidrs = allowCidrs;
        }

        public boolean isRequireHttps() {
            return requireHttps;
        }

        public void setRequireHttps(boolean requireHttps) {
            this.requireHttps = requireHttps;
        }
    }

    public static class TargetConfig {

        /** 与 Header {@code X-Eval-Target-Id} 及 hashed membership 派生中的 targetId 对齐。 */
        private String targetId;

        /** 被测服务根 URL，不含尾斜杠，例如 https://vagent.internal:8443 */
        private String baseUrl;

        private boolean enabled = true;

        public String getTargetId() {
            return targetId;
        }

        public void setTargetId(String targetId) {
            this.targetId = targetId;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
