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

import com.openvie.api.bootstrap.config.GlobalExceptionHandler;
import com.openvie.api.notification.service.NotificationService;
import com.openvie.api.notification.service.NotificationService.NotificationView;
import com.openvie.api.notification.service.NotificationService.UnreadCountResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listReturnsPagedNotifications() throws Exception {
        UUID notifId = UUID.randomUUID();
        when(service.listForUser(tenantId, userId, 0, 20)).thenReturn(
                new PageImpl<>(
                        List.of(
                                new NotificationView(
                                        notifId,
                                        "BOOKING_REMINDER",
                                        "Nhắc lịch hẹn",
                                        "Bạn có lịch hẹn",
                                        "/apps/booking",
                                        false,
                                        null,
                                        LocalDateTime.now()
                                )
                        ),
                        org.springframework.data.domain.PageRequest.of(0, 20),
                        1
                )
        );

        mvc.perform(get("/api/v1/notifications")
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(notifId.toString()))
                .andExpect(jsonPath("$.content[0].type").value("BOOKING_REMINDER"))
                .andExpect(jsonPath("$.content[0].title").value("Nhắc lịch hẹn"))
                .andExpect(jsonPath("$.content[0].read").value(false));
    }

    @Test
    void unreadCountReturnsCount() throws Exception {
        when(service.getUnreadCount(tenantId, userId)).thenReturn(5L);

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(5));
    }

    @Test
    void markAsReadReturnsNoContent() throws Exception {
        UUID notifId = UUID.randomUUID();

        mvc.perform(post("/api/v1/notifications/{id}/read", notifId)
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString()))
                .andExpect(status().isNoContent());

        verify(service).markAsRead(tenantId, userId, notifId);
    }

    @Test
    void markAllAsReadReturnsNoContent() throws Exception {
        mvc.perform(post("/api/v1/notifications/read-all")
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString()))
                .andExpect(status().isNoContent());

        verify(service).markAllAsRead(tenantId, userId);
    }
}
