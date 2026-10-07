package com.videostep.locator.service.aiconfig;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.AiConfigSetting;
import com.videostep.locator.repository.AiConfigSettingRepository;
import lombok.extern.slf4j.Slf4j;
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
 * AI 配置加密（AES/GCM/NoPadding，随机 IV 前缀 Base64）——设计文档 v0.3 §5.3。
 *
 * <p>总钥匙（方案 A）存库：`ai_config_settings.encryption.master_key`（明文字符串），
 * 首次使用（启动）时若不存在则自动生成并落库；此后**启动定死、UI 不提供更换**。
 * 威胁模型：主钥与密文同库 → 不防"能读库的人"解密（纵深防御/防意外暴露）。
 * 解密失败返回 null（如主钥变更），绝不回显密文。</p>
 *
 * <p>与健康数据 {@code SensitiveFieldCipher}（cipher-key/SAFETY_CIPHER_KEY）域隔离。</p>
 */
@Component
@Slf4j
public class AiConfigCipher {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    public static final String KEY_SETTING = "encryption.master_key";

    private final AiConfigSettingRepository settingRepository;
    private final Object lock = new Object();
    private volatile SecretKeySpec key;

    public AiConfigCipher(AiConfigSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    /** 启动时确保主钥存在（未初始化则生成落库）；进程内此后不再读库（启动定死） */
    public void ensureKey() {
        key();
    }

    private SecretKeySpec key() {
        SecretKeySpec k = key;
        if (k == null) {
            synchronized (lock) {
                if (key == null) {
                    key = loadOrCreateKey();
                }
                k = key;
            }
        }
        return k;
    }

    private SecretKeySpec loadOrCreateKey() {
        AiConfigSetting s = settingRepository.selectOne(
                new LambdaQueryWrapper<AiConfigSetting>().eq(AiConfigSetting::getConfigKey, KEY_SETTING));
        String src;
        if (s == null || s.getConfigValue() == null || s.getConfigValue().isBlank()) {
            src = randomKey();
            AiConfigSetting row = AiConfigSetting.builder()
                    .configKey(KEY_SETTING)
                    .configValue(src)
                    .description("AI 配置加密总钥匙（启动定死、UI 不换；变更将导致全部 api_key 不可解密）")
                    .build();
            settingRepository.insert(row);
            log.warn("[AiConfigCipher] 已生成并落库 AI 配置加密总钥匙；请妥善备份。此密钥变更将导致全部 api_key 不可解密。");
        } else {
            src = s.getConfigValue();
        }
        return new SecretKeySpec(sha256(src), 0, 16, "AES");
    }

    /** 加密明文；空值返回 null */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("AI 配置加密失败", e);
        }
    }

    /** 解密密文；解密失败（如主钥变更）返回 null，绝不回显密文 */
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
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[AiConfigCipher] 解密失败（可能主钥变更）：返回 null");
            return null;
        }
    }

    private static String randomKey() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
