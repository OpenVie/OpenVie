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

import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JsonbRecordStore implements BizAppStorageStore {

    private final BizAppRecordRepository recordRepository;

    @Override
    public Optional<BizAppRecord> findById(UUID tenantId, String appKind, UUID recordId) {
        return recordRepository.findById(recordId)
                .filter(row -> (tenantId == null || row.getTenantId().equals(tenantId))
                        && (appKind == null || appKind.isBlank() || row.getAppKind().equalsIgnoreCase(appKind)));
    }

    @Override
    public Page<BizAppRecord> list(UUID tenantId, String appKind, String entity, boolean includeArchived, Pageable pageable) {
        return includeArchived
                ? recordRepository.findByTenantIdAndAppKindAndEntityOrderByUpdatedAtDesc(tenantId, appKind, entity, pageable)
                : recordRepository.findByTenantIdAndAppKindAndEntityAndStatusOrderByUpdatedAtDesc(tenantId, appKind, entity, "ACTIVE", pageable);
    }

    @Override
    public BizAppRecord save(BizAppRecord record) {
        return recordRepository.save(record);
    }
}
