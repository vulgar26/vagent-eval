package com.vagent.eval.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 *     allow-cidrs: [...]  → {@link Api#getAllowCidrs}
 *     require-https: ...  → {@link Api#requireHttps}
 *   default-eval-token: "" → {@link #defaultEvalToken}
 *   targets:               → {@link #targets}
 *     - target-id: vagent → {@link TargetConfig#targetId}
 *       base-url: ...     → {@link TargetConfig#baseUrl}
 *       eval-token: ""    → {@link TargetConfig#getEvalToken}
 *       enabled: true     → {@link TargetConfig#enabled}
 *   membership:            → {@link #membership}
 *     salt: ...           → {@link Membership#salt}
 *     top-n: 8           → {@link Membership#topN}
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

    /**
     * Day6：引用 membership 判定参数（盐、候选集前 N 条）。
     * <p>
     * YAML 键示例：{@code eval.membership.salt}、{@code eval.membership.top-n}。
     */
    @Valid
    private Membership membership = new Membership();

    /**
     * P0+：调用被测 {@code POST /api/v1/eval/chat} 时写入 {@code X-Eval-Token} 的默认明文；
     * per-target 见 {@link TargetConfig#getEvalToken()}。明文勿提交仓库，用环境变量或本地覆盖文件注入。
     */
    private String defaultEvalToken = "";

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

    public Membership getMembership() {
        return membership;
    }

    public void setMembership(Membership membership) {
        this.membership = membership == null ? new Membership() : membership;
    }

    public String getDefaultEvalToken() {
        return defaultEvalToken;
    }

    public void setDefaultEvalToken(String defaultEvalToken) {
        this.defaultEvalToken = defaultEvalToken == null ? "" : defaultEvalToken;
    }

    public static class Api {

        /** 默认 false：与 eval-upgrade 建议一致，避免误暴露评测入口。 */
        private boolean enabled = false;

        /** 仅存 hash，禁止明文 token；Day1 仅占位，Filter 校验在后续里程碑接入。 */
        private String tokenHash = "";

        /**
         * Day9：允许调用评测管理 API 的客户端网段（CIDR）；<strong>空列表</strong>表示不启用 IP 限制（仅依赖 token 等）。
         * YAML：{@code allow-cidrs: [ "10.0.0.0/8", "127.0.0.1/32" ]}
         */
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
            this.allowCidrs = allowCidrs == null ? new ArrayList<>() : allowCidrs;
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

        /** 覆盖 {@link EvalProperties#defaultEvalToken}；仅当非空时使用。 */
        private String evalToken = "";

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

        public String getEvalToken() {
            return evalToken;
        }

        public void setEvalToken(String evalToken) {
            this.evalToken = evalToken == null ? "" : evalToken;
        }
    }

    /**
     * Day6：hashed membership 的配置块。
     * <p>
     * {@link #salt} 会通过 {@code X-Eval-Membership-Salt} 随请求传递，使被测侧构造的
     * {@code retrieval_hits} 与评测侧 {@link com.vagent.eval.run.CitationMembership} 使用同一派生规则。
     * {@link #topN} 表示：对响应中 {@code retrieval_hits} 数组<strong>按检索排序后的前 N 个元素</strong>
     * 视为合法候选；超出部分不参与 membership（与 SSOT「前 N 口径」对齐时可在此调 N）。
     */
    public static class Membership {

        /**
         * 参与 SHA-256 输入串的盐；生产应使用足够熵的密钥化材料，且勿写入仓库明文。
         */
        private String salt = "";

        /**
         * 候选集截断上界：仅取 {@code retrieval_hits} 前 {@code topN} 条参与集合构造。
         */
        @Min(1)
        @Max(256)
        private int topN = 8;

        public String getSalt() {
            return salt;
        }

        public void setSalt(String salt) {
            this.salt = salt == null ? "" : salt;
        }

        public int getTopN() {
            return topN;
        }

        public void setTopN(int topN) {
            this.topN = topN;
        }
    }
}
