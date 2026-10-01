package com.cacanode.api.document.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.cacanode.api.document.dto.DocumentDownloadResponse;
import com.cacanode.api.document.enums.DocumentStatus;
import com.cacanode.api.document.enums.DocumentType;
import com.cacanode.api.document.service.DocumentService;

import jakarta.servlet.http.HttpServletRequest;

class DocumentControllerTest {

    @Test
    void legacyListRemainsUnboundedWhenNoMobileParametersArePresent() {
        DocumentService service = mock(DocumentService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID tenantId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        when(request.getAttribute("tenantId")).thenReturn(tenantId.toString());
        when(service.list(tenantId, knowledgeBaseId)).thenReturn(List.of());

        var response = new DocumentController(service).list(
                knowledgeBaseId, null, null, null, null, null, request);

        assertTrue(response.isEmpty());
        verify(service).list(tenantId, knowledgeBaseId);
    }

    @Test
    void pagedListForwardsCombinedMobileFilters() {
        DocumentService service = mock(DocumentService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID tenantId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        when(request.getAttribute("tenantId")).thenReturn(tenantId.toString());
        when(service.list(
                tenantId, knowledgeBaseId, 2, 20, " policy ", DocumentStatus.COMPLETED,
                DocumentType.PDF)).thenReturn(List.of());

        var response = new DocumentController(service).list(
                knowledgeBaseId, 2, 20, " policy ", DocumentStatus.COMPLETED,
                DocumentType.PDF, request);

        assertTrue(response.isEmpty());
        verify(service).list(
                tenantId, knowledgeBaseId, 2, 20, " policy ", DocumentStatus.COMPLETED,
                DocumentType.PDF);
    }

    @Test
    void httpPagedListKeepsArrayBodyAndAddsTotalHeader() {
        DocumentService service = mock(DocumentService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID tenantId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        when(request.getAttribute("tenantId")).thenReturn(tenantId.toString());
        when(service.listResult(tenantId, knowledgeBaseId, 0, 20, "policy", null,
                null, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-20"),
                "uploaded", "desc"))
                .thenReturn(new DocumentService.DocumentListResult(List.of(), 125));

        var response = new DocumentController(service).listResponse(
                knowledgeBaseId, 0, 20, "policy", null, null,
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-20"),
                "uploaded", "desc", request);

        assertEquals("125", response.getHeaders().getFirst("X-Total-Count"));
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void downloadUsesAttachmentHeadersAndRequestTenant() {
        DocumentService service = mock(DocumentService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID tenantId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        byte[] content = "hello".getBytes();
        when(request.getAttribute("tenantId")).thenReturn(tenantId.toString());
        when(service.download(tenantId, documentId)).thenReturn(
                new DocumentDownloadResponse("notes.txt", "text/plain", content));

        var response = new DocumentController(service).download(documentId, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/plain", response.getHeaders().getContentType().toString());
        assertEquals(5, response.getHeaders().getContentLength());
        String contentDisposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(contentDisposition.startsWith("attachment;"));
        assertTrue(contentDisposition.contains("filename=\"notes.txt\""));
        assertArrayEquals(content, response.getBody());
        verify(service).download(tenantId, documentId);
    }
}
