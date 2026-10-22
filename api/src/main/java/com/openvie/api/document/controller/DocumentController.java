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

package com.openvie.api.document.controller;

import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.common.exception.ErrorResponse;
import com.openvie.api.document.dto.DocumentListItemResponse;
import com.openvie.api.document.dto.DocumentStatusResponse;
import com.openvie.api.document.dto.DocumentUploadResponse;
import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.openvie.api.document.service.DocumentService;
import com.openvie.api.ai.api.AiInferenceApi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Document ingestion, inspection, and deletion for the active workspace's knowledge base")
@SecurityRequirement(name = "bearerAuth")
public class DocumentController extends BaseController {

    private final DocumentService documentService;
    private final AiInferenceApi inferenceClient;

    @Autowired
    public DocumentController(DocumentService documentService, AiInferenceApi inferenceClient) {
        this.documentService = documentService;
        this.inferenceClient = inferenceClient;
    }

    public DocumentController(DocumentService documentService) {
        this(documentService, null);
    }

    public List<DocumentListItemResponse> list(
            @RequestParam("knowledgeBaseId") UUID knowledgeBaseId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "status", required = false) DocumentStatus status,
            @RequestParam(value = "type", required = false) DocumentType type,
            HttpServletRequest request
    ) {
        UUID tenantId = getTenantId(request);
        if (page == null && size == null && query == null && status == null && type == null) {
            return documentService.list(tenantId, knowledgeBaseId);
        }
        return documentService.list(
                tenantId,
                knowledgeBaseId,
                page,
                size,
                query,
                status,
                type
        );
    }

    @Operation(summary = "List documents of a knowledge base",
            description = "Returns the KB-scoped document list. Without query parameters the full list is returned; "
                    + "with `page`/`size` the list is server-paged and `X-Total-Count` carries the total. "
                    + "Search (`q`), status, type, and upload-date filters combine freely.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documents (paged or full) with X-Total-Count", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "INVALID_FILTER page/size out of bounds", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Knowledge base not in the caller's active workspace", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<List<DocumentListItemResponse>> listResponse(
            @RequestParam("knowledgeBaseId") UUID knowledgeBaseId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "status", required = false) DocumentStatus status,
            @RequestParam(value = "type", required = false) DocumentType type,
            @RequestParam(value = "uploaded_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedFrom,
            @RequestParam(value = "uploaded_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedTo,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "direction", required = false) String direction,
            HttpServletRequest request
    ) {
        UUID tenantId = getTenantId(request);
        if (page == null && size == null && query == null && status == null && type == null
                && uploadedFrom == null && uploadedTo == null
                && sort == null && direction == null) {
            List<DocumentListItemResponse> documents = documentService.list(tenantId, knowledgeBaseId);
            return ResponseEntity.ok().header("X-Total-Count", Long.toString(documents.size()))
                    .body(documents);
        }
        var result = documentService.listResult(
                tenantId, knowledgeBaseId, page, size, query, status, type,
                uploadedFrom, uploadedTo, sort, direction);
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.totalCount()))
                .body(result.documents());
    }

    @Operation(summary = "Upload a document into a knowledge base",
            description = "Accepts a supported file, stores the original, and publishes the ingestion request to the "
                    + "AI worker; indexing runs asynchronously and progress is readable through the status endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Upload accepted; indexing is in progress"),
            @ApiResponse(responseCode = "400", description = "Knowledge base not active or not found, unsupported file type, unreadable file, or size limit exceeded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller has no active workspace membership",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error while storing the file or publishing the ingestion request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("knowledgeBaseId") UUID knowledgeBaseId,
            HttpServletRequest request
    ) {
        DocumentUploadResponse response = documentService.upload(
                getTenantId(request),
                getUserId(request),
                knowledgeBaseId,
                file
        );
        return ResponseEntity.accepted().body(response);
    }

    @Operation(summary = "Get the status of one document",
            description = "Returns ingestion status, metadata, and the knowledge base the document belongs to; "
                    + "poll this endpoint while a fresh upload is still indexed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document status"),
            @ApiResponse(responseCode = "404", description = "Document not found in the caller's workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{documentId}")
    public DocumentStatusResponse get(
            @PathVariable UUID documentId,
            HttpServletRequest request
    ) {
        return documentService.get(getTenantId(request), documentId);
    }

    @Operation(summary = "Download the original file",
            description = "Streams the stored original with its upload-time content type and file name.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File bytes"),
            @ApiResponse(responseCode = "404", description = "Document not found in the caller's workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Download failed while reading the stored file",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{documentId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID documentId,
            HttpServletRequest request
    ) {
        var download = documentService.download(getTenantId(request), documentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.content());
    }

    @Operation(summary = "Load a citation image asset",
            description = "Serves a persisted image (embedded picture or standalone image document) "
                    + "referenced by chat citations in the sources panel.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image bytes"),
            @ApiResponse(responseCode = "400", description = "The media identifier is malformed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Document or image asset not found in the caller's workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Media read failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{documentId}/media/{mediaId}")
    public ResponseEntity<byte[]> media(
            @PathVariable UUID documentId,
            @PathVariable String mediaId,
            HttpServletRequest request
    ) {
        var download = documentService.downloadMedia(getTenantId(request), documentId, mediaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.content().length)
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(Duration.ofDays(7))
                        .cachePublic()
                        .getHeaderValue())
                .body(download.content());
    }

    @Operation(summary = "List the indexed units of a document",
            description = "Returns the chunk/unit decomposition stored in the AI index (used by the viewer's citation focus).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Indexed units"),
            @ApiResponse(responseCode = "404", description = "Document not found in the caller's workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "The AI index metadata could not be read; body is `{\"detail\": \"...\"}`")
    })
    @GetMapping("/{documentId}/units")
    public List<AiInferenceApi.DocumentUnit> units(
            @PathVariable UUID documentId,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            HttpServletRequest request
    ) {
        UUID tenantId = getTenantId(request);
        var document = documentService.get(tenantId, documentId);
        if (inferenceClient == null) {
            throw new IllegalStateException("AI inference client is unavailable");
        }
        return inferenceClient.listDocumentUnits(
                tenantId, document.knowledgeBaseId(), documentId,
                requestId == null ? UUID.randomUUID().toString() : requestId);
    }

    @Operation(summary = "Delete a document",
            description = "Removes the database record and requests de-indexing from the AI worker. Callers may only "
                    + "delete documents they uploaded; workspace admins may delete any document. "
                    + "Deletion is refused while the document is still processing.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Document deleted"),
            @ApiResponse(responseCode = "400", description = "The document is still being processed (PENDING/PROCESSING)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller did not upload the document and is not a workspace admin",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Document not found in the caller's workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID documentId,
            HttpServletRequest request
    ) {
        documentService.delete(getTenantId(request), getUserId(request),
                isWorkspaceAdmin(request), documentId);
        return ResponseEntity.noContent().build();
    }
}
