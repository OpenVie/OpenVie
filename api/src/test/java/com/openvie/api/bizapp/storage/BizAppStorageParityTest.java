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

import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.bizapp.service.storage.JsonbRecordStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Verifies plug-and-play storage routing: All marketplace apps (bundled or custom)
 * use the universal JsonbRecordStore backed by bizapp_records.
 */
class BizAppStorageParityTest {

    @Test
    void storageRouterRoutesAllAppsUniversallyThroughJsonbRecordStore() {
        var jsonbStore = Mockito.mock(JsonbRecordStore.class);
        var router = new BizAppStorageRouter(jsonbStore);

        // 1. CRM routes to jsonbStore
        assertFalse(router.isTyped("crm", "leads"));
        assertEquals("bizapp_records", router.getTableName("crm", "leads"));
        assertEquals(jsonbStore, router.route("crm", "leads"));

        // 2. Booking routes to jsonbStore
        assertFalse(router.isTyped("booking", "bookings"));
        assertEquals("bizapp_records", router.getTableName("booking", "bookings"));
        assertEquals(jsonbStore, router.route("booking", "bookings"));

        // 3. Custom apps route to jsonbStore
        assertFalse(router.isTyped("custom-app", "orders"));
        assertEquals("bizapp_records", router.getTableName("custom-app", "orders"));
        assertEquals(jsonbStore, router.route("custom-app", "orders"));

        assertFalse(router.isTyped("warehouse", "items"));
        assertEquals("bizapp_records", router.getTableName("warehouse", "items"));
        assertEquals(jsonbStore, router.route("warehouse", "items"));
    }

    @Test
    void storageRouterFindAnyDelegatesToJsonbRecordStore() {
        var jsonbStore = Mockito.mock(JsonbRecordStore.class);
        var router = new BizAppStorageRouter(jsonbStore);

        UUID recordId = UUID.randomUUID();
        BizAppRecord expectedRecord = new BizAppRecord();
        expectedRecord.setId(recordId);
        expectedRecord.setAppKind("booking");

        when(jsonbStore.findById(null, "booking", recordId)).thenReturn(Optional.of(expectedRecord));

        Optional<BizAppRecord> result = router.findAny(recordId, "booking");
        assertTrue(result.isPresent());
        assertEquals(recordId, result.get().getId());
    }
}
