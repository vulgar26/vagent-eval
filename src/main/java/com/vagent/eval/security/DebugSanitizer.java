package com.vagent.eval.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 阶段三：debug 信息的“写库/出站”清洗器（脱敏 + 截断）。
 * <p>
 * 背景：{@code EvalResult.debug} 既会落库（eval_result.debug_json），也可能被 API 返回给调用方。
 * 如果不做统一清洗，很容易在排障时把 query/answer/token 等敏感信息写进 DB 或输出到公网。
 * <p>
 * 设计目标：
 * <ul>
 *   <li><strong>默认安全</strong>：出现敏感键时直接移除或替换为 hash+len；</li>
 *   <li><strong>白名单优先</strong>：除明确允许的解释性字段外，默认不落库/不出站，避免未来新增字段误泄露；</li>
 *   <li><strong>可控体量</strong>：字符串/列表/键数量有限制，避免单条 debug 过大拖慢 DB 与网络；</li>
 *   <li><strong>不改变主流程</strong>：清洗失败也不应影响评测判定，只要尽量返回一个可序列化的 Map。</li>
 * </ul>
 */
public final class DebugSanitizer {

    private DebugSanitizer() {
    }

    /** 单个字符串字段最大长度（字符数）。 */
    static final int MAX_STR_CHARS = 2_000;
    /** debug Map 最多保留多少个键（按插入顺序截断）。 */
    static final int MAX_KEYS = 60;
    /** debug 里列表最多保留多少项。 */
    static final int MAX_LIST_ITEMS = 30;

    /**
     * 明确敏感键（不应落库/不应出站）。
     * <p>
     * 说明：这是兜底黑名单；更严格的“白名单”可在后续迭代。
     */
    private static final Set<String> ALWAYS_SENSITIVE_KEYS = Set.of(
            "x-eval-token",
            "x-eval-api-token",
            "token",
            "api_token",
            "query",
            "answer",
            "prompt",
            "system_prompt",
            "retrieval_hits",
            "sources",
            "source_text",
            "snippet",
            "content"
    );

    /**
     * 允许落库的 debug key（白名单，大小写不敏感）。
     * <p>
     * 选取标准：能够解释“为何判这个 verdict/error_code”，且不包含业务明文内容。
     * 后续若发现某字段确有价值且不敏感，可在此增补。
     */
    private static final Set<String> STORAGE_ALLOW_KEYS = Set.of(
            "eval_rule_version",
            "expected_behavior",
            "actual_behavior",
            "verdict_reason",
            "requires_citations",
            "citations_enforced",
            "citations_enforced_reason",
            "retrieval_supported",
            "tools_supported",
            "sources_count",
            "http_status",
            "parse_error",
            "contract_reason",
            "contract_violations",
            "security_violation_key",
            "membership_ok",
            "membership_path",
            "membership_top_n",
            "membership_hash_alg",
            "membership_key_derivation",
            "membership_key_derivation_prefix",
            "membership_hashes_considered",
            "membership_plain_hit_ids_considered",
            "membership_eval_token_configured"
    );

    /**
     * 出站允许的 debug key（更严格的白名单，大小写不敏感）。
     * <p>
     * 原则：给外部调用方足够的解释性信息，但不暴露过细排障细节。
     */
    private static final Set<String> OUTBOUND_ALLOW_KEYS = Set.of(
            "eval_rule_version",
            "expected_behavior",
            "actual_behavior",
            "verdict_reason",
            "requires_citations",
            "retrieval_supported",
            "tools_supported",
            "sources_count",
            "contract_reason",
            "contract_violations",
            "membership_ok",
            "membership_path",
            "membership_top_n",
            "membership_hash_alg",
            "membership_key_derivation",
            "membership_key_derivation_prefix",
            "membership_hashes_considered",
            "membership_plain_hit_ids_considered",
            "membership_eval_token_configured"
    );

    /** 允许保留的 key 前缀（白名单）。 */
    private static final List<String> ALLOW_PREFIXES = List.of(
            "membership_"
    );

    /**
     * 写库前清洗（最重要：避免敏感信息进入 Postgres）。
     */
    public static Map<String, Object> sanitizeForStorage(Map<String, Object> debug) {
        return sanitize(debug, false);
    }

    /**
     * 出站前清洗：比写库更严格（即便允许 EVAL_DEBUG 也不输出明显敏感字段）。
     * <p>
     * 若未授权 EVAL_DEBUG，则额外移除 {@link EvalSecurityConstants#FORBIDDEN_DEBUG_KEYS_WITHOUT_EVAL_DEBUG} 中的键，
     * 使响应“变安全而不是直接 403”，避免本地联调误伤。
     */
    public static Map<String, Object> sanitizeForOutbound(Map<String, Object> debug, boolean evalDebugAllowed) {
        Map<String, Object> m = sanitize(debug, true);
        if (!evalDebugAllowed && m != null && !m.isEmpty()) {
            for (String k : EvalSecurityConstants.FORBIDDEN_DEBUG_KEYS_WITHOUT_EVAL_DEBUG) {
                m.remove(k);
            }
        }
        return m;
    }

    private static Map<String, Object> sanitize(Map<String, Object> debug, boolean outbound) {
        if (debug == null || debug.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            int kept = 0;
            int dropped = 0;
            for (Map.Entry<String, Object> e : debug.entrySet()) {
                if (kept >= MAX_KEYS) {
                    out.put("debug_truncated", true);
                    break;
                }
                String key = e.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                String k = key.trim();
                String kl = k.toLowerCase(Locale.ROOT);

                Object v = e.getValue();

                // 1) 永久敏感：不落库、不出站；改为 hash+len 以保留可追踪性（但不泄露原文）。
                if (ALWAYS_SENSITIVE_KEYS.contains(kl)) {
                    out.put(k + "_len", v == null ? 0 : String.valueOf(v).length());
                    out.put(k + "_sha256", sha256Hex(String.valueOf(v)));
                    kept += 2;
                    continue;
                }

                // 2) 白名单：只有允许的 key（或允许前缀）才保留；否则默认丢弃（更安全）。
                if (!allowedKey(kl, outbound)) {
                    dropped++;
                    continue;
                }

                // 2) 对字符串做截断
                if (v instanceof String s) {
                    out.put(k, truncate(s, MAX_STR_CHARS));
                    kept++;
                    continue;
                }

                // 3) 对 List 做截断（只保留前 N 项；元素再做轻量截断）
                if (v instanceof List<?> list) {
                    out.put(k, truncateList(list));
                    kept++;
                    continue;
                }

                // 4) 对 Map：避免深层爆炸，仅做一层浅拷贝 + key 数限制 + value 字符串截断
                if (v instanceof Map<?, ?> map) {
                    out.put(k, truncateMap(map));
                    kept++;
                    continue;
                }

                // 5) 其它类型：直接写入（数字/布尔/枚举等通常安全）
                out.put(k, v);
                kept++;
            }
            if (dropped > 0 && kept < MAX_KEYS) {
                out.put("debug_dropped_keys_count", dropped);
            }
            return out;
        } catch (Exception e) {
            // 清洗失败也不要影响主流程
            return Map.of("debug_sanitize_error", e.getClass().getSimpleName());
        }
    }

    private static boolean allowedKey(String keyLower, boolean outbound) {
        if (keyLower == null || keyLower.isBlank()) {
            return false;
        }
        Set<String> allow = outbound ? OUTBOUND_ALLOW_KEYS : STORAGE_ALLOW_KEYS;
        if (allow.contains(keyLower)) {
            return true;
        }
        for (String p : ALLOW_PREFIXES) {
            if (keyLower.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "…";
    }

    private static List<Object> truncateList(List<?> list) {
        int n = Math.min(list == null ? 0 : list.size(), MAX_LIST_ITEMS);
        java.util.ArrayList<Object> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Object o = list.get(i);
            if (o instanceof String s) {
                out.add(truncate(s, 200));
            } else {
                out.add(o);
            }
        }
        if (list != null && list.size() > n) {
            out.add(Map.of("truncated_more", list.size() - n));
        }
        return out;
    }

    private static Map<String, Object> truncateMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (map == null || map.isEmpty()) {
            return out;
        }
        int kept = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (kept >= 30) {
                out.put("truncated", true);
                break;
            }
            Object k = e.getKey();
            if (k == null) continue;
            String ks = String.valueOf(k);
            Object v = e.getValue();
            if (v instanceof String s) {
                out.put(ks, truncate(s, 200));
            } else {
                out.put(ks, v);
            }
            kept++;
        }
        return out;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

