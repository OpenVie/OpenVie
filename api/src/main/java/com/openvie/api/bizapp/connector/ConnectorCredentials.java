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

package com.openvie.api.bizapp.connector;

import com.openvie.api.bizapp.model.BizAppCredential;
import com.openvie.api.bizapp.repository.BizAppCredentialRepository;
import com.openvie.api.bizapp.service.BizAppCredentialCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves a manifest {@code credentialRef} (vault id or workspace-visible
 * name) to its decrypted secret plus format. Workspace-scoped rows are
 * invisible to other workspaces; org-wide rows serve every workspace.
 */
@Component
@RequiredArgsConstructor
public class ConnectorCredentials {

    private final BizAppCredentialRepository credentialRepository;
    private final BizAppCredentialCipher cipher;

    /** Decrypted credential material; {@code format} decides the auth header shape. */
    public record Resolved(String format, String plaintext) {
    }

    public Resolved resolve(UUID orgId, UUID tenantId, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (!cipher.available()) {
            throw ConnectorException.permanent(
                    "stored BizApp credentials require BIZAPP_ENC_KEY (32 random bytes, base64)");
        }
        BizAppCredential credential = find(orgId, tenantId, ref.trim());
        if (credential == null) {
            throw ConnectorException.permanent("credential " + ref + " not found in org vault");
        }
        return new Resolved(credential.getFormat(), cipher.decrypt(credential.getEncryptedData()));
    }

    private BizAppCredential find(UUID orgId, UUID tenantId, String ref) {
        UUID id = parseUuid(ref);
        if (id != null) {
            BizAppCredential credential = credentialRepository.findByIdAndOrgId(id, orgId).orElse(null);
            if (credential == null) {
                return null;
            }
            if (credential.getTenantId() != null && !credential.getTenantId().equals(tenantId)) {
                throw ConnectorException.permanent(
                        "credential " + ref + " is not visible in this workspace");
            }
            return credential;
        }
        BizAppCredential scoped = tenantId == null ? null
                : credentialRepository.findByOrgIdAndTenantIdAndName(orgId, tenantId, ref).orElse(null);
        return scoped != null ? scoped
                : credentialRepository.findFirstByOrgIdAndNameAndTenantIdIsNull(orgId, ref).orElse(null);
    }

    private UUID parseUuid(String ref) {
        try {
            return UUID.fromString(ref);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }
}
