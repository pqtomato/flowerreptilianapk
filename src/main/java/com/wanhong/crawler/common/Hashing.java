package com.wanhong.crawler.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 去重用的摘要工具。
 */
public final class Hashing {

    private Hashing() {
    }

    /** URL 规范化后取 SHA-256 十六进制摘要。 */
    public static String urlHash(String url) {
        return sha256(normalizeUrl(url));
    }

    /**
     * 规范化 URL：去首尾空白、去结尾斜杠、去掉无意义的跟踪参数、统一小写主机名。
     */
    public static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        int hashIndex = value.indexOf('#');
        if (hashIndex >= 0) {
            value = value.substring(0, hashIndex);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
