package com.vagent.eval.web;

import com.vagent.eval.config.EvalProperties;

import java.util.List;
import java.util.Map;

/**
 * 本地探针 {@link EvalProbeChatController} 对 {@code meta} 的补充：仅写入各 target 在配置里声明的
 * {@code eval.targets[].meta-trace-keys} 中的键，避免把某一被测方（如 vagent）的观测形态强加给 travel-ai 等。
 * <p>
 * 对列表中的键，若本类有「联调用固定样本」则写入；否则跳过（由真实 target 自行返回时可落库）。
 */
public final class ProbeMetaAugmentor {

    private ProbeMetaAugmentor() {
    }

    /**
     * @param targetId   请求头 {@code X-Eval-Target-Id}
     * @param meta       正在组装的 {@code meta} map（就地修改）
     * @param hitCount   本响应模拟的检索命中条数（用于 {@code retrieve_hit_count} 等）
     * @param evalProperties 读取各 target 的 {@code meta-trace-keys}
     */
    public static void applyConfiguredTraceKeys(
            String targetId,
            Map<String, Object> meta,
            int hitCount,
            EvalProperties evalProperties
    ) {
        if (evalProperties == null || meta == null) {
            return;
        }
        List<String> keys = evalProperties.resolveMetaTraceKeys(targetId);
        for (String key : keys) {
            Object v = probeSampleForKey(key, hitCount);
            if (v != null) {
                meta.put(key, v);
            }
        }
    }

    /**
     * 仅为常见联调键提供稳定样本；未知键返回 null（不覆盖、不猜测 travel-ai 等专有字段）。
     */
    private static Object probeSampleForKey(String key, int hitCount) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "retrieve_hit_count" -> hitCount;
            case "hybrid_lexical_mode" -> "tsvector";
            case "hybrid_lexical_outcome" -> "ok";
            case "retrieve_top1_distance" -> 0.05;
            case "retrieve_top1_distance_bucket" -> "0.0-0.2";
            default -> null;
        };
    }
}
