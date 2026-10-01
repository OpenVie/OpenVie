package com.cacanode.api.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {
    @Test
    void preservesResponseStatusExceptionStatusAndReason() {
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/v1/documents/8b5f6f9e/upload");

        var response = new GlobalExceptionHandler().handleResponseStatusException(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "DOCUMENT_ACCESS_DENIED"), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("DOCUMENT_ACCESS_DENIED", response.getBody().getMessage());
    }

}
