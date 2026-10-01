package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString(new byte[32]); // deterministic 32 zero bytes

    @Test
    void roundTripsPlaintext() {
        CredentialCipher cipher = new CredentialCipher(KEY);

        String encrypted = cipher.encrypt("hunter2");

        assertNotEquals("hunter2", encrypted);
        assertEquals("hunter2", cipher.decrypt(encrypted));
    }

    @Test
    void eachEncryptionProducesDifferentCiphertext() {
        CredentialCipher cipher = new CredentialCipher(KEY);

        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"),
                "GCM must use a fresh IV per call");
    }

    @Test
    void tamperedCiphertextFailsLoudly() {
        CredentialCipher cipher = new CredentialCipher(KEY);
        String encrypted = cipher.encrypt("secret");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01;

        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(Base64.getEncoder().encodeToString(raw)));
    }

    @Test
    void unavailableWithoutKeyAndRefusesToEncrypt() {
        CredentialCipher cipher = new CredentialCipher("");

        assertFalse(cipher.available());
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> cipher.encrypt("x"));
        assertTrue(exception.getMessage().contains("NOTIFICATION_ENC_KEY"),
                "the failure must name the variable: " + exception.getMessage());
    }

    @Test
    void rejectsWrongSizedKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThrows(IllegalStateException.class, () -> new CredentialCipher(shortKey));
    }
}
