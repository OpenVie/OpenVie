package com.openvie.api.notification.repository;

import com.openvie.api.notification.model.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {

    List<NotificationChannel> findByOrgIdOrderByCreatedAtAsc(UUID orgId);

    Optional<NotificationChannel> findByOrgIdAndType(UUID orgId, String type);

    boolean existsByOrgIdAndEnabledTrue(UUID orgId);

    boolean existsByOrgId(UUID orgId);
}
