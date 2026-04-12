package com.vagent.eval.run;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Day6：引用闭环 —— <strong>hashed membership</strong> 的纯函数工具类。
 * <p>
 * <strong>在整体流程中的位置</strong>：{@link RunEvaluator} 在校验完 HTTP 契约、且本 case
 * {@code requires_citations=true}、且上游声明支持检索并已返回非空 {@code sources} 之后，
 * 会调用本类方法：先把「检索候选 chunk」与「sources 里声明的 chunk」统一成 canonical id，
 * 再映射到同一套 SHA-256 摘要（十六进制），做集合包含判定；不在候选集内则判
 * {@link com.vagent.eval.run.RunModel.ErrorCode#SOURCE_NOT_IN_HITS}。
 * <p>
 * <strong>与 SSOT 的关系</strong>：canonical 规则、哈希输入串拼接顺序、分隔符、以及「候选集取前 N 条」
 * 的 N（见 {@link com.vagent.eval.config.EvalProperties.Membership#getTopN()}）应以仓库外规范为准；
 * 本类实现与 {@code application.yml} 中的 {@code eval.membership.*} 为 P0 可落地版本，若 SSOT 变更应同步改此处与配置说明。
 */
public final class CitationMembership {

    private CitationMembership() {
    }

    /**
     * 将 chunk / 引用条目的原始 {@code id} 字符串规范化为 canonical 形式，用于跨层比对。
     * <p>
     * <strong>口径（P0）</strong>：去掉首尾空白后按 {@link Locale#ROOT} 转小写。
     * 这样可消除「同一 chunk、不同大小写/意外空格」导致的假阴性（误判不在 hits 内）。
     * 刻意不做更激进的 Unicode 规范化（如 NFC），以免与上游 KB 主键约定不一致；若 SSOT 要求 NFC/NFKC，应在此扩展。
     *
     * @param raw 响应 JSON 中 {@code id} 字段文本；null 视为空串
     * @return 规范化后的 id；可能为空串（空串仍参与哈希，便于稳定失败而非 NPE）
     */
    public static String canonicalChunkId(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 计算某 canonical chunk id 在 membership 判定中使用的摘要（小写十六进制）。
     * <p>
     * <strong>输入串格式（P0，便于多方实现一致）</strong>：UTF-8 编码下
     * {@code salt + RECORD_SEPARATOR + targetId + RECORD_SEPARATOR + canonicalChunkId}，
     * 其中 {@link #RECORD_SEPARATOR} 为 ASCII 控制符 {@code U+001E}（Record Separator），
     * 一般不出现在业务 id 中，降低拼接歧义。
     * <p>
     * <strong>为何带 targetId</strong>：同一 chunk id 在不同被测 target / 租户下可能指向不同物理文档；
     * 把 {@code X-Eval-Target-Id} 对应字符串纳入哈希，可避免跨 target 的「偶然撞 id」被误判为同一成员。
     * <p>
     * <strong>盐（salt）</strong>：来自配置 {@code eval.membership.salt}，通过 {@code X-Eval-Membership-Salt}
     * 传给被测侧，使评测方与被测方对「候选集里有哪些哈希」达成一致；日志中禁止打印完整盐值。
     *
     * @param salt        配置中的盐；null 按空串
     * @param targetId    与请求头 {@code X-Eval-Target-Id} 对齐
     * @param canonicalId {@link #canonicalChunkId(String)} 的输出
     * @return 64 位小写十六进制 SHA-256 摘要
     */
    public static String membershipHashHex(String salt, String targetId, String canonicalId) {
        String s = salt == null ? "" : salt;
        String t = targetId == null ? "" : targetId;
        String c = canonicalId == null ? "" : canonicalId;
        String payload = s + RECORD_SEPARATOR + t + RECORD_SEPARATOR + c;
        return sha256Hex(payload);
    }

    /**
     * 与哈希拼接逻辑配套的分隔符常量，供文档或其它模块引用同一字符。
     */
    public static final char RECORD_SEPARATOR = '\u001E';

    /**
     * 对 UTF-8 字符串做 SHA-256，并格式化为小写十六进制。
     *
     * @param utf8Payload 已确定语义的拼接串
     * @return 64 字符小写 hex
     */
    static String sha256Hex(String utf8Payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(utf8Payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
