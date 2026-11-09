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

package com.openvie.api.notification.controller;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.notification.service.NotificationService;
import com.openvie.api.notification.service.NotificationService.NotificationView;
import com.openvie.api.notification.service.NotificationService.UnreadCountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Notifications", description = "In-app notifications for workspace members")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class NotificationController extends BaseController {

    private final NotificationService notificationService;

    @Operation(summary = "List notifications of the current user in active workspace")
    @ApiResponse(responseCode = "200", description = "Notifications page")
    @GetMapping
    public Page<NotificationView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return notificationService.listForUser(getTenantId(request), getUserId(request), page, size);
    }

    @Operation(summary = "Get unread notifications count for current user")
    @ApiResponse(responseCode = "200", description = "Unread count")
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(HttpServletRequest request) {
        return new UnreadCountResponse(notificationService.getUnreadCount(getTenantId(request), getUserId(request)));
    }

    @Operation(summary = "Mark a notification as read")
    @ApiResponse(responseCode = "204", description = "Marked as read")
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable UUID id, HttpServletRequest request) {
        notificationService.markAsRead(getTenantId(request), getUserId(request), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark all notifications as read for current user")
    @ApiResponse(responseCode = "204", description = "All marked as read")
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(HttpServletRequest request) {
        notificationService.markAllAsRead(getTenantId(request), getUserId(request));
        return ResponseEntity.noContent().build();
    }
}
