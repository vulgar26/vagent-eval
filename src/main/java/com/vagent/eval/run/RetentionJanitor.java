package com.vagent.eval.run;

import com.vagent.eval.audit.EvalAuditService;
import com.vagent.eval.config.EvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * P1：最小 retention 清理器（落库/内存通用）。
 * <p>
 * 默认关闭（{@code eval.retention.enabled=false}），避免本地开发误删证据；生产或 CI 可显式开启。
 * <p>
 * <strong>清理口径</strong>：仅删除已进入终态（FINISHED/CANCELLED）且 {@code finished_at < now - days} 的 run；
 * 删除 run 时会级联删除其 results（DB 版依赖外键 ON DELETE CASCADE；内存版由 {@link RunStore} 实现保证）。
 */
@Component
public class RetentionJanitor {

    private static final Logger log = LoggerFactory.getLogger(RetentionJanitor.class);

    private final EvalProperties evalProperties;
    private final RunStore runStore;
    private final EvalAuditService audit;

    public RetentionJanitor(EvalProperties evalProperties, RunStore runStore, EvalAuditService audit) {
        this.evalProperties = evalProperties;
        this.runStore = runStore;
        this.audit = audit;
    }

    /** 与 {@code eval.retention.interval-ms} 对齐；勿引用 {@code @evalProperties}（Boot 3 下该 bean 名非此）。 */
    @Scheduled(fixedDelayString = "${eval.retention.interval-ms:86400000}")
    public void cleanupOldRuns() {
        if (evalProperties == null || !evalProperties.getRetention().isEnabled()) {
            return;
        }
        int days = evalProperties.getRetention().getDays();
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        int deleted = runStore.deleteRunsFinishedBefore(cutoff);
        if (deleted > 0) {
            log.info("Retention cleanup deleted {} runs finished before {}", deleted, cutoff);
            // 阶段三：系统级自动清理也要可追溯（actor 仍按 local_dev 占位，后续可升级为 system）
            audit.recordWithActor(
                    EvalAuditService.ACTOR_SYSTEM,
                    "RETENTION_CLEANUP",
                    "OK",
                    "RETENTION",
                    "",
                    "",
                    "",
                    null,
                    null,
                    null,
                    java.util.Map.of(
                            "deleted_runs", deleted,
                            "days", days,
                            "cutoff", cutoff.toString()
                    )
            );
        }
    }
}

