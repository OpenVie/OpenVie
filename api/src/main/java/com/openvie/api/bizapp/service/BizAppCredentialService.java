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

package com.openvie.api.bizapp.service;

import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppDtos.CredentialView;
import com.openvie.api.bizapp.dto.BizAppDtos.CreateCredentialRequest;
import com.openvie.api.bizapp.model.BizAppCredential;
import com.openvie.api.bizapp.repository.BizAppCredentialRepository;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.InternalServerErrorException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Organization-level outbound secret vault for BizApp blocks. Plaintext
 * exists only inside the create request; every read path returns metadata
 * only. Rows are org-scoped and referenced by id or name from manifests.
 */
@Service
@RequiredArgsConstructor
public class BizAppCredentialService {

    private static final List<String> FORMATS = List.of("api_key", "bearer", "basic");

    private final BizAppCredentialRepository repository;
    private final BizAppCredentialCipher cipher;
    private final BizAppRuntime runtime;
    private final BizAppProperties properties;

    @Transactional
    public CredentialView create(UUID orgId, UUID tenantId, UUID actorId, CreateCredentialRequest request) {
        BizAppCredentialService.requireEnabled(properties);
        String format = request.format() == null ? "" : request.format();
        if (!FORMATS.contains(format)) {
            throw new BadRequestException("credential format must be one of " + FORMATS);
        }
        if (repository.findByOrgIdAndName(orgId, request.name()).isPresent()) {
            throw new ConflictException("Credential name is already taken in this organization");
        }
        requireCipher(properties);
        BizAppCredential credential = new BizAppCredential();
        credential.setOrgId(orgId);
        credential.setName(request.name());
        credential.setFormat(format);
        String plaintext = "basic".equals(format)
                ? request.user() + ":" + request.secret()
                : request.secret();
        if (plaintext != null && plaintext.contains("-----BEGIN") && plaintext.contains("PRIVATE KEY-----")) {
            throw new BadRequestException("Private keys (PEM blocks) cannot be stored in vault; signing keys are never custodied");
        }
        credential.setEncryptedData(cipher.encrypt(plaintext));
        credential.setCreatedBy(actorId);
        BizAppCredential saved = repository.save(credential);

        runtime.audit(tenantId, actorId, LogAction.BIZAPP_CREDENTIAL_CREATED, "bizapp_credential",
                saved.getId(), Map.of("name", request.name(), "format", format));
        return new CredentialView(saved.getId(), saved.getName(), saved.getFormat(), saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<CredentialView> list(UUID orgId) {
        return repository.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(credential -> new CredentialView(
                        credential.getId(), credential.getName(), credential.getFormat(),
                        credential.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void delete(UUID orgId, UUID tenantId, UUID actorId, UUID credentialId) {
        requireEnabled(properties);
        BizAppCredential credential = repository.findByIdAndOrgId(credentialId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Credential not found"));
        repository.delete(credential);
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_CREDENTIAL_DELETED, "bizapp_credential",
                credentialId, Map.of("name", credential.getName()));
    }

    private static void requireEnabled(BizAppProperties properties) {
        if (!properties.enabled()) {
            throw new ResourceNotFoundException("BizApps are disabled on this installation");
        }
    }

    private void requireCipher(BizAppProperties properties) {
        requireEnabled(properties);
        if (!cipher.available()) {
            throw new InternalServerErrorException(
                    "Stored BizApp credentials require BIZAPP_ENC_KEY (32 random bytes, base64)."
                    + " Generate one with: openssl rand -base64 32");
        }
    }
}
