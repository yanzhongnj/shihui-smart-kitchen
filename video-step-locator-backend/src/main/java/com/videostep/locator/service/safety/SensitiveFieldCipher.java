package com.videostep.locator.service.safety;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段加解密（AES/GCM，随机 IV 前缀 Base64）。
 * 用于 health_profiles.medications/conditions（D2：仅本人可见、Admin 不返回、可一键清除）。
 * key 优先级：配置 safety.profile.cipher-key > 环境变量 SAFETY_CIPHER_KEY > 内置开发密钥（仅本地）。
 */
@Component
public class SensitiveFieldCipher {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    public SensitiveFieldCipher(@Value("${safety.profile.cipher-key:}") String keyHex) {
        String src = (keyHex != null && !keyHex.isBlank())
                ? keyHex
                : System.getenv("SAFETY_CIPHER_KEY");
        if (src == null || src.isBlank()) {
            src = "shihui-foodsafety-p1-dev-key-change-me";
        }
        this.key = new SecretKeySpec(sha256(src), 0, 16, "AES");
    }

    /** 加密明文；空值返回 null */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /** 解密密文；解密失败（如 key 变更）返回 null，绝不回显密文 */
    public String decrypt(String cipherB64) {
        if (cipherB64 == null || cipherB64.isBlank()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(cipherB64);
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_LEN];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
