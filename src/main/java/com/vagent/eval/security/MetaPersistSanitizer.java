package com.vagent.eval.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.config.EvalProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将上游 {@code meta} 对象落库前的策略：与 {@link DebugSanitizer} 分流，避免用 debug 白名单误删观测键。
 * <p>
 * 规则（对齐 Vagent 说明）：
 * <ul>
 *   <li>默认从待持久化副本中移除 {@code retrieval_hit_ids}；仅当配置显式允许且 runner 使用 {@code EVAL_DEBUG} 时保留。</li>
 *   <li>序列化后字符数超过 {@link EvalProperties.Persistence#getMaxTargetMetaJsonChars()} 时不落库（返回 null）。</li>
 * </ul>
 */
public final class MetaPersistSanitizer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private MetaPersistSanitizer() {
    }

    /**
     * @param metaNode 上游 JSON 根级的 {@code meta} 对象节点；非 object 则返回 null
     */
    public static Map<String, Object> sanitizeMetaForStorage(JsonNode metaNode, ObjectMapper om, EvalProperties props) {
        if (metaNode == null || metaNode.isNull() || !metaNode.isObject()) {
            return null;
        }
        Map<String, Object> m;
        try {
            m = new LinkedHashMap<>(om.convertValue(metaNode, MAP_TYPE));
        } catch (IllegalArgumentException e) {
            return null;
        }
        boolean retainPlainIds = props.getPersistence().isAllowPlainRetrievalHitIdsInMeta()
                && isEvalDebugChatMode(props.getRunner().getChatMode());
        if (!retainPlainIds) {
            m.remove("retrieval_hit_ids");
        }
        int max = props.getPersistence().getMaxTargetMetaJsonChars();
        try {
            String json = om.writeValueAsString(m);
            if (json.length() > max) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return m;
    }

    /**
     * 出站：未带 {@link EvalSecurityConstants#HDR_EVAL_DEBUG} 时从 meta 副本移除明文命中 id 列表。
     */
    public static Map<String, Object> sanitizeMetaForOutbound(Map<String, Object> meta, boolean evalDebugHeaderAllowed) {
        if (meta == null || meta.isEmpty()) {
            return meta;
        }
        if (evalDebugHeaderAllowed || !meta.containsKey("retrieval_hit_ids")) {
            return meta;
        }
        Map<String, Object> copy = new LinkedHashMap<>(meta);
        copy.remove("retrieval_hit_ids");
        return copy;
    }

    private static boolean isEvalDebugChatMode(String raw) {
        if (raw == null) {
            return false;
        }
        return "EVAL_DEBUG".equalsIgnoreCase(raw.trim());
    }
}
