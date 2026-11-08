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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BizAppTaskContextProviderTest {

    @Test
    void buildsTitleSummaryAndFieldsFromTheInstalledManifest() throws Exception {
        UUID workspace = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        String manifest = """
                {"schema":"bizapp.v1","kind":"inventory",
                 "name":{"en":"Shopping","vi":"Mua sắm"},
                 "entities":[{"name":"shopping_requests","label":{"en":"Requests","vi":"Yêu cầu"},
                   "fields":[
                     {"name":"request_code","type":"string","label":{"vi":"Mã yêu cầu","en":"Code"}},
                     {"name":"item_name","type":"string","label":{"vi":"Món hàng","en":"Item"}},
                     {"name":"amount","type":"number","label":{"vi":"Số tiền","en":"Amount"}}]}]}
                """;
        BizAppInstallation installation = new BizAppInstallation();
        installation.setKind("inventory");
        installation.setStatus("ACTIVE");
        installation.setManifest(new ObjectMapper().readValue(manifest, Map.class));
        BizAppRecord record = new BizAppRecord();
        record.setTenantId(workspace);
        record.setAppKind("inventory");
        record.setEntity("shopping_requests");
        record.setPayload(Map.of("request_code", "REQ-1", "item_name", "Màn hình", "amount", 2));

        BizAppInstallationRepository installations = mock(BizAppInstallationRepository.class);
        BizAppRecordRepository records = mock(BizAppRecordRepository.class);
        when(installations.findByTenantIdAndKindAndStatus(workspace, "inventory", "ACTIVE"))
                .thenReturn(Optional.of(installation));
        when(records.findByIdAndTenantIdAndAppKind(recordId, workspace, "inventory"))
                .thenReturn(Optional.of(record));

        BizAppTaskContextProvider provider = new BizAppTaskContextProvider(
                installations, records, new BizAppManifestParser(new ObjectMapper()));

        var context = provider.describe(workspace, "inventory", "shopping_requests", recordId);
        assertEquals("REQ-1", context.title());
        assertTrue(context.summary().contains("Món hàng"));
        assertEquals("Màn hình", context.fields().get("Món hàng"));
    }
}
