package com.kylinops.notification.config;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NotificationSecretCipher} 单元测试。
 *
 * <p>覆盖加解密往返、随机 nonce、错误密钥、篡改、缺失密钥、非法长度等场景。
 * 所有断言必须确保异常消息不包含明文或密文,避免敏感数据进入日志。</p>
 */
class NotificationSecretCipherTest {

    private static final String KEY_A = randomBase64Key();
    private static final String KEY_B = randomBase64Key();

    private static String randomBase64Key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static NotificationSecretCipher cipherWithKey(String key) {
        return new NotificationSecretCipher(key);
    }

    @Test
    void encryptUsesRandomNonceAndDecrypts() {
        NotificationSecretCipher cipher = cipherWithKey(KEY_A);
        String first = cipher.encrypt("secret-value");
        String second = cipher.encrypt("secret-value");

        assertThat(first).startsWith("v1:");
        assertThat(second).isNotEqualTo(first);
        assertThat(cipher.decrypt(first)).isEqualTo("secret-value");
        assertThat(cipher.decrypt(second)).isEqualTo("secret-value");
    }

    @Test
    void wrongKeyFailsClosedWithoutLeakingSecret() {
        String encrypted = cipherWithKey(KEY_A).encrypt("do-not-leak");
        assertThatThrownBy(() -> cipherWithKey(KEY_B).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("do-not-leak")
                .hasMessageNotContaining(encrypted);
    }

    @Test
    void tamperedCiphertextIsRejectedWithoutLeakingSecret() {
        NotificationSecretCipher cipher = cipherWithKey(KEY_A);
        String encrypted = cipher.encrypt("plaintext-payload");

        // 篡改 base64 密文段(反转最后一个字符)
        int lastColon = encrypted.lastIndexOf(':');
        String prefix = encrypted.substring(0, lastColon + 1);
        String body = encrypted.substring(lastColon + 1);
        char last = body.charAt(body.length() - 1);
        char swapped = last == 'A' ? 'B' : 'A';
        String tampered = prefix + body.substring(0, body.length() - 1) + swapped;

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("plaintext-payload")
                .hasMessageNotContaining(tampered);
    }

    @Test
    void absentKeyMarksCipherAsUnconfigured() {
        NotificationSecretCipher cipher = new NotificationSecretCipher("");
        assertThat(cipher.isConfigured()).isFalse();
    }

    @Test
    void blankKeyMarksCipherAsUnconfigured() {
        NotificationSecretCipher cipher = new NotificationSecretCipher("   ");
        assertThat(cipher.isConfigured()).isFalse();
    }

    @Test
    void invalidBase64KeyRejectedAtConstruction() {
        assertThatThrownBy(() -> new NotificationSecretCipher("!!!not-base64!!!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidKeyLengthRejectedAtConstruction() {
        // 16 字节 (AES-128) — 业务要求 32 字节 (AES-256)
        byte[] shortKey = new byte[16];
        new SecureRandom().nextBytes(shortKey);
        String shortBase64 = Base64.getEncoder().encodeToString(shortKey);

        assertThatThrownBy(() -> new NotificationSecretCipher(shortBase64))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unconfiguredCipherRefusesEncrypt() {
        NotificationSecretCipher cipher = new NotificationSecretCipher("");
        assertThatThrownBy(() -> cipher.encrypt("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("anything");
    }

    @Test
    void unconfiguredCipherRefusesDecrypt() {
        NotificationSecretCipher cipher = new NotificationSecretCipher("");
        assertThatThrownBy(() -> cipher.decrypt("v1:abc:def"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("v1:abc:def");
    }

    @Test
    void malformedEnvelopeRejectedWithoutLeakingContent() {
        NotificationSecretCipher cipher = cipherWithKey(KEY_A);
        assertThatThrownBy(() -> cipher.decrypt("v2:not-our-version:abc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("v2:not-our-version:abc");
    }

    @Test
    void roundTripPreservesUnicodePayload() {
        NotificationSecretCipher cipher = cipherWithKey(KEY_A);
        String payload = "飞书密钥-key-🔐";
        String encrypted = cipher.encrypt(payload);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(payload);
    }
}