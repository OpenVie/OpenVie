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

package com.openvie.api.bizapp.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.bizapp.service.storage.BizAppStorageStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class BizAppStorageParityTest {

    @Test
    void storageRouterIdentifiesTypedTablesCorrectly() {
        var jsonbStore = Mockito.mock(com.openvie.api.bizapp.service.storage.JsonbRecordStore.class);
        var crmStore = Mockito.mock(com.openvie.api.bizapp.service.storage.CrmLeadsTypedStore.class);
        var bookingStore = Mockito.mock(com.openvie.api.bizapp.service.storage.BookingAppointmentsTypedStore.class);

        var router = new BizAppStorageRouter(jsonbStore, crmStore, bookingStore);

        assertTrue(router.isTyped("crm", "leads"));
        assertEquals("bizapp_crm_leads", router.getTableName("crm", "leads"));
        assertEquals(crmStore, router.route("crm", "leads"));

        assertTrue(router.isTyped("booking", "bookings"));
        assertEquals("bizapp_booking_appointments", router.getTableName("booking", "bookings"));
        assertEquals(bookingStore, router.route("booking", "bookings"));

        assertFalse(router.isTyped("custom-app", "orders"));
        assertEquals("bizapp_records", router.getTableName("custom-app", "orders"));
        assertEquals(jsonbStore, router.route("custom-app", "orders"));
    }

    @Test
    void crmLeadsTypedStoreMaintainsFieldParityWithJsonbPayload() {
        var mockRepo = Mockito.mock(com.openvie.api.bizapp.repository.BizAppCrmLeadRepository.class);
        var store = new com.openvie.api.bizapp.service.storage.CrmLeadsTypedStore(mockRepo);

        UUID orgId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Acme Corp");
        payload.put("email", "contact@acme.test");
        payload.put("phone", "0901234567");
        payload.put("stage", "QUALIFIED");
        payload.put("dealValue", 1500000.0);
        payload.put("notes", "VIP client");

        BizAppRecord input = new BizAppRecord();
        input.setId(recordId);
        input.setOrgId(orgId);
        input.setTenantId(tenantId);
        input.setAppKind("crm");
        input.setEntity("leads");
        input.setStatus("ACTIVE");
        input.setCreatedBy(userId);
        input.setPayload(payload);

        Mockito.when(mockRepo.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

        BizAppRecord saved = store.save(input);

        assertNotNull(saved);
        assertEquals(recordId, saved.getId());
        assertEquals("crm", saved.getAppKind());
        assertEquals("leads", saved.getEntity());
        assertEquals("Acme Corp", saved.getPayload().get("name"));
        assertEquals("contact@acme.test", saved.getPayload().get("email"));
        assertEquals("QUALIFIED", saved.getPayload().get("stage"));
        assertEquals("VIP client", saved.getPayload().get("notes"));
    }

    @Test
    void bookingAppointmentsTypedStoreMaintainsFieldParityWithJsonbPayload() {
        var mockRepo = Mockito.mock(com.openvie.api.bizapp.repository.BizAppBookingAppointmentRepository.class);
        var store = new com.openvie.api.bizapp.service.storage.BookingAppointmentsTypedStore(mockRepo);

        UUID orgId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerName", "Nguyen Van A");
        payload.put("customerEmail", "nva@example.com");
        payload.put("service", "tour");
        payload.put("bookingDate", "2026-12-01");
        payload.put("guests", 4);
        payload.put("notes", "Need projector");

        BizAppRecord input = new BizAppRecord();
        input.setId(recordId);
        input.setOrgId(orgId);
        input.setTenantId(tenantId);
        input.setAppKind("booking");
        input.setEntity("bookings");
        input.setStatus("ACTIVE");
        input.setCreatedBy(userId);
        input.setPayload(payload);

        Mockito.when(mockRepo.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

        BizAppRecord saved = store.save(input);

        assertNotNull(saved);
        assertEquals(recordId, saved.getId());
        assertEquals("booking", saved.getAppKind());
        assertEquals("bookings", saved.getEntity());
        assertEquals("Nguyen Van A", saved.getPayload().get("customerName"));
        assertEquals("nva@example.com", saved.getPayload().get("customerEmail"));
        assertEquals("tour", saved.getPayload().get("service"));
        assertEquals("2026-12-01", saved.getPayload().get("bookingDate"));
        assertEquals(4, saved.getPayload().get("guests"));
    }
}
