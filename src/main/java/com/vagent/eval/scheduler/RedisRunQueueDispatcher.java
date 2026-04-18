package com.vagent.eval.scheduler;

import com.vagent.eval.audit.EvalAuditService;
import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.observability.EvalMetrics;
import com.vagent.eval.run.RunRunner;
import com.vagent.eval.run.RunStore;
import com.vagent.eval.run.ScheduleRejectSupport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阶段五 5.2：将待执行 run 写入 Redis 列表，由本机（及多实例）上的 worker 阻塞消费并调用 {@link RunRunner#execute(String)}。
 * <p>
 * 语义对齐内存 {@link com.vagent.eval.run.TargetRunScheduler}：
 * <ul>
 *   <li>每 target 一条逻辑队列：key = {@code keyPrefix + "schedule:queue:" + normalizedTargetId}</li>
 *   <li>FIFO：生产端 {@code RPUSH}，消费端阻塞 {@code leftPop(timeout)}（等价 BLPOP）</li>
 *   <li>深度上限：与 {@code eval.runner.target-queue-capacity} 一致，满则按入队超时等待或拒绝</li>
 *   <li>worker 数：与 {@code eval.runner.target-concurrency} 一致（每实例每 target）</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "eval.scheduler.redis", name = "enabled", havingValue = "true")
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisRunQueueDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RedisRunQueueDispatcher.class);

    private final StringRedisTemplate redis;
    private final EvalProperties evalProperties;
    private final RunRunner runRunner;
    private final RunStore runStore;
    private final EvalMetrics evalMetrics;
    private final EvalAuditService audit;
    private final RedisGlobalRunQuota globalRunQuota;

    private final Map<String, RedisLane> lanes = new ConcurrentHashMap<>();

    public RedisRunQueueDispatcher(
            StringRedisTemplate redis,
            EvalProperties evalProperties,
            RunRunner runRunner,
            RunStore runStore,
            EvalMetrics evalMetrics,
            EvalAuditService audit,
            RedisGlobalRunQuota globalRunQuota
    ) {
        this.redis = redis;
        this.evalProperties = evalProperties;
        this.runRunner = runRunner;
        this.runStore = runStore;
        this.evalMetrics = evalMetrics;
        this.audit = audit;
        this.globalRunQuota = globalRunQuota;
    }

    /**
     * 将 runId 写入对应 target 的 Redis 队列；必要时启动该 target 的 worker 池。
     */
    public void enqueue(String targetId, String runId, int queueCapacity, int enqueueTimeoutMs) {
        String tid = nz(targetId);
        String rid = nz(runId);
        String key = queueKey(tid);
        long t0 = System.nanoTime();

        long deadline = System.nanoTime() + Math.max(0L, enqueueTimeoutMs) * 1_000_000L;
        while (true) {
            Long size;
            try {
                size = redis.opsForList().size(key);
            } catch (DataAccessException e) {
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                log.error("eval_event=redis_enqueue_failed target_id={} run_id={} reason=redis_error", tid, rid, e);
                reject(tid, rid, "REDIS_ERROR", elapsedMs, 0, queueCapacity, enqueueTimeoutMs);
                return;
            }
            long len = size == null ? 0L : size;
            if (len < queueCapacity) {
                try {
                    redis.opsForList().rightPush(key, rid);
                } catch (DataAccessException e) {
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                    log.error("eval_event=redis_enqueue_failed target_id={} run_id={} reason=redis_rpush_error", tid, rid, e);
                    reject(tid, rid, "REDIS_ERROR", elapsedMs, (int) Math.min(len, Integer.MAX_VALUE), queueCapacity, enqueueTimeoutMs);
                    return;
                }
                log.info(
                        "eval_event=run_enqueued_redis target_id={} run_id={} redis_key_suffix=schedule:queue:{} queue_depth={} queue_capacity={}",
                        tid,
                        rid,
                        tid,
                        len + 1,
                        queueCapacity
                );
                ensureConsumers(tid);
                return;
            }
            if (enqueueTimeoutMs <= 0 || System.nanoTime() >= deadline) {
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                String reason = enqueueTimeoutMs <= 0 ? "QUEUE_FULL"
                        : (elapsedMs >= (enqueueTimeoutMs * 0.9) ? "ENQUEUE_TIMEOUT" : "QUEUE_FULL");
                reject(tid, rid, reason, elapsedMs, (int) Math.min(len, Integer.MAX_VALUE), queueCapacity, enqueueTimeoutMs);
                return;
            }
            try {
                Thread.sleep(Math.min(50L, Math.max(1L, enqueueTimeoutMs / 10L)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                reject(tid, rid, "ENQUEUE_INTERRUPTED", elapsedMs, (int) Math.min(len, Integer.MAX_VALUE), queueCapacity, enqueueTimeoutMs);
                return;
            }
        }
    }

    private void reject(
            String tid,
            String rid,
            String reason,
            long elapsedMs,
            int queueDepth,
            int queueCapacity,
            int enqueueTimeoutMs
    ) {
        ScheduleRejectSupport.rejectRunSchedule(
                log,
                tid,
                rid,
                reason,
                elapsedMs,
                queueDepth,
                queueCapacity,
                enqueueTimeoutMs,
                runStore,
                evalMetrics,
                audit
        );
    }

    private void ensureConsumers(String targetId) {
        int targetConcurrency = Math.max(1, evalProperties.getRunner().getTargetConcurrency());
        lanes.computeIfAbsent(targetId, k -> new RedisLane(k, targetConcurrency, queueKey(k))).ensureStarted();
    }

    String queueKey(String normalizedTargetId) {
        String prefix = evalProperties.getScheduler().getRedis().getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "vagent:eval:";
        }
        if (!prefix.endsWith(":")) {
            prefix = prefix + ":";
        }
        return prefix + "schedule:queue:" + sanitizeTargetIdForRedisKey(normalizedTargetId);
    }

    static String sanitizeTargetIdForRedisKey(String targetId) {
        String s = nz(targetId).toLowerCase();
        if (s.isEmpty()) {
            return "_";
        }
        StringBuilder sb = new StringBuilder(Math.min(s.length(), 80));
        for (int i = 0; i < s.length() && sb.length() < 80; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private final class RedisLane {
        final String targetId;
        final int concurrency;
        final String redisKey;
        final ExecutorService workers;
        final AtomicBoolean started = new AtomicBoolean(false);

        RedisLane(String targetId, int concurrency, String redisKey) {
            this.targetId = Objects.requireNonNull(targetId);
            this.concurrency = Math.max(1, concurrency);
            this.redisKey = Objects.requireNonNull(redisKey);
            this.workers = Executors.newFixedThreadPool(this.concurrency, new RedisLaneThreadFactory(targetId));
        }

        void ensureStarted() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            for (int i = 0; i < concurrency; i++) {
                workers.submit(this::workerLoop);
            }
        }

        void workerLoop() {
            int brPopSec = Math.max(1, Math.min(120, evalProperties.getScheduler().getRedis().getBrPopTimeoutSeconds()));
            Duration timeout = Duration.ofSeconds(brPopSec);
            while (!Thread.currentThread().isInterrupted()) {
                String runId;
                try {
                    runId = redis.opsForList().leftPop(redisKey, timeout);
                } catch (DataAccessException e) {
                    log.warn("eval_event=redis_lane_pop_error target_id={} key={}", targetId, redisKey, e);
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                if (runId == null || runId.isBlank()) {
                    continue;
                }
                try {
                    runWithGlobalQuotaThenExecute(runId);
                } catch (Exception e) {
                    log.error("Redis lane worker crashed: target_id={} run_id={}", targetId, runId, e);
                    try {
                        runStore.markCancelled(runId, "scheduler_worker_crash:" + e.getClass().getSimpleName());
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        /**
         * 5.3：在 {@link RunRunner#execute(String)}（含 {@code tryMarkStarted}）之前占用全局配额；
         * 配额满时 {@code LPUSH} 回队首并短睡后再次出队，直到超时再拒跑。
         */
        private void runWithGlobalQuotaThenExecute(String firstRunId) {
            EvalProperties.Scheduler.Redis rcfg = evalProperties.getScheduler().getRedis();
            int maxGlobal = rcfg.getGlobalMaxConcurrentRunsPerTarget();
            if (maxGlobal <= 0) {
                runRunner.execute(firstRunId);
                return;
            }

            int acquireTimeoutMs = rcfg.getGlobalQuotaAcquireTimeoutMs();
            long waitStartNs = System.nanoTime();
            long deadlineNs = waitStartNs + Math.max(0L, acquireTimeoutMs) * 1_000_000L;
            int brPopSec = Math.max(1, Math.min(120, evalProperties.getScheduler().getRedis().getBrPopTimeoutSeconds()));
            int queueCapacity = evalProperties.getRunner().getTargetQueueCapacity();

            String current = firstRunId.trim();
            if (current.isEmpty()) {
                return;
            }

            while (!Thread.currentThread().isInterrupted()) {
                if (globalRunQuota.tryAcquire(targetId)) {
                    try {
                        runRunner.execute(current);
                    } finally {
                        globalRunQuota.release(targetId);
                    }
                    return;
                }
                if (System.nanoTime() >= deadlineNs) {
                    rejectGlobalQuotaAcquireTimeout(current, queueCapacity, acquireTimeoutMs, waitStartNs);
                    return;
                }
                try {
                    redis.opsForList().leftPush(redisKey, current);
                } catch (DataAccessException e) {
                    long elapsedMs = (System.nanoTime() - waitStartNs) / 1_000_000L;
                    log.error("eval_event=redis_global_quota_lpush_failed target_id={} run_id={}", targetId, current, e);
                    rejectRedisSchedule(current, "REDIS_ERROR", elapsedMs, queueCapacity, acquireTimeoutMs, e);
                    return;
                }
                current = null;
                try {
                    Thread.sleep(Math.min(50L, Math.max(1L, acquireTimeoutMs / 20L)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (current == null && System.nanoTime() < deadlineNs && !Thread.currentThread().isInterrupted()) {
                    long remainMs = (deadlineNs - System.nanoTime()) / 1_000_000L;
                    if (remainMs <= 0) {
                        break;
                    }
                    long waitMs = Math.min(remainMs, (long) brPopSec * 1000L);
                    waitMs = Math.max(1L, waitMs);
                    try {
                        current = redis.opsForList().leftPop(redisKey, Duration.ofMillis(waitMs));
                    } catch (DataAccessException e) {
                        log.warn("eval_event=redis_global_quota_pop_retry_error target_id={} key={}", targetId, redisKey, e);
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (current == null || current.isBlank()) {
                    return;
                }
            }
        }

        private void rejectGlobalQuotaAcquireTimeout(
                String runId,
                int queueCapacity,
                int acquireTimeoutMs,
                long waitStartNs
        ) {
            long elapsedMs = Math.max(0L, (System.nanoTime() - waitStartNs) / 1_000_000L);
            int depth = queueDepthOrZero();
            ScheduleRejectSupport.rejectRunSchedule(
                    log,
                    targetId,
                    runId,
                    "GLOBAL_QUOTA_ACQUIRE_TIMEOUT",
                    elapsedMs,
                    depth,
                    queueCapacity,
                    acquireTimeoutMs,
                    runStore,
                    evalMetrics,
                    audit
            );
        }

        private int queueDepthOrZero() {
            try {
                Long s = redis.opsForList().size(redisKey);
                if (s == null) {
                    return 0;
                }
                return (int) Math.min(s, Integer.MAX_VALUE);
            } catch (DataAccessException e) {
                return 0;
            }
        }

        private void rejectRedisSchedule(
                String runId,
                String reason,
                long elapsedMs,
                int queueCapacity,
                int acquireTimeoutMs,
                Exception error
        ) {
            int depth = queueDepthOrZero();
            log.error(
                    "eval_event=redis_global_quota_reject target_id={} run_id={} reason={}",
                    targetId,
                    runId,
                    reason,
                    error
            );
            ScheduleRejectSupport.rejectRunSchedule(
                    log,
                    targetId,
                    runId,
                    reason,
                    elapsedMs,
                    depth,
                    queueCapacity,
                    acquireTimeoutMs,
                    runStore,
                    evalMetrics,
                    audit
            );
        }

        void shutdown() {
            workers.shutdownNow();
        }
    }

    private static final class RedisLaneThreadFactory implements ThreadFactory {
        private final String targetId;
        private int n = 0;

        RedisLaneThreadFactory(String targetId) {
            this.targetId = targetId;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "redis-lane-" + targetId + "-" + (++n));
            t.setDaemon(true);
            return t;
        }
    }

    @PreDestroy
    void shutdownLanes() {
        for (RedisLane lane : lanes.values()) {
            lane.shutdown();
        }
        lanes.clear();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
