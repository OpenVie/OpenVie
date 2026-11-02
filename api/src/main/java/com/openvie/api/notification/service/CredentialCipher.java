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

package com.openvie.api.notification.service;

import com.openvie.api.common.security.GeneralCredentialsCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM envelope for stored notification-channel credentials.
 *
 * <p>The key comes from {@code NOTIFICATION_ENC_KEY} (32 bytes, base64). When
 * it is absent, database-stored channels are unavailable and the API reports
 * that precisely; the environment-configured bootstrap path keeps working, so
 * an installation is never forced to set a second secret it does not need.
 * The envelope itself lives in the shared {@link GeneralCredentialsCipher}.
 */
@Component
public class CredentialCipher {

    private final GeneralCredentialsCipher cipher;

    public CredentialCipher(@Value("${app.notifications.encryption-key:}") String encodedKey) {
        this.cipher = new GeneralCredentialsCipher(encodedKey, "NOTIFICATION_ENC_KEY");
    }

    /** True when a usable key was configured. */
    public boolean available() {
        return cipher.available();
    }

    public String encrypt(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    public String decrypt(String envelope) {
        return cipher.decrypt(envelope);
    }
}
