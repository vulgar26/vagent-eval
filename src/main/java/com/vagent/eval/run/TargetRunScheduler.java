package com.vagent.eval.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.vagent.eval.audit.EvalAuditService;
import com.vagent.eval.scheduler.RedisRunQueueDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.observability.EvalMetrics;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阶段四：按 target 隔离的 run 调度器（FIFO 队列 + worker）。
 * <p>
 * 目标：
 * <ul>
 *   <li>不同 target 互不影响：每个 target 一条 lane（队列 + worker）。</li>
 *   <li>同 target FIFO 公平：队列先进先出，按创建顺序执行。</li>
 *   <li>为后续 Redis/多实例配额打底：把“并发/回压”从 case 级别上移到 run 调度层。</li>
 * </ul>
 * <p>
 * 队列容量、worker 数、入队超时见 {@link com.vagent.eval.config.EvalProperties.Runner}（{@code eval.runner.*}）；
 * 入队失败会打点 {@code eval.runner.enqueue.rejected} 并写审计 {@code RUN_SCHEDULE_REJECT}。
 * <p>
 * 阶段五 5.2：当 {@code eval.scheduler.redis.enabled=true} 且存在 {@link RedisRunQueueDispatcher} bean 时，入队走 Redis 列表（跨进程），
 * 否则仍使用本进程内存队列（与 5.1 前行为一致）。若打开 Redis 开关但 dispatcher 未装配（例如无 {@code StringRedisTemplate}），则回退内存并打 WARN。
 */
@Component
public class TargetRunScheduler {

    private static final Logger log = LoggerFactory.getLogger(TargetRunScheduler.class);

    // 兜底值：仅用于“EvalProperties 未注入成功/为空”的边界情况
    static final int DEFAULT_TARGET_CONCURRENCY = 1;
    static final int DEFAULT_QUEUE_CAPACITY = 50;
    static final int DEFAULT_ENQUEUE_TIMEOUT_MS = 250;

    private final RunRunner runRunner;
    private final RunStore runStore;
    private final EvalProperties evalProperties;
    private final EvalMetrics evalMetrics;
    private final EvalAuditService audit;
    private final RedisRunQueueDispatcher redisRunQueueDispatcher;

    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    public TargetRunScheduler(
            RunRunner runRunner,
            RunStore runStore,
            EvalProperties evalProperties,
            EvalMetrics evalMetrics,
            EvalAuditService audit,
            @Autowired(required = false) RedisRunQueueDispatcher redisRunQueueDispatcher
    ) {
        this.runRunner = runRunner;
        this.runStore = runStore;
        this.evalProperties = evalProperties;
        this.evalMetrics = evalMetrics;
        this.audit = audit;
        this.redisRunQueueDispatcher = redisRunQueueDispatcher;
    }

    /**
     * 将 run 加入对应 target 的 lane。
     * <p>
     * 入队失败（队列满或等待超时）时，本步先将该 run 直接标记为 CANCELLED，避免产生大量 case 级 RATE_LIMITED 噪声结果。
     */
    public void enqueue(String targetId, String runId) {
        String tid = nz(targetId);
        String rid = nz(runId);
        if (tid.isEmpty() || rid.isEmpty()) {
            throw new IllegalArgumentException("targetId and runId are required");
        }

        int queueCapacity = evalProperties == null ? DEFAULT_QUEUE_CAPACITY : evalProperties.getRunner().getTargetQueueCapacity();
        int targetConcurrency = evalProperties == null ? DEFAULT_TARGET_CONCURRENCY : evalProperties.getRunner().getTargetConcurrency();
        int enqueueTimeoutMs = evalProperties == null ? DEFAULT_ENQUEUE_TIMEOUT_MS : evalProperties.getRunner().getEnqueueTimeoutMs();

        if (evalProperties != null
                && evalProperties.getScheduler().getRedis().isEnabled()
                && redisRunQueueDispatcher != null) {
            redisRunQueueDispatcher.enqueue(tid, rid, queueCapacity, enqueueTimeoutMs);
            return;
        }
        if (evalProperties != null
                && evalProperties.getScheduler().getRedis().isEnabled()
                && redisRunQueueDispatcher == null) {
            log.warn(
                    "eval_event=redis_schedule_fallback_memory target_id={} run_id={} reason=redis_dispatcher_bean_missing",
                    tid,
                    rid
            );
        }

        Lane lane = lanes.computeIfAbsent(tid, k -> new Lane(k, queueCapacity, targetConcurrency));
        lane.ensureStarted();

        long t0 = System.nanoTime();
        boolean ok;
        try {
            ok = lane.queue.offer(rid, enqueueTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        }

        if (ok) {
            log.info(
                    "eval_event=run_enqueued target_id={} run_id={} queue_depth={} queue_capacity={}",
                    tid,
                    rid,
                    lane.queue.size(),
                    lane.capacity
            );
            return;
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        String reason;
        if (enqueueTimeoutMs <= 0) {
            reason = "QUEUE_FULL";
        } else {
            // 很快失败更像“队列已满”，接近超时更像“等待超时”
            reason = elapsedMs >= (enqueueTimeoutMs * 0.9) ? "ENQUEUE_TIMEOUT" : "QUEUE_FULL";
        }

        ScheduleRejectSupport.rejectRunSchedule(
                log,
                tid,
                rid,
                reason,
                elapsedMs,
                lane.queue.size(),
                lane.capacity,
                enqueueTimeoutMs,
                runStore,
                evalMetrics,
                audit
        );
    }

    private final class Lane {
        final String targetId;
        final int capacity;
        final int concurrency;
        final BlockingQueue<String> queue;
        final ExecutorService workers;
        final AtomicBoolean started = new AtomicBoolean(false);

        Lane(String targetId, int capacity, int concurrency) {
            this.targetId = Objects.requireNonNull(targetId);
            this.capacity = Math.max(1, capacity);
            this.concurrency = Math.max(1, concurrency);
            this.queue = new ArrayBlockingQueue<>(this.capacity);
            this.workers = Executors.newFixedThreadPool(this.concurrency, new LaneThreadFactory(targetId));
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
            while (!Thread.currentThread().isInterrupted()) {
                String runId;
                try {
                    runId = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    runRunner.execute(runId);
                } catch (Exception e) {
                    // execute 内部已尽量 markCancelled；这里仅避免 worker 死掉
                    log.error("Lane worker crashed: target_id={} run_id={}", targetId, runId, e);
                    try {
                        runStore.markCancelled(runId, "scheduler_worker_crash:" + e.getClass().getSimpleName());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static final class LaneThreadFactory implements ThreadFactory {
        private final String targetId;
        private int n = 0;

        private LaneThreadFactory(String targetId) {
            this.targetId = targetId;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "lane-" + targetId + "-" + (++n));
            t.setDaemon(true);
            return t;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}

