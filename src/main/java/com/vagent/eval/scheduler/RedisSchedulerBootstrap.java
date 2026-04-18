package com.vagent.eval.scheduler;

import com.vagent.eval.config.EvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * 阶段五 5.1：在打开 {@code eval.scheduler.redis.enabled} 时，对 Redis 做一次启动期连通性校验（{@code PING}）。
 * <p>
 * 连接参数（host/port/password/SSL 等）统一走 Spring Boot 的 {@code spring.data.redis.*} 自动配置；
 * 本类只消费 {@link EvalProperties#getScheduler()} 里的<strong>业务语义字段</strong>（开关、key 前缀、失败策略）。
 * <p>
 * 阶段 5.2：当本开关为 true 且存在 {@link org.springframework.data.redis.core.StringRedisTemplate} 时，由 {@link RedisRunQueueDispatcher} 将 run 写入 Redis 列表并由 worker 消费；
 * 否则仍由内存 {@link com.vagent.eval.run.TargetRunScheduler} 负责入队与执行（与 5.1 前一致）。
 */
@Component
@ConditionalOnProperty(prefix = "eval.scheduler.redis", name = "enabled", havingValue = "true")
public class RedisSchedulerBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedisSchedulerBootstrap.class);

    private final EvalProperties evalProperties;
    private final ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;

    public RedisSchedulerBootstrap(
            EvalProperties evalProperties,
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider
    ) {
        this.evalProperties = evalProperties;
        this.connectionFactoryProvider = connectionFactoryProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        EvalProperties.Scheduler.Redis cfg = evalProperties.getScheduler().getRedis();
        String keyPrefix = cfg.getKeyPrefix() == null ? "" : cfg.getKeyPrefix().trim();
        if (keyPrefix.isEmpty()) {
            throw new IllegalStateException(
                    "eval.scheduler.redis.enabled=true 但 eval.scheduler.redis.key-prefix 为空；"
                            + "请设置非空前缀以避免与 vagent/travel 等业务 Redis key 冲突"
            );
        }

        RedisConnectionFactory factory = connectionFactoryProvider.getIfAvailable();
        if (factory == null) {
            String msg = "eval.scheduler.redis.enabled=true 但未发现 RedisConnectionFactory；"
                    + "请配置 spring.data.redis.host（或 spring.data.redis.url）等标准项以启用 Lettuce 自动配置";
            if (cfg.getOnConnectFailure() == EvalProperties.Scheduler.Redis.OnConnectFailure.STRICT) {
                throw new IllegalStateException(msg);
            }
            log.warn("eval_event=redis_scheduler_skip reason=no_connection_factory detail={}", msg);
            return;
        }

        try (RedisConnection conn = factory.getConnection()) {
            String pong = conn.ping();
            log.info(
                    "eval_event=redis_scheduler_ping_ok key_prefix={} pong={}",
                    keyPrefix,
                    pong
            );
        } catch (Exception e) {
            String detail = e.getClass().getSimpleName() + ":" + (e.getMessage() == null ? "" : e.getMessage());
            if (cfg.getOnConnectFailure() == EvalProperties.Scheduler.Redis.OnConnectFailure.STRICT) {
                throw new IllegalStateException("Redis PING 失败（eval.scheduler.redis.on-connect-failure=strict）: " + detail, e);
            }
            log.warn("eval_event=redis_scheduler_ping_failed key_prefix={} detail={}", keyPrefix, detail);
        }
    }
}
