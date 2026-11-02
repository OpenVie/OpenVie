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

import com.openvie.api.bizapp.model.BizAppCrmLead;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppCrmLeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CrmLeadsTypedStore implements BizAppStorageStore {

    private final BizAppCrmLeadRepository repository;

    @Override
    public Optional<BizAppRecord> findById(UUID tenantId, String appKind, UUID recordId) {
        return repository.findByIdAndTenantId(recordId, tenantId)
                .map(this::toRecord);
    }

    @Override
    public Page<BizAppRecord> list(UUID tenantId, String appKind, String entity, boolean includeArchived, Pageable pageable) {
        Page<BizAppCrmLead> rows = includeArchived
                ? repository.findByTenantIdOrderByUpdatedAtDesc(tenantId, pageable)
                : repository.findByTenantIdAndStatusOrderByUpdatedAtDesc(tenantId, "ACTIVE", pageable);
        return rows.map(this::toRecord);
    }

    @Override
    public BizAppRecord save(BizAppRecord record) {
        BizAppCrmLead lead = record.getId() != null
                ? repository.findById(record.getId()).orElseGet(BizAppCrmLead::new)
                : new BizAppCrmLead();

        if (record.getId() != null) {
            lead.setId(record.getId());
        }
        lead.setOrgId(record.getOrgId());
        lead.setTenantId(record.getTenantId());
        lead.setStatus(record.getStatus() != null ? record.getStatus() : "ACTIVE");
        lead.setRuntime(record.getRuntime() != null ? record.getRuntime() : Map.of());
        lead.setCreatedBy(record.getCreatedBy());
        lead.setUpdatedBy(record.getUpdatedBy());

        Map<String, Object> p = record.getPayload() != null ? record.getPayload() : Map.of();
        lead.setName(p.get("name") != null ? String.valueOf(p.get("name")) : "Unnamed Lead");
        lead.setEmail(p.get("email") != null ? String.valueOf(p.get("email")) : null);
        lead.setPhone(p.get("phone") != null ? String.valueOf(p.get("phone")) : null);
        lead.setStage(p.get("stage") != null ? String.valueOf(p.get("stage")) : "NEW");
        if (p.get("dealValue") instanceof Number num) {
            lead.setDealValue(BigDecimal.valueOf(num.doubleValue()));
        } else if (p.get("dealValue") != null) {
            try {
                lead.setDealValue(new BigDecimal(String.valueOf(p.get("dealValue"))));
            } catch (Exception ignored) {
                lead.setDealValue(null);
            }
        }
        lead.setNotes(p.get("notes") != null ? String.valueOf(p.get("notes")) : null);

        BizAppCrmLead saved = repository.save(lead);
        return toRecord(saved);
    }

    private BizAppRecord toRecord(BizAppCrmLead lead) {
        BizAppRecord r = new BizAppRecord();
        r.setId(lead.getId());
        r.setOrgId(lead.getOrgId());
        r.setTenantId(lead.getTenantId());
        r.setAppKind("crm");
        r.setEntity("leads");
        r.setStatus(lead.getStatus());
        r.setRuntime(lead.getRuntime());
        r.setCreatedBy(lead.getCreatedBy());
        r.setUpdatedBy(lead.getUpdatedBy());
        r.setCreatedAt(lead.getCreatedAt());
        r.setUpdatedAt(lead.getUpdatedAt());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", lead.getName());
        payload.put("email", lead.getEmail());
        payload.put("phone", lead.getPhone());
        payload.put("stage", lead.getStage());
        payload.put("dealValue", lead.getDealValue());
        payload.put("notes", lead.getNotes());
        r.setPayload(payload);

        return r;
    }
}
