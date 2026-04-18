package com.vagent.eval.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisRunQueueDispatcherKeyTest {

    @Test
    void sanitizeTargetIdForRedisKey_normalizesUnsafeChars() {
        assertThat(RedisRunQueueDispatcher.sanitizeTargetIdForRedisKey("VAgent")).isEqualTo("vagent");
        assertThat(RedisRunQueueDispatcher.sanitizeTargetIdForRedisKey("travel/ai")).isEqualTo("travel_ai");
        assertThat(RedisRunQueueDispatcher.sanitizeTargetIdForRedisKey("")).isEqualTo("_");
    }
}
