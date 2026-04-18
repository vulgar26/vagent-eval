package com.vagent.eval.run;

import com.vagent.eval.audit.EvalAuditService;
import com.vagent.eval.observability.EvalMetrics;
import org.slf4j.Logger;

import java.util.Map;

/**
 * 入队失败（内存队列满 / Redis 队列满 / Redis 不可用）时统一取消 run、打指标与审计。
 */
public final class ScheduleRejectSupport {

    private ScheduleRejectSupport() {
    }

    public static void rejectRunSchedule(
            Logger log,
            String targetId,
            String runId,
            String reason,
            long elapsedMs,
            int queueDepth,
            int queueCapacity,
            int enqueueTimeoutMs,
            RunStore runStore,
            EvalMetrics evalMetrics,
            EvalAuditService audit
    ) {
        try {
            runStore.markCancelled(runId, "schedule_rejected:" + reason);
        } catch (Exception ignored) {
        }
        try {
            evalMetrics.runnerEnqueueRejected(targetId, reason);
        } catch (Exception ignored) {
        }
        try {
            audit.recordWithActor(
                    EvalAuditService.ACTOR_SYSTEM,
                    "RUN_SCHEDULE_REJECT",
                    "REJECTED",
                    reason,
                    "",
                    "",
                    "",
                    runId,
                    null,
                    targetId,
                    Map.of(
                            "queue_depth", queueDepth,
                            "queue_capacity", queueCapacity,
                            "enqueue_timeout_ms", enqueueTimeoutMs,
                            "elapsed_ms", elapsedMs
                    )
            );
        } catch (Exception ignored) {
        }
        log.info(
                "eval_event=run_enqueue_rejected target_id={} run_id={} reason={} queue_depth={} queue_capacity={} enqueue_timeout_ms={} elapsed_ms={}",
                targetId,
                runId,
                reason,
                queueDepth,
                queueCapacity,
                enqueueTimeoutMs,
                elapsedMs
        );
    }
}
