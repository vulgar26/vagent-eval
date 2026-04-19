package com.vagent.eval.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
 *   default-eval-gateway-key: "" → {@link #defaultEvalGatewayKey}（与需 {@code X-Eval-Gateway-Key} 的被测对齐，如 travel-ai）
 *   targets:               → {@link #targets}
 *     - target-id: vagent → {@link TargetConfig#targetId}
 *       base-url: ...     → {@link TargetConfig#baseUrl}
 *       eval-token: ""    → {@link TargetConfig#getEvalToken}
 *       eval-gateway-key: "" → {@link TargetConfig#getEvalGatewayKey}
 *       enabled: true     → {@link TargetConfig#enabled}
 *       meta-trace-keys:  → {@link TargetConfig#getMetaTraceKeys}（compare / 探针按 target 选择 meta 子集）
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
     * P0+：执行器/保护性参数（限流、并发等）。默认值应“能跑通”但不打爆下游。
     */
    @Valid
    private Runner runner = new Runner();

    /**
     * P1：内存版/存储版统一的保留期配置（默认关闭自动清理；生产可开启）。
     */
    @Valid
    private Retention retention = new Retention();

    /**
     * 结果行扩展：上游 {@code meta} 落库策略（与 {@code eval.runner.*} 独立）。
     */
    @Valid
    private Persistence persistence = new Persistence();

    /**
     * 阶段五：调度扩展（Redis 队列/配额等）。连接细节用 {@code spring.data.redis.*}；此处仅存业务语义字段。
     */
    @Valid
    private Scheduler scheduler = new Scheduler();

    /**
     * P0+：调用被测 {@code POST /api/v1/eval/chat} 时写入 {@code X-Eval-Token} 的默认明文；
     * per-target 见 {@link TargetConfig#getEvalToken()}。明文勿提交仓库，用环境变量或本地覆盖文件注入。
     */
    private String defaultEvalToken = "";

    /**
     * 发往被测 {@code POST /api/v1/eval/chat} 时可选的 {@code X-Eval-Gateway-Key} 默认值；
     * per-target 见 {@link TargetConfig#getEvalGatewayKey()}。须与被测 {@code app.eval.gateway-key}（如 travel-ai 的
     * {@code APP_EVAL_GATEWAY_KEY}）一致；留空则不发送该头（适用于不要求网关头的被测）。
     */
    private String defaultEvalGatewayKey = "";

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

    public Runner getRunner() {
        return runner;
    }

    public void setRunner(Runner runner) {
        this.runner = runner == null ? new Runner() : runner;
    }

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention == null ? new Retention() : retention;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence == null ? new Persistence() : persistence;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler == null ? new Scheduler() : scheduler;
    }

    public String getDefaultEvalToken() {
        return defaultEvalToken;
    }

    public void setDefaultEvalToken(String defaultEvalToken) {
        this.defaultEvalToken = defaultEvalToken == null ? "" : defaultEvalToken;
    }

    public String getDefaultEvalGatewayKey() {
        return defaultEvalGatewayKey;
    }

    public void setDefaultEvalGatewayKey(String defaultEvalGatewayKey) {
        this.defaultEvalGatewayKey = defaultEvalGatewayKey == null ? "" : defaultEvalGatewayKey;
    }

    /**
     * 按 {@code eval.targets[].target-id} 解析「compare meta 摘要 / 探针可选 meta 字段」要关注的键名（snake_case，与上游 JSON 一致）。
     * 未匹配到 target 或列表为空时返回空列表。
     */
    public List<String> resolveMetaTraceKeys(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return List.of();
        }
        String want = targetId.trim().toLowerCase(Locale.ROOT);
        for (TargetConfig t : targets) {
            if (t.getTargetId() != null && t.getTargetId().trim().toLowerCase(Locale.ROOT).equals(want)) {
                return t.normalizedMetaTraceKeys();
            }
        }
        return List.of();
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

        /** 覆盖 {@link EvalProperties#defaultEvalGatewayKey}；仅当非空 trim 后使用。 */
        private String evalGatewayKey = "";

        /**
         * 从落库 {@code meta} 中按序拷贝到 compare 的 {@code *_meta_trace} 的键名；空表示不生成摘要（空 map）。
         * 各被测方（vagent / travel-ai 等）观测字段不同，在此按 target 配置白名单，避免在代码里写死某一家的 schema。
         */
        private List<String> metaTraceKeys = new ArrayList<>();

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

        public String getEvalGatewayKey() {
            return evalGatewayKey;
        }

        public void setEvalGatewayKey(String evalGatewayKey) {
            this.evalGatewayKey = evalGatewayKey == null ? "" : evalGatewayKey;
        }

        public List<String> getMetaTraceKeys() {
            return metaTraceKeys;
        }

        public void setMetaTraceKeys(List<String> metaTraceKeys) {
            this.metaTraceKeys = metaTraceKeys == null ? new ArrayList<>() : metaTraceKeys;
        }

        /**
         * 非空、trim 后的 meta 摘要键列表（不可变视图）。
         */
        public List<String> normalizedMetaTraceKeys() {
            if (metaTraceKeys == null || metaTraceKeys.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String s : metaTraceKeys) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
            return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
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

    public static class Runner {

        /**
         * eval 侧对下游 target 的全局并发保护（跨 run 生效）。P0 默认偏保守。
         */
        @Min(1)
        @Max(256)
        private int maxConcurrency = 8;

        /**
         * 获取并发许可的等待时间；超时则本次 case 直接返回 {@code RATE_LIMITED}（避免堆积导致雪崩）。
         */
        @Min(0)
        @Max(60_000)
        private int acquireTimeoutMs = 250;

        /**
         * 阶段四：每个 target 的 lane worker 数（当前实现默认 1）。
         * <p>
         * 说明：一个 lane 内的入队仍保持 FIFO，但 worker 数 > 1 会降低“严格 FIFO 完全顺序”的保证。
         * 本阶段默认 1，以保证结果顺序更稳定，便于验收与排障。
         */
        @Min(1)
        @Max(64)
        private int targetConcurrency = 1;

        /**
         * 阶段四：每个 target 的 lane 队列容量（runId 数量）。
         */
        @Min(1)
        @Max(10_000)
        private int targetQueueCapacity = 50;

        /**
         * 阶段四：入队等待时间；超时则拒绝调度并将 run 标为 CANCELLED。
         */
        @Min(0)
        @Max(60_000)
        private int enqueueTimeoutMs = 250;

        /**
         * 写入下游 eval/chat JSON body 的 {@code mode}（默认 {@code EVAL}）。
         * <p>
         * 仅用于联调/排障：可配置为 {@code EVAL_DEBUG} 以允许下游返回更强的 debug 字段（仍需下游自行遵守安全策略）。
         */
        private String chatMode = "EVAL";

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getAcquireTimeoutMs() {
            return acquireTimeoutMs;
        }

        public void setAcquireTimeoutMs(int acquireTimeoutMs) {
            this.acquireTimeoutMs = acquireTimeoutMs;
        }

        public int getTargetConcurrency() {
            return targetConcurrency;
        }

        public void setTargetConcurrency(int targetConcurrency) {
            this.targetConcurrency = targetConcurrency;
        }

        public int getTargetQueueCapacity() {
            return targetQueueCapacity;
        }

        public void setTargetQueueCapacity(int targetQueueCapacity) {
            this.targetQueueCapacity = targetQueueCapacity;
        }

        public int getEnqueueTimeoutMs() {
            return enqueueTimeoutMs;
        }

        public void setEnqueueTimeoutMs(int enqueueTimeoutMs) {
            this.enqueueTimeoutMs = enqueueTimeoutMs;
        }

        public String getChatMode() {
            return chatMode;
        }

        public void setChatMode(String chatMode) {
            this.chatMode = chatMode == null ? "EVAL" : chatMode.trim();
        }
    }

    /**
     * 上游 {@code meta} JSON 快照的持久化边界（YAML {@code eval.persistence.*}）。
     */
    public static class Persistence {

        /**
         * 默认 false：落库前移除 {@code meta.retrieval_hit_ids}（Vagent 仅在严格 DEBUG 边界下发明文）。
         * 为 true 且 {@link Runner#getChatMode()} 为 {@code EVAL_DEBUG} 时保留该键（内网联调）。
         */
        private boolean allowPlainRetrievalHitIdsInMeta = false;

        /**
         * 单条 {@code target_meta_json} 序列化后的最大字符数；超出则整段 meta 不落库（返回 null）。
         */
        @Min(1024)
        @Max(16_777_216)
        private int maxTargetMetaJsonChars = 262_144;

        public boolean isAllowPlainRetrievalHitIdsInMeta() {
            return allowPlainRetrievalHitIdsInMeta;
        }

        public void setAllowPlainRetrievalHitIdsInMeta(boolean allowPlainRetrievalHitIdsInMeta) {
            this.allowPlainRetrievalHitIdsInMeta = allowPlainRetrievalHitIdsInMeta;
        }

        public int getMaxTargetMetaJsonChars() {
            return maxTargetMetaJsonChars;
        }

        public void setMaxTargetMetaJsonChars(int maxTargetMetaJsonChars) {
            this.maxTargetMetaJsonChars = maxTargetMetaJsonChars;
        }
    }

    public static class Retention {

        /**
         * 是否启用自动清理（默认 false：避免本地开发误删证据）。
         */
        private boolean enabled = false;

        /**
         * 仅清理已 FINISHED/CANCELLED 的 run；保留天数从 finishedAt 计算。
         */
        @Min(1)
        @Max(365)
        private int days = 14;

        /**
         * 清理执行间隔（毫秒）：默认 24h。
         */
        @Min(10_000)
        @Max(7 * 24 * 60 * 60 * 1000)
        private long intervalMs = 24L * 60L * 60L * 1000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    /**
     * 阶段五：调度子配置树（YAML {@code eval.scheduler.*}）。
     */
    public static class Scheduler {

        @Valid
        private Redis redis = new Redis();

        public Redis getRedis() {
            return redis;
        }

        public void setRedis(Redis redis) {
            this.redis = redis == null ? new Redis() : redis;
        }

        /**
         * Redis 侧调度相关的<strong>业务字段</strong>；连接参数使用 Spring Boot 标准 {@code spring.data.redis.*}。
         */
        public static class Redis {

            /**
             * 是否启用「Redis 调度扩展」：5.1 起含连通性校验；5.2 起在存在 {@code StringRedisTemplate} 时由
             * {@link com.vagent.eval.scheduler.RedisRunQueueDispatcher} 接管跨进程 run 队列（默认 false）。
             */
            private boolean enabled = false;

            /**
             * 所有 eval 调度相关 Redis key 的统一前缀，用于与 vagent/travel 等业务 key 隔离。
             * <p>
             * 建议以 {@code :} 结尾，例如 {@code vagent:eval:}。
             */
            private String keyPrefix = "vagent:eval:";

            /**
             * Redis 不可连或 PING 失败时的策略。
             */
            private OnConnectFailure onConnectFailure = OnConnectFailure.LENIENT;

            /**
             * 阶段 5.2：消费者 {@code BLPOP} 阻塞超时（秒）；超时后循环重试，便于优雅停机与空转降 CPU。
             */
            @Min(1)
            @Max(120)
            private int brPopTimeoutSeconds = 5;

            /**
             * 5.3：集群范围内同一 target 最多同时执行多少个 run（所有 eval 实例合计）。
             * {@code 0} 表示不启用全局配额（默认）。
             */
            @Min(0)
            @Max(10_000)
            private int globalMaxConcurrentRunsPerTarget = 0;

            /**
             * 5.3：从 Redis 队列取出 run 后，等待获得全局配额的最长时间（毫秒）；超时则拒跑（与入队拒绝路径一致）。
             * 等待期间若配额已满会将 runId {@code LPUSH} 回队首并短睡后再次出队重试。
             */
            @Min(0)
            @Max(600_000)
            private int globalQuotaAcquireTimeoutMs = 30_000;

            /**
             * {@code LENIENT}：仅告警，不影响进程启动（默认，避免 Redis 抖动拖死评测服务）。<br>
             * {@code STRICT}：启动失败，用于生产硬门禁。
             */
            public enum OnConnectFailure {
                LENIENT,
                STRICT
            }

            public int getBrPopTimeoutSeconds() {
                return brPopTimeoutSeconds;
            }

            public void setBrPopTimeoutSeconds(int brPopTimeoutSeconds) {
                this.brPopTimeoutSeconds = brPopTimeoutSeconds;
            }

            public int getGlobalMaxConcurrentRunsPerTarget() {
                return globalMaxConcurrentRunsPerTarget;
            }

            public void setGlobalMaxConcurrentRunsPerTarget(int globalMaxConcurrentRunsPerTarget) {
                this.globalMaxConcurrentRunsPerTarget = globalMaxConcurrentRunsPerTarget;
            }

            public int getGlobalQuotaAcquireTimeoutMs() {
                return globalQuotaAcquireTimeoutMs;
            }

            public void setGlobalQuotaAcquireTimeoutMs(int globalQuotaAcquireTimeoutMs) {
                this.globalQuotaAcquireTimeoutMs = globalQuotaAcquireTimeoutMs;
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getKeyPrefix() {
                return keyPrefix;
            }

            public void setKeyPrefix(String keyPrefix) {
                this.keyPrefix = keyPrefix == null ? "" : keyPrefix.trim();
            }

            public OnConnectFailure getOnConnectFailure() {
                return onConnectFailure;
            }

            public void setOnConnectFailure(OnConnectFailure onConnectFailure) {
                this.onConnectFailure = onConnectFailure == null ? OnConnectFailure.LENIENT : onConnectFailure;
            }
        }
    }
}
