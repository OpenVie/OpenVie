// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.common.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope for database-stored secrets.
 *
 * <p>One formatter, one hash discipline for every stored secret in the
 * platform: notification channel credentials and BizApp credential vault rows
 * share this single envelope (12-byte random IV + ciphertext, base64). Each
 * surface wires its own key variable — {@code NOTIFICATION_ENC_KEY} and
 * {@code BIZAPP_ENC_KEY} — through its own bean.
 */
public class GeneralCredentialsCipher {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final String keyVariable;

    public GeneralCredentialsCipher(String encodedKey, String keyVariable) {
        this.keyVariable = keyVariable;
        this.key = parse(encodedKey, keyVariable);
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
            throw new IllegalStateException("Unable to encrypt stored credentials", exception);
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
                    "Stored credentials could not be decrypted; the encryption key may"
                    + " have changed", exception);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "Stored credentials require " + keyVariable + " (32 random bytes, base64)."
                    + " Generate one with: openssl rand -base64 32");
        }
    }

    private static SecretKeySpec parse(String encoded, String keyVariable) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(keyVariable + " must be base64 of exactly 32 bytes");
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    keyVariable + " must decode to exactly 32 bytes, got " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }
}
