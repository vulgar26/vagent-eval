package com.vagent.eval.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段五：{@link EvalProperties.Scheduler.Redis} 默认值与嵌套绑定占位单测。
 */
class EvalPropertiesSchedulerRedisTest {

    @Test
    void schedulerRedis_defaults() {
        EvalProperties p = new EvalProperties();
        assertThat(p.getScheduler().getRedis().isEnabled()).isFalse();
        assertThat(p.getScheduler().getRedis().getKeyPrefix()).isEqualTo("vagent:eval:");
        assertThat(p.getScheduler().getRedis().getOnConnectFailure())
                .isEqualTo(EvalProperties.Scheduler.Redis.OnConnectFailure.LENIENT);
    }
}
