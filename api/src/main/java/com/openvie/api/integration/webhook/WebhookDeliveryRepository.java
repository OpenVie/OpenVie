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

package com.openvie.api.integration.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findByEndpointIdOrderByCreatedAtDesc(UUID endpointId);

    /**
     * SKIP LOCKED keeps multiple API instances from racing on the same row;
     * the dispatcher walks due PENDING deliveries without double delivery.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "select d from WebhookDelivery d "
            + "where d.status = 'PENDING' and d.nextAttemptAt <= :now "
            + "order by d.nextAttemptAt asc")
    List<WebhookDelivery> lockDueDeliveries(LocalDateTime now, org.springframework.data.domain.Pageable pageable);
}
