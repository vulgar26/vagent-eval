package com.vagent.eval.observability;

import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.Verdict;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 阶段二：评测服务最小可观测埋点（Micrometer）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li><strong>标签维度可控</strong>：{@code target_id} 来自配置（数量有限）；HTTP 侧用 {@code status_class}（2xx/4xx/5xx）避免把完整 URL 打进标签；</li>
 *   <li><strong>不把指标记录放在关键路径的失败分支之外</strong>：即使 Micrometer 内部异常也不应吞掉主业务（本类方法保持极简）；</li>
 *   <li><strong>Prometheus 拉取</strong>：由 Spring Boot Actuator 暴露 {@code /actuator/prometheus}，Prometheus 定时 scrape。</li>
 * </ul>
 */
@Component
public class EvalMetrics {

    private final MeterRegistry registry;

    public EvalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 管理 API 创建 run（落库成功之后调用即可）。
     */
    public void runCreated(String targetId) {
        registry.counter("eval.run.created", "target_id", nz(targetId)).increment();
    }

    /**
     * run 进入终态：{@code FINISHED} 或 {@code CANCELLED}（与 {@link com.vagent.eval.run.RunModel.RunStatus} 对齐）。
     */
    public void runTerminal(String targetId, String terminalStatus) {
        registry.counter(
                "eval.run.terminal",
                "target_id", nz(targetId),
                "status", nz(terminalStatus)
        ).increment();
    }

    /**
     * 单题评测结束（写入 {@code eval_result} 之后调用）。
     */
    public void caseResult(String targetId, Verdict verdict, ErrorCode errorCode) {
        String v = verdict == null ? "UNKNOWN" : verdict.name();
        String ec = errorCode == null ? "NONE" : errorCode.name();
        registry.counter(
                "eval.case.result",
                "target_id", nz(targetId),
                "verdict", v,
                "error_code", ec
        ).increment();
    }

    /**
     * 下游 {@code POST /api/v1/eval/chat} 调用耗时（观测网络/被测延迟；不代表 eval 自身 CPU）。
     */
    public void targetHttp(String targetId, int httpStatus, long latencyMs) {
        Timer timer = Timer.builder("eval.target.http")
                .tag("target_id", nz(targetId))
                .tag("status_class", statusClass(httpStatus))
                .register(registry);
        timer.record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);
    }

    /**
     * 进程内并发许可超时（{@link java.util.concurrent.Semaphore#tryAcquire} 失败）。
     */
    public void runnerSemaphoreTimeout(String targetId) {
        registry.counter("eval.runner.semaphore_timeout", "target_id", nz(targetId)).increment();
    }

    /**
     * 阶段四：调度层入队被拒绝（队列满或等待超时）。
     */
    public void runnerEnqueueRejected(String targetId, String reason) {
        registry.counter(
                "eval.runner.enqueue.rejected",
                "target_id", nz(targetId),
                "reason", nz(reason)
        ).increment();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 将具体 HTTP 状态码归并为有限集合，避免 Prometheus 时间序列爆炸。
     */
    private static String statusClass(int code) {
        if (code >= 200 && code <= 299) {
            return "2xx";
        }
        if (code >= 300 && code <= 399) {
            return "3xx";
        }
        if (code >= 400 && code <= 499) {
            return "4xx";
        }
        if (code >= 500 && code <= 599) {
            return "5xx";
        }
        if (code == 0) {
            return "none";
        }
        return "other";
    }
}
