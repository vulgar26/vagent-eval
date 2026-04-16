package com.vagent.eval.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段三：业务审计（管理操作）写入服务。
 * <p>
 * 与 {@link com.vagent.eval.security.EvalAuditLogger} 的区别：
 * <ul>
 *   <li>EvalAuditLogger：偏“安全链决策/拒绝”的日志审计（面向日志平台）；</li>
 *   <li>本类：偏“管理面操作”的<strong>落库</strong>审计（可追溯、可查询、重启不丢）。</li>
 * </ul>
 * <p>
 * 当前阶段（local 开发/联调）先把 actor 统一写死为 {@value #ACTOR_LOCAL_DEV}；
 * 后续可升级为账号体系或 token 指纹，但不影响表结构。
 */
@Service
public class EvalAuditService {

    public static final String ACTOR_LOCAL_DEV = "local_dev";
    public static final String ACTOR_SYSTEM = "system";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvalAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一次业务审计事件（落库）。
     *
     * @param eventType 事件类型（稳定枚举字符串，如 RUN_CREATE/RUN_DELETE/RETENTION_CLEANUP）
     * @param status    OK/REJECTED/ERROR
     * @param reason    稳定机器码（USER_REQUEST/RETENTION/NOT_FOUND/INTERNAL...）
     * @param clientIp  客户端 IP（本地可为空）
     * @param method    HTTP 方法（系统任务可为空）
     * @param path      请求路径（系统任务可为空）
     * @param runId     可选：关联 run
     * @param datasetId 可选：关联 dataset
     * @param targetId  可选：关联 target
     * @param detail    可选：扩展信息（调用方需自行脱敏/截断；本阶段先保持极简）
     */
    public void record(
            String eventType,
            String status,
            String reason,
            String clientIp,
            String method,
            String path,
            String runId,
            String datasetId,
            String targetId,
            Map<String, Object> detail
    ) {
        recordWithActor(
                ACTOR_LOCAL_DEV,
                eventType,
                status,
                reason,
                clientIp,
                method,
                path,
                runId,
                datasetId,
                targetId,
                detail
        );
    }

    /**
     * 记录一次业务审计事件（落库）——允许显式传入 actor。
     * <p>
     * 用途：system/retention 等非用户触发事件需要与用户操作区分开。
     */
    public void recordWithActor(
            String actor,
            String eventType,
            String status,
            String reason,
            String clientIp,
            String method,
            String path,
            String runId,
            String datasetId,
            String targetId,
            Map<String, Object> detail
    ) {
        String detailJson = null;
        Map<String, Object> safeDetail = sanitizeDetail(detail);
        if (safeDetail != null && !safeDetail.isEmpty()) {
            try {
                detailJson = objectMapper.writeValueAsString(safeDetail);
            } catch (Exception e) {
                // 审计落库失败不应影响主流程；尽量把错误压缩成一个可读字符串。
                detailJson = "{\"detail_serialize_error\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        }

        jdbc.update("""
                        INSERT INTO eval_audit_event(
                          event_time, event_type, actor,
                          client_ip, method, path,
                          run_id, dataset_id, target_id,
                          status, reason, detail_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                        """,
                Timestamp.from(Instant.now()),
                nz(eventType),
                nz(actor),
                nz(clientIp),
                nz(method),
                nz(path),
                blankToNull(runId),
                blankToNull(datasetId),
                blankToNull(targetId),
                nz(status),
                nz(reason),
                detailJson
        );
    }

    /**
     * 阶段三：审计 detail 的最小清洗（截断 + 限制 key 数），避免把“人手输入 reason”之类写成超大 JSON。
     * <p>
     * 本阶段策略偏保守：不做复杂递归，只保证“体量可控 + 纯文本截断”。
     */
    static Map<String, Object> sanitizeDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        int kept = 0;
        for (Map.Entry<String, Object> e : detail.entrySet()) {
            if (kept >= 30) {
                out.put("detail_truncated", true);
                break;
            }
            String k = e.getKey();
            if (k == null || k.isBlank()) continue;
            Object v = e.getValue();
            if (v instanceof String s) {
                out.put(k, truncate(s, 500));
            } else {
                out.put(k, v);
            }
            kept++;
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

