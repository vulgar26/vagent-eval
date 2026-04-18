package com.vagent.eval.scheduler;

import com.vagent.eval.config.EvalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisGlobalRunQuotaKeyTest {

    @Test
    void activeCountKey_prefixAndSanitizedTarget() {
        EvalProperties p = new EvalProperties();
        p.getScheduler().getRedis().setKeyPrefix("pfx:");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisGlobalRunQuota q = new RedisGlobalRunQuota(redis, p);
        assertThat(q.activeCountKey("X/Y")).isEqualTo("pfx:quota:run:active:x_y");
    }
}
