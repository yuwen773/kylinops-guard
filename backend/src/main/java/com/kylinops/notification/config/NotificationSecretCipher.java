package com.kylinops.notification.config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * 通知通道密钥加解密工具(AES-256-GCM)。
 *
 * <p>信封格式: <code>v1:&lt;base64-nonce&gt;:&lt;base64-ciphertext||tag&gt;</code></p>
 *
 * <p>关键不变量:</p>
 * <ul>
 *   <li>随机 12 字节 nonce(每次加密),保证密文不可链接</li>
 *   <li>128 位 GCM 认证标签,密文/标签任一被篡改即失败</li>
 *   <li>所有加密异常统一包装为 {@link IllegalStateException},且<b>不包含</b>明文/密文</li>
 *   <li>未配置主密钥时 {@link #isConfigured()} 返回 false,调用 {@link #encrypt}/{@link #decrypt} 抛异常</li>
 * </ul>
 *
 * <p>线程安全: {@link #decryptCipher} 无状态;{@link #random} 为线程安全单例。</p>
 */
public final class NotificationSecretCipher {

    private static final String VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64_DEC = Base64.getDecoder();

    private final SecretKeySpec keySpec;
    private final boolean configured;

    /**
     * @param base64MasterKey 主密钥的 Base64 编码。空/blank/null 视为未配置。
     */
    public NotificationSecretCipher(String base64MasterKey) {
        Objects.requireNonNull(base64MasterKey, "base64MasterKey");
        if (base64MasterKey.isBlank()) {
            this.keySpec = null;
            this.configured = false;
            return;
        }
        byte[] raw;
        try {
            raw = B64_DEC.decode(base64MasterKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("notification master key is not valid Base64");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "notification master key must decode to " + KEY_BYTES + " bytes");
        }
        this.keySpec = new SecretKeySpec(raw, "AES");
        this.configured = true;
    }

    /** 主密钥是否已配置。未配置时 encrypt/decrypt 不可调用。 */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * 加密并返回 v1 信封字符串。
     *
     * @throws IllegalStateException 未配置主密钥或加密失败(消息不含明文/密文)
     */
    public String encrypt(String plaintext) {
        if (!configured) {
            throw new IllegalStateException("notification secret cipher is not configured");
        }
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] ct;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, nonce));
            ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("notification secret encryption failed");
        }
        return VERSION + ":" + B64.encodeToString(nonce) + ":" + B64.encodeToString(ct);
    }

    /**
     * 解密 v1 信封字符串。
     *
     * @throws IllegalStateException 未配置主密钥或解密失败(消息不含明文/密文)
     */
    public String decrypt(String envelope) {
        if (!configured) {
            throw new IllegalStateException("notification secret cipher is not configured");
        }
        Objects.requireNonNull(envelope, "envelope");
        // 拒绝包含冒号以外的额外段(避免异常消息回显 envelope 内容)
        String[] parts = envelope.split(":", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("notification secret envelope has unsupported version");
        }
        byte[] nonce;
        byte[] ct;
        try {
            nonce = B64_DEC.decode(parts[1]);
            ct = B64_DEC.decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("notification secret envelope is malformed");
        }
        if (nonce.length != NONCE_BYTES) {
            throw new IllegalStateException("notification secret envelope nonce has invalid length");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("notification secret decryption failed");
        }
    }
}