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

package com.openvie.api.integration.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/** Published signature format shared by webhook and connector HMAC callers. */
public final class IntegrationSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private IntegrationSignature() {
    }

    /** {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "<timestamp>.<body>">}. */
    public static String signatureHeader(String secret, String unixTimestamp, String body) {
        return "t=" + unixTimestamp + ",v1="
                + hmacSha256Hex(secret, unixTimestamp + "." + body);
    }

    private static String hmacSha256Hex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
