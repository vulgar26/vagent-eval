package com.vagent.eval.scheduler;

import com.vagent.eval.config.EvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 5.3：若配置了全局每 target 并发上限但未启用 Redis 调度队列，则启动时打 WARN 并忽略该上限（不阻断启动）。
 */
@Component
@Order(Integer.MAX_VALUE - 20)
public class GlobalRunQuotaConfigurationWarning implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GlobalRunQuotaConfigurationWarning.class);

    private final EvalProperties evalProperties;
    private final RedisRunQueueDispatcher redisRunQueueDispatcher;

    public GlobalRunQuotaConfigurationWarning(
            EvalProperties evalProperties,
            @Autowired(required = false) RedisRunQueueDispatcher redisRunQueueDispatcher
    ) {
        this.evalProperties = evalProperties;
        this.redisRunQueueDispatcher = redisRunQueueDispatcher;
    }

    @Override
    public void run(ApplicationArguments args) {
        EvalProperties.Scheduler.Redis r = evalProperties.getScheduler().getRedis();
        if (r.getGlobalMaxConcurrentRunsPerTarget() <= 0) {
            return;
        }
        boolean redisQueueActive = r.isEnabled() && redisRunQueueDispatcher != null;
        if (!redisQueueActive) {
            log.warn(
                    "eval_event=global_per_target_run_quota_ignored global_max_concurrent_runs_per_target={} "
                            + "reason=redis_scheduler_queue_inactive "
                            + "hint=enable_eval.scheduler.redis_and_spring_data_redis_or_set_global_max_to_zero",
                    r.getGlobalMaxConcurrentRunsPerTarget()
            );
        }
    }
}
