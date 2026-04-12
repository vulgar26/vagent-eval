package com.vagent.eval.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Day9：将请求 token 做 SHA-256 后与配置中的十六进制 hash 做常量时间比对。
 */
public final class EvalApiTokenVerifier {

    private EvalApiTokenVerifier() {
    }

    /**
     * @param presentedToken 请求头中的明文 token；null 视为空串
     * @param configuredHash   {@code eval.api.token-hash}，小写十六进制 SHA-256；空白表示未启用校验
     * @return configuredHash 空白时 true；否则仅当 hash 匹配时 true
     */
    public static boolean valid(String presentedToken, String configuredHash) {
        if (configuredHash == null || configuredHash.isBlank()) {
            return true;
        }
        String token = presentedToken == null ? "" : presentedToken;
        String computed = sha256Hex(token);
        String expected = configuredHash.trim().toLowerCase();
        return MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /** 供测试与配置生成 token-hash 时复用。 */
    public static String sha256Hex(String utf8) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(utf8.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }
}
