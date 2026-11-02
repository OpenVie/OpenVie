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

import com.openvie.api.bizapp.model.BizAppBookingAppointment;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppBookingAppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class BookingAppointmentsTypedStore implements BizAppStorageStore {

    private final BizAppBookingAppointmentRepository repository;

    @Override
    public Optional<BizAppRecord> findById(UUID tenantId, String appKind, UUID recordId) {
        return repository.findByIdAndTenantId(recordId, tenantId)
                .map(this::toRecord);
    }

    @Override
    public Page<BizAppRecord> list(UUID tenantId, String appKind, String entity, boolean includeArchived, Pageable pageable) {
        Page<BizAppBookingAppointment> rows = includeArchived
                ? repository.findByTenantIdOrderByUpdatedAtDesc(tenantId, pageable)
                : repository.findByTenantIdAndStatusOrderByUpdatedAtDesc(tenantId, "ACTIVE", pageable);
        return rows.map(this::toRecord);
    }

    @Override
    public BizAppRecord save(BizAppRecord record) {
        BizAppBookingAppointment appt = record.getId() != null
                ? repository.findById(record.getId()).orElseGet(BizAppBookingAppointment::new)
                : new BizAppBookingAppointment();

        if (record.getId() != null) {
            appt.setId(record.getId());
        }
        appt.setOrgId(record.getOrgId());
        appt.setTenantId(record.getTenantId());
        appt.setStatus(record.getStatus() != null ? record.getStatus() : "ACTIVE");
        appt.setRuntime(record.getRuntime() != null ? record.getRuntime() : Map.of());
        appt.setCreatedBy(record.getCreatedBy());
        appt.setUpdatedBy(record.getUpdatedBy());

        Map<String, Object> p = record.getPayload() != null ? record.getPayload() : Map.of();
        appt.setCustomerName(p.get("customerName") != null ? String.valueOf(p.get("customerName")) : "Guest");
        appt.setCustomerEmail(p.get("customerEmail") != null ? String.valueOf(p.get("customerEmail")) : "guest@example.com");
        appt.setService(p.get("service") != null ? String.valueOf(p.get("service")) : "consultation");

        if (p.get("bookingDate") instanceof LocalDate ld) {
            appt.setBookingDate(ld);
        } else if (p.get("bookingDate") != null) {
            try {
                appt.setBookingDate(LocalDate.parse(String.valueOf(p.get("bookingDate"))));
            } catch (Exception ignored) {
                appt.setBookingDate(LocalDate.now());
            }
        } else {
            appt.setBookingDate(LocalDate.now());
        }

        if (p.get("guests") instanceof Number num) {
            appt.setGuests(num.intValue());
        } else if (p.get("guests") != null) {
            try {
                appt.setGuests(Integer.parseInt(String.valueOf(p.get("guests"))));
            } catch (Exception ignored) {
                appt.setGuests(null);
            }
        }
        appt.setNotes(p.get("notes") != null ? String.valueOf(p.get("notes")) : null);

        BizAppBookingAppointment saved = repository.save(appt);
        return toRecord(saved);
    }

    private BizAppRecord toRecord(BizAppBookingAppointment appt) {
        BizAppRecord r = new BizAppRecord();
        r.setId(appt.getId());
        r.setOrgId(appt.getOrgId());
        r.setTenantId(appt.getTenantId());
        r.setAppKind("booking");
        r.setEntity("bookings");
        r.setStatus(appt.getStatus());
        r.setRuntime(appt.getRuntime());
        r.setCreatedBy(appt.getCreatedBy());
        r.setUpdatedBy(appt.getUpdatedBy());
        r.setCreatedAt(appt.getCreatedAt());
        r.setUpdatedAt(appt.getUpdatedAt());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerName", appt.getCustomerName());
        payload.put("customerEmail", appt.getCustomerEmail());
        payload.put("service", appt.getService());
        payload.put("bookingDate", appt.getBookingDate() != null ? appt.getBookingDate().toString() : null);
        payload.put("guests", appt.getGuests());
        payload.put("notes", appt.getNotes());
        r.setPayload(payload);

        return r;
    }
}
