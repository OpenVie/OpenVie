package com.cacanode.api.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope for stored notification-channel credentials.
 *
 * <p>The key comes from {@code NOTIFICATION_ENC_KEY} (32 bytes, base64). When
 * it is absent, database-stored channels are unavailable and the API reports
 * that precisely; the environment-configured bootstrap path keeps working, so
 * an installation is never forced to set a second secret it does not need.
 */
@Component
public class CredentialCipher {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;

    public CredentialCipher(@Value("${app.notifications.encryption-key:}") String encodedKey) {
        this.key = parse(encodedKey);
    }

    /** True when a usable key was configured. */
    public boolean available() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt channel credentials", exception);
        }
    }

    public String decrypt(String envelope) {
        requireKey();
        try {
            byte[] raw = Base64.getDecoder().decode(envelope);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(raw, GCM_IV_BYTES, raw.length - GCM_IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Stored channel credentials could not be decrypted; the encryption key may"
                    + " have changed", exception);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "Stored notification channels require NOTIFICATION_ENC_KEY (32 random bytes,"
                    + " base64). Generate one with: openssl rand -base64 32");
        }
    }

    private static SecretKeySpec parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(
                    "NOTIFICATION_ENC_KEY must be base64 of exactly 32 bytes");
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "NOTIFICATION_ENC_KEY must decode to exactly 32 bytes, got " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }
}
