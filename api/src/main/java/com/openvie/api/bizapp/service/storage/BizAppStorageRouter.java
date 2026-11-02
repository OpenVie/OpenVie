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

package com.openvie.api.bizapp.service.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Routes BizApp records to either dedicated typed tables (for platform flagship apps)
 * or JSONB generic store (for org-custom and generic apps) (Workstream T).
 *
 * <p>Routing is determined strictly by the platform template registry, not by manifest content.
 */
@Component
@RequiredArgsConstructor
public class BizAppStorageRouter {

    private final JsonbRecordStore jsonbStore;
    private final CrmLeadsTypedStore crmLeadsStore;
    private final BookingAppointmentsTypedStore bookingStore;

    public BizAppStorageStore route(String appKind, String entity) {
        if ("crm".equalsIgnoreCase(appKind) && "leads".equalsIgnoreCase(entity)) {
            return crmLeadsStore;
        }
        if ("booking".equalsIgnoreCase(appKind) && "bookings".equalsIgnoreCase(entity)) {
            return bookingStore;
        }
        return jsonbStore;
    }

    public boolean isTyped(String appKind, String entity) {
        return ("crm".equalsIgnoreCase(appKind) && "leads".equalsIgnoreCase(entity))
                || ("booking".equalsIgnoreCase(appKind) && "bookings".equalsIgnoreCase(entity));
    }

    public String getTableName(String appKind, String entity) {
        if ("crm".equalsIgnoreCase(appKind) && "leads".equalsIgnoreCase(entity)) {
            return "bizapp_crm_leads";
        }
        if ("booking".equalsIgnoreCase(appKind) && "bookings".equalsIgnoreCase(entity)) {
            return "bizapp_booking_appointments";
        }
        return "bizapp_records";
    }
}
