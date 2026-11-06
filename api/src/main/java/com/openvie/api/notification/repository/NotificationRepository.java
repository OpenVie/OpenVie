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

package com.openvie.api.notification.repository;

import com.openvie.api.notification.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("SELECT n FROM Notification n WHERE n.tenantId = :tenantId AND n.userId = :userId ORDER BY n.createdAt DESC")
    Page<Notification> findForUser(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.tenantId = :tenantId AND n.userId = :userId AND n.readAt IS NULL")
    long countUnreadForUser(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.tenantId = :tenantId AND n.userId = :userId AND n.readAt IS NULL")
    int markAllAsRead(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.id = :id AND n.tenantId = :tenantId AND n.userId = :userId AND n.readAt IS NULL")
    int markAsRead(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    Optional<Notification> findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId);
}
