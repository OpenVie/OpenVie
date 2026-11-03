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

package com.openvie.api.bizapp.controller;

import com.openvie.api.bizapp.dto.BizAppDtos.DraftView;
import com.openvie.api.bizapp.dto.BizAppDtos.GenerateDraftRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.GenerateDraftResponse;
import com.openvie.api.bizapp.dto.BizAppDtos.SaveDraftRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.ValidateManifestRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.ValidationResult;
import com.openvie.api.bizapp.service.BizAppDraftService;
import com.openvie.api.common.controller.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "BizApp Drafts", description = "AI drafting and visual composer draft operations (ORG_OWNER only)")
@RestController
@RequestMapping("/api/v1/bizapps")
@RequiredArgsConstructor
public class BizAppDraftController extends BaseController {

    private final BizAppDraftService draftService;

    @Operation(summary = "Stateless manifest validation", description = "Validates a manifest against bizapp.v1 without saving.")
    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validate(
            @RequestBody ValidateManifestRequest request,
            HttpServletRequest httpReq) {
        requireOrgOwner(httpReq);
        return ResponseEntity.ok(draftService.validateStateless(request.manifest()));
    }

    @Operation(summary = "Generate manifest draft via AI", description = "Drafts a new manifest using the configured LLM.")
    @PostMapping("/drafts/generate")
    public ResponseEntity<GenerateDraftResponse> generateDraft(
            @Valid @RequestBody GenerateDraftRequest request,
            HttpServletRequest httpReq) {
        requireOrgOwner(httpReq);
        var resp = draftService.generateDraft(getOrgId(httpReq), getTenantId(httpReq), getUserId(httpReq), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @Operation(summary = "List drafts of organization")
    @GetMapping("/drafts")
    public ResponseEntity<List<DraftView>> listDrafts(HttpServletRequest httpReq) {
        requireOrgOwner(httpReq);
        return ResponseEntity.ok(draftService.listDrafts(getOrgId(httpReq)));
    }

    @Operation(summary = "Get one draft by id")
    @GetMapping("/drafts/{id}")
    public ResponseEntity<DraftView> getDraft(@PathVariable UUID id, HttpServletRequest httpReq) {
        requireOrgOwner(httpReq);
        return ResponseEntity.ok(draftService.getDraft(getOrgId(httpReq), id));
    }

    @Operation(summary = "Save or update a draft (from canvas or editor)")
    @PutMapping("/drafts/{id}")
    public ResponseEntity<DraftView> updateDraft(
            @PathVariable UUID id,
            @Valid @RequestBody SaveDraftRequest request,
            HttpServletRequest httpReq) {
        requireOrgOwner(httpReq);
        SaveDraftRequest reqWithId = new SaveDraftRequest(
                id, request.title(), request.source(), request.manifest(), request.meta());
        return ResponseEntity.ok(draftService.saveDraft(getOrgId(httpReq), getTenantId(httpReq), getUserId(httpReq), reqWithId));
    }

    @Operation(summary = "Save a brand-new draft (first save from the composer canvas)",
            description = "The body carries no id; the service creates the draft and returns it with its new id.")
    @PutMapping("/drafts/new")
    public ResponseEntity<DraftView> createDraft(
            @Valid @RequestBody SaveDraftRequest request,
            HttpServletRequest httpReq) {
        requireOrgOwner(httpReq);
        return ResponseEntity.ok(draftService.saveDraft(
                getOrgId(httpReq), getTenantId(httpReq), getUserId(httpReq), request));
    }

    @Operation(summary = "Delete a draft")
    @DeleteMapping("/drafts/{id}")
    public ResponseEntity<Void> deleteDraft(@PathVariable UUID id, HttpServletRequest httpReq) {
        requireOrgOwner(httpReq);
        draftService.deleteDraft(getOrgId(httpReq), getTenantId(httpReq), getUserId(httpReq), id);
        return ResponseEntity.noContent().build();
    }

    private void requireOrgOwner(HttpServletRequest request) {
        if (!"ORG_OWNER".equals(getOrgRole(request))) {
            throw new AccessDeniedException("Only the organization owner may manage BizApp drafts");
        }
    }
}
