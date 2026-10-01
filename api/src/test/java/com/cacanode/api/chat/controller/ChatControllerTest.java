package com.cacanode.api.chat.controller;

import com.cacanode.api.chat.dto.ChatDtos;
import com.cacanode.api.chat.query.ChatControlPlaneService;
import com.cacanode.api.common.controller.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerTest extends BaseController {
    private final ChatControlPlaneService service = mock(ChatControlPlaneService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ChatController controller = new ChatController(service);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(request.getAttribute("tenantId")).thenReturn(tenantId.toString());
        when(request.getAttribute("userId")).thenReturn(userId.toString());
    }

    @Test
    void metadataHeadersAreReturnedWithoutChangingListBodies() {
        when(service.playgroundPage(tenantId, userId, 30, 0, null, null, null,
                null, null, null, null))
                .thenReturn(new ChatControlPlaneService.PlaygroundPage(List.of(), "opaque-token"));

        var playground = controller.playgroundResponse(30, 0, null, null, null,
                null, null, null, null, request);

        assertEquals("opaque-token", playground.getHeaders().getFirst("X-Next-Cursor"));
        assertEquals(List.of(), playground.getBody());
    }
}
