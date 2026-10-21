package com.openvie.api.integration.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    List<WebhookEndpoint> findByOrgIdAndStatusAndSecretEncryptedIsNotNull(
            UUID orgId, String status);

    List<WebhookEndpoint> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
