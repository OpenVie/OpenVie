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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppDtos.CreateCredentialRequest;
import com.openvie.api.bizapp.model.BizAppCredential;
import com.openvie.api.bizapp.repository.BizAppCredentialRepository;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

class BizAppCredentialServiceTest {

    private BizAppCredentialRepository repository;
    private BizAppCredentialCipher cipher;
    private BizAppRuntime runtime;
    private BizAppCredentialService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(BizAppCredentialRepository.class);
        cipher = mock(BizAppCredentialCipher.class);
        runtime = mock(BizAppRuntime.class);
        when(cipher.available()).thenReturn(true);
        when(cipher.encrypt(any())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
        when(repository.save(any())).thenAnswer(invocation -> {
            BizAppCredential credential = invocation.getArgument(0);
            credential.setId(UUID.randomUUID());
            return credential;
        });
        service = new BizAppCredentialService(repository, cipher, runtime,
                new BizAppProperties(true, false, false, false, ""));
    }

    @Test
    void storesApiKeySecretAndWorkspaceScope() {
        when(repository.findFirstByOrgIdAndNameAndTenantIdIsNull(orgId, "gateway"))
                .thenReturn(Optional.empty());

        var view = service.create(orgId, tenantId, actorId,
                new CreateCredentialRequest("gateway", "api_key", "secret-value", null, tenantId));

        ArgumentCaptor<BizAppCredential> saved = ArgumentCaptor.forClass(BizAppCredential.class);
        verify(repository).save(saved.capture());
        assertEquals("enc:secret-value", saved.getValue().getEncryptedData());
        assertEquals(tenantId, saved.getValue().getTenantId());
        assertEquals(tenantId, view.workspaceId());
        assertEquals("api_key", view.format());
    }

    @Test
    void joinsUserAndSecretForBasicOauth2AndMqtt() {
        when(repository.findByOrgIdAndTenantIdAndName(eq(orgId), eq(tenantId), any()))
                .thenReturn(Optional.empty());

        service.create(orgId, tenantId, actorId,
                new CreateCredentialRequest("basic-one", "basic", "pass", "user", tenantId));
        service.create(orgId, tenantId, actorId,
                new CreateCredentialRequest("idp-one", "oauth2", "client-secret", "client-id", tenantId));
        service.create(orgId, tenantId, actorId,
                new CreateCredentialRequest("mqtt-one", "mqtt", "broker-pass", "broker-user", tenantId));

        ArgumentCaptor<BizAppCredential> saved = ArgumentCaptor.forClass(BizAppCredential.class);
        verify(repository, org.mockito.Mockito.times(3)).save(saved.capture());
        assertEquals("enc:user:pass", saved.getAllValues().get(0).getEncryptedData());
        assertEquals("enc:client-id:client-secret", saved.getAllValues().get(1).getEncryptedData());
        assertEquals("enc:broker-user:broker-pass", saved.getAllValues().get(2).getEncryptedData());
    }

    @Test
    void checksUniquenessInTheCredentialWorkspaceScope() {
        when(repository.findByOrgIdAndTenantIdAndName(orgId, tenantId, "gateway"))
                .thenReturn(Optional.of(new BizAppCredential()));

        var error = assertThrows(ConflictException.class, () -> service.create(orgId, tenantId, actorId,
                new CreateCredentialRequest("gateway", "api_key", "secret", null, tenantId)));
        assertEquals("Credential name is already taken in this organization", error.getMessage());
        verify(repository, never()).findFirstByOrgIdAndNameAndTenantIdIsNull(any(), any());
    }

    @Test
    void rejectsPairFormatsWithoutUser() {
        var error = assertThrows(BadRequestException.class, () -> service.create(orgId, tenantId, actorId,
                new CreateCredentialRequest("idp", "oauth2", "secret", " ", tenantId)));
        assertEquals("credential format oauth2 requires user (username or client id)", error.getMessage());
    }

    @Test
    void rejectsUnknownFormat() {
        var error = assertThrows(BadRequestException.class, () -> service.create(orgId, tenantId, actorId,
                new CreateCredentialRequest("legacy", "certificate", "secret", null, null)));
        assertEquals("credential format must be one of [api_key, bearer, basic, oauth2, mqtt, hmac]",
                error.getMessage());
    }
}
