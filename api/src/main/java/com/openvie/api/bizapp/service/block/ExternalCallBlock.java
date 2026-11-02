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

package com.openvie.api.bizapp.service.block;

import com.openvie.api.bizapp.model.BizAppCredential;
import com.openvie.api.bizapp.repository.BizAppCredentialRepository;
import com.openvie.api.bizapp.service.BizAppCredentialCipher;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.InternalServerErrorException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound HTTP block. Secrets resolve by {@code credentialRef} out of the
 * organization's vault — decrypted just-in-time, attached as a header, and
 * never logged. Transport errors degrade to a warning-and-failed result: the
 * record write never depends on the remote system.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-BLOCK")
public class ExternalCallBlock implements BizAppBlockExecutor {

    public static final String TYPE = "external_call";

    private final BizAppCredentialRepository credentialRepository;
    private final BizAppCredentialCipher cipher;

    @Override
    public String supports() {
        return TYPE;
    }

    @Override
    public BlockResult execute(BlockSpec spec, BlockContext context) {
        String url = string(spec.params(), "url");
        if (url == null) {
            throw new BadRequestException("external_call block " + spec.blockId() + " has no url");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));
        RestClient client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        HttpMethod method = "GET".equalsIgnoreCase(string(spec.params(), "method"))
                ? HttpMethod.GET : HttpMethod.POST;
        try {
            RestClient.RequestBodySpec request = client.method(method).uri(URI.create(url));
            applyCredential(request, spec, context);
            RestClient.ResponseSpec response = method == HttpMethod.POST
                    ? request.body(body(spec, context)).retrieve()
                    : request.retrieve();
            int status = response.toBodilessEntity().getStatusCode().value();
            return new BlockResult("external_call " + spec.blockId() + " -> HTTP " + status);
        } catch (BadRequestException | ResourceNotFoundException | InternalServerErrorException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("external_call {} failed: {}", spec.blockId(), exception.getMessage());
            return new BlockResult("external_call " + spec.blockId() + " failed: " + exception.getMessage());
        }
    }

    private void applyCredential(RestClient.RequestBodySpec request, BlockSpec spec, BlockContext context) {
        String credentialRef = string(spec.params(), "credentialRef");
        if (credentialRef == null || credentialRef.isBlank()) {
            return;
        }
        if (!cipher.available()) {
            throw new InternalServerErrorException(
                    "Stored BizApp credentials require BIZAPP_ENC_KEY (32 random bytes, base64)."
                    + " Generate one with: openssl rand -base64 32");
        }
        UUID credentialId = parseRef(credentialRef);
        BizAppCredential credential = credentialRepository.findById(credentialId)
                .filter(row -> row.getOrgId().equals(context.orgId()))
                .orElseThrow(() -> new ResourceNotFoundException("Credential " + credentialRef + " not found"));
        String plaintext = cipher.decrypt(credential.getEncryptedData());
        switch (credential.getFormat()) {
            case "api_key" -> request.header("X-API-Key", plaintext);
            case "bearer" -> request.header("Authorization", "Bearer " + plaintext);
            case "basic" -> request.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8)));
            default -> throw new BadRequestException("Unsupported credential format " + credential.getFormat());
        }
    }

    /** {@code credentialRef} may be a vault row id or its `name`; both resolve org-scoped. */
    private UUID parseRef(String credentialRef) {
        try {
            return UUID.fromString(credentialRef);
        } catch (IllegalArgumentException notAnId) {
            throw new BadRequestException("credentialRef must be a credential id from the org vault");
        }
    }

    private Object body(BlockSpec spec, BlockContext context) {
        Object template = spec.params() == null ? null : spec.params().get("body");
        if (template instanceof Map<?, ?> map) {
            return new LinkedHashMap<>(map);
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("app", context.appKind());
        fallback.put("entity", context.entity());
        if (context.recordId() != null) {
            fallback.put("record_id", context.recordId().toString());
        }
        if (context.payload() != null) {
            fallback.put("record", context.payload());
        }
        return fallback;
    }

    private String string(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
