package com.vagent.eval.run;

import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.EvalRun;
import com.vagent.eval.run.RunModel.RunStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Day3 内存版 Run/Result 存储：
 * - 管 run 状态（running/finished/cancelled）
 * - 管取消标记（cancelRequested）
 * - 管每条 case 的结果列表
 */
@Component
public class RunStore {

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    private final Map<String, List<EvalResult>> resultsByRunId = new ConcurrentHashMap<>();

    public EvalRun createRun(String datasetId, String targetId, int totalCases) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        RunState st = new RunState(runId, datasetId, targetId, totalCases, now);
        runs.put(runId, st);
        resultsByRunId.put(runId, new CopyOnWriteArrayList<>());
        return st.snapshot();
    }

    public Optional<EvalRun> getRun(String runId) {
        RunState st = runs.get(runId);
        return st == null ? Optional.empty() : Optional.of(st.snapshot());
    }

    public List<EvalRun> listRuns() {
        List<EvalRun> out = new ArrayList<>();
        for (RunState st : runs.values()) {
            out.add(st.snapshot());
        }
        out.sort(Comparator.comparing(EvalRun::createdAt).reversed());
        return out;
    }

    public void markStarted(String runId) {
        RunState st = mustGet(runId);
        st.status = RunStatus.RUNNING;
        st.startedAt = Instant.now();
    }

    public void markFinished(String runId) {
        RunState st = mustGet(runId);
        st.status = RunStatus.FINISHED;
        st.finishedAt = Instant.now();
    }

    public void markCancelled(String runId, String reason) {
        RunState st = mustGet(runId);
        st.status = RunStatus.CANCELLED;
        st.cancelReason = reason == null ? "" : reason;
        st.finishedAt = Instant.now();
        st.cancelRequested.set(true);
    }

    public boolean isCancelRequested(String runId) {
        return mustGet(runId).cancelRequested.get();
    }

    public void requestCancel(String runId, String reason) {
        RunState st = mustGet(runId);
        st.cancelRequested.set(true);
        st.cancelReason = reason == null ? "" : reason;
    }

    public void appendResult(EvalResult r) {
        List<EvalResult> list = resultsByRunId.get(r.runId());
        if (list == null) {
            throw new IllegalArgumentException("run not found");
        }
        list.add(r);
        RunState st = mustGet(r.runId());
        st.completedCases.incrementAndGet();
    }

    public List<EvalResult> listResults(String runId, int offset, int limit) {
        List<EvalResult> list = resultsByRunId.get(runId);
        if (list == null) {
            throw new IllegalArgumentException("run not found");
        }
        int from = Math.max(0, offset);
        int to = Math.min(list.size(), from + Math.max(0, limit));
        if (from >= to) {
            return List.of();
        }
        return List.copyOf(list.subList(from, to));
    }

    private RunState mustGet(String runId) {
        RunState st = runs.get(runId);
        if (st == null) {
            throw new IllegalArgumentException("run not found");
        }
        return st;
    }

    private static final class RunState {
        private final String runId;
        private final String datasetId;
        private final String targetId;
        private volatile RunStatus status = RunStatus.PENDING;
        private final int totalCases;
        private final AtomicInteger completedCases = new AtomicInteger(0);
        private final Instant createdAt;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private volatile String cancelReason = "";

        private RunState(String runId, String datasetId, String targetId, int totalCases, Instant createdAt) {
            this.runId = runId;
            this.datasetId = datasetId;
            this.targetId = targetId;
            this.totalCases = totalCases;
            this.createdAt = createdAt;
        }

        private EvalRun snapshot() {
            return new EvalRun(
                    runId,
                    datasetId,
                    targetId,
                    status,
                    totalCases,
                    completedCases.get(),
                    createdAt,
                    startedAt,
                    finishedAt,
                    cancelReason
            );
        }
    }
}

