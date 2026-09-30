package com.water.widget;

import java.security.MessageDigest;

/**
 * 签名工具类。
 *
 * 盐值由本地 secrets.properties 或 CI secrets 注入。
 */
public class Signer {

    /**
     * 生成签名。
     * 当 salt 为空时返回空字符串，表示签名功能未配置。
     */
    static String sign(String adId, String token, String uid, String salt) {
        return signAt(adId, token, uid, salt, System.currentTimeMillis());
    }

    static String signAt(String adId, String token, String uid, String salt, long now) {
        if (salt == null || salt.isEmpty()) {
            return "";
        }
        try {
            // 服务端 2026-09 起按 30 秒时间桶校验，10 秒桶会被拒。
            long n = 30 * (now / 30000);
            String e = token.length() >= 8 ? token.substring(token.length() - 8) : token;
            String t = uid.length() >= 8 ? uid.substring(uid.length() - 8) : uid;
            String raw = adId + n + e + t + salt;
            return md5(raw);
        } catch (Exception ex) {
            return "";
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
