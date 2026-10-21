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
