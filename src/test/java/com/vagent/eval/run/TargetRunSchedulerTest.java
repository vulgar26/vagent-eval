package com.vagent.eval.run;

import com.vagent.eval.audit.EvalAuditService;
import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.observability.EvalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 阶段四：{@link TargetRunScheduler} 单测（不启动 Spring 容器）。
 * <p>
 * 覆盖队列满回压与同一 target 下 FIFO 顺序。
 */
class TargetRunSchedulerTest {

    @Test
    void queueFull_withEnqueueTimeoutZero_marksRunCancelledWithQueueFull() throws Exception {
        RunRunner runRunner = mock(RunRunner.class);
        RunStore runStore = mock(RunStore.class);
        EvalAuditService audit = mock(EvalAuditService.class);
        EvalMetrics metrics = new EvalMetrics(new SimpleMeterRegistry());

        EvalProperties ep = new EvalProperties();
        ep.getRunner().setTargetConcurrency(1);
        ep.getRunner().setTargetQueueCapacity(1);
        ep.getRunner().setEnqueueTimeoutMs(0);

        TargetRunScheduler scheduler = new TargetRunScheduler(runRunner, runStore, ep, metrics, audit);

        CountDownLatch firstExecuteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(inv -> {
            String rid = inv.getArgument(0);
            if ("run_first".equals(rid)) {
                firstExecuteEntered.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(runRunner).execute(anyString());

        scheduler.enqueue("t1", "run_first");
        assertThat(firstExecuteEntered.await(10, TimeUnit.SECONDS)).isTrue();

        scheduler.enqueue("t1", "run_second");
        scheduler.enqueue("t1", "run_third");

        verify(runStore).markCancelled(eq("run_third"), contains("QUEUE_FULL"));
        verify(audit).recordWithActor(
                eq(EvalAuditService.ACTOR_SYSTEM),
                eq("RUN_SCHEDULE_REJECT"),
                eq("REJECTED"),
                eq("QUEUE_FULL"),
                eq(""),
                eq(""),
                eq(""),
                eq("run_third"),
                isNull(),
                eq("t1"),
                anyMap()
        );

        releaseFirst.countDown();
        Thread.sleep(200L);
    }

    @Test
    void fifo_twoRuns_executeInOrder() throws Exception {
        RunRunner runRunner = mock(RunRunner.class);
        RunStore runStore = mock(RunStore.class);
        EvalAuditService audit = mock(EvalAuditService.class);
        EvalMetrics metrics = new EvalMetrics(new SimpleMeterRegistry());

        EvalProperties ep = new EvalProperties();
        ep.getRunner().setTargetConcurrency(1);
        ep.getRunner().setTargetQueueCapacity(10);
        ep.getRunner().setEnqueueTimeoutMs(5_000);

        List<String> order = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            order.add(inv.getArgument(0));
            return null;
        }).when(runRunner).execute(anyString());

        TargetRunScheduler scheduler = new TargetRunScheduler(runRunner, runStore, ep, metrics, audit);

        scheduler.enqueue("t2", "run_a");
        scheduler.enqueue("t2", "run_b");

        for (int i = 0; i < 200; i++) {
            if (order.size() >= 2) {
                break;
            }
            Thread.sleep(50L);
        }

        assertThat(order).containsExactly("run_a", "run_b");
    }
}
