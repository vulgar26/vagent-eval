package com.vagent.eval.scheduler;

import com.vagent.eval.config.EvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 阶段五 5.3：跨 eval 实例的「每 target 全局并发 run 数」上限（Redis 计数 + Lua 原子占坑/释放）。
 * <p>
 * 仅在 {@code eval.scheduler.redis.enabled=true} 且存在 {@link StringRedisTemplate} 时注册；是否生效还取决于
 * {@code eval.scheduler.redis.global-max-concurrent-runs-per-target} &gt; 0。
 */
@Component
@ConditionalOnProperty(prefix = "eval.scheduler.redis", name = "enabled", havingValue = "true")
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisGlobalRunQuota {

    private static final Logger log = LoggerFactory.getLogger(RedisGlobalRunQuota.class);

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>();

    static {
        ACQUIRE.setScriptText(
                "local cap = tonumber(ARGV[1])\n"
                        + "local cur = tonumber(redis.call('GET', KEYS[1]) or '0')\n"
                        + "if cur >= cap then return 0 end\n"
                        + "redis.call('INCR', KEYS[1])\n"
                        + "return 1\n"
        );
        ACQUIRE.setResultType(Long.class);
        RELEASE.setScriptText(
                "local cur = tonumber(redis.call('GET', KEYS[1]) or '0')\n"
                        + "if cur <= 0 then return 0 end\n"
                        + "return redis.call('DECR', KEYS[1])\n"
        );
        RELEASE.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final EvalProperties evalProperties;

    public RedisGlobalRunQuota(StringRedisTemplate redis, EvalProperties evalProperties) {
        this.redis = redis;
        this.evalProperties = evalProperties;
    }

    String activeCountKey(String normalizedTargetId) {
        String prefix = evalProperties.getScheduler().getRedis().getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "vagent:eval:";
        }
        if (!prefix.endsWith(":")) {
            prefix = prefix + ":";
        }
        return prefix + "quota:run:active:" + RedisRunQueueDispatcher.sanitizeTargetIdForRedisKey(normalizedTargetId);
    }

    /**
     * @return true 已占用一格；false 当前已满或 Redis 异常（由调用方决定是否重试/拒跑）
     */
    public boolean tryAcquire(String targetId) {
        int cap = evalProperties.getScheduler().getRedis().getGlobalMaxConcurrentRunsPerTarget();
        if (cap <= 0) {
            return true;
        }
        String key = activeCountKey(targetId);
        try {
            Long ok = redis.execute(ACQUIRE, Collections.singletonList(key), String.valueOf(cap));
            return ok != null && ok == 1L;
        } catch (DataAccessException e) {
            log.warn("eval_event=redis_global_quota_acquire_error target_id={} key={}", targetId, key, e);
            return false;
        }
    }

    public void release(String targetId) {
        int cap = evalProperties.getScheduler().getRedis().getGlobalMaxConcurrentRunsPerTarget();
        if (cap <= 0) {
            return;
        }
        String key = activeCountKey(targetId);
        try {
            redis.execute(RELEASE, Collections.singletonList(key));
        } catch (DataAccessException e) {
            log.warn("eval_event=redis_global_quota_release_error target_id={} key={}", targetId, key, e);
        }
    }

}
