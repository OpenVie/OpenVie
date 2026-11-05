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

package com.openvie.api.bizapp.service;

import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.dto.BizAppDtos.InstallResult;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.document.api.DocumentApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-RUNTIME-API")
public class BizAppRuntimeApiImpl implements BizAppRuntimeApi {

    private final BizAppInstallationRepository installationRepository;
    private final BizAppInstallService installService;
    private final BizAppMarketplaceService marketplaceService;
    private final BizAppDraftService draftService;
    private final BizAppRecordService recordService;
    private final BizAppManifestParser parser;
    private final DocumentApi documentApi;
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private RecentTurnsProvider recentTurnsProvider;

    @Override
    @Transactional(readOnly = true)
    public List<InstalledAppSummary> listInstalledSummary(UUID tenantId) {
        List<BizAppInstallation> installed = installationRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "ACTIVE");
        return installed.stream().map(inst -> {
            Manifest manifest = parser.parse(inst.getManifest());
            String name = manifest.name() == null ? inst.getKind() : manifest.name().en();
            String desc = "";
            List<String> entities = manifest.entities() == null ? List.of() : manifest.entities().stream().map(Entity::name).toList();
            List<String> views = manifest.ui() != null && manifest.ui().views() != null ? manifest.ui().views() : List.of();
            List<EntityDetail> entityDetails = extractEntityDetails(manifest);
            List<String> triggers = manifest.conversational() != null ? manifest.conversational().triggers() : List.of();
            if (triggers.isEmpty() || entityDetails.stream().allMatch(e -> e.fields().stream().allMatch(f -> f.optionAliases().isEmpty()))) {
                try {
                    Map<String, Object> raw = marketplaceService.templateManifest(null, "platform:" + inst.getKind());
                    if (raw != null) {
                        Manifest diskManifest = parser.parse(raw);
                        if (triggers.isEmpty() && diskManifest.conversational() != null && diskManifest.conversational().triggers() != null) {
                            triggers = diskManifest.conversational().triggers();
                        }
                        if (entityDetails.stream().allMatch(e -> e.fields().stream().allMatch(f -> f.optionAliases().isEmpty()))) {
                            entityDetails = extractEntityDetails(diskManifest);
                        }
                    }
                } catch (Exception ignored) {}
            }
            return new InstalledAppSummary(inst.getId(), inst.getKind(), name, inst.getVersion(), desc, entities, views, inst.getStatus(), entityDetails, triggers);
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarketplaceTemplateSummary> listMarketplaceSummary(UUID tenantId) {
        return marketplaceService.catalog(null).stream().map(tmpl -> {
            String kind = tmpl.kind();
            String name = tmpl.name();
            int version = tmpl.version();
            String desc = tmpl.summary() != null ? tmpl.summary() : "";
            String templateRef = "platform:" + kind;
            List<EntityDetail> entityDetails = List.of();
            List<String> triggers = List.of();
            try {
                Map<String, Object> raw = marketplaceService.templateManifest(null, templateRef);
                if (raw != null) {
                    Manifest tmplManifest = parser.parse(raw);
                    entityDetails = extractEntityDetails(tmplManifest);
                    if (tmplManifest.conversational() != null) {
                        triggers = tmplManifest.conversational().triggers();
                    }
                }
            } catch (Exception ignored) {}
            List<String> entities = entityDetails.stream().map(EntityDetail::name).toList();
            return new MarketplaceTemplateSummary(kind, name, version, desc, entities, List.of(), templateRef, entityDetails, triggers);
        }).toList();
    }

    private List<EntityDetail> extractEntityDetails(Manifest manifest) {
        if (manifest.entities() == null) {
            return List.of();
        }
        return manifest.entities().stream().map(e -> {
            String label = e.label() != null ? (e.label().vi() != null ? e.label().vi() : e.label().en()) : e.name();
            List<FieldDetail> fields = e.fields() == null ? List.of() : e.fields().stream().map(f -> {
                String fLabel = f.label() != null ? (f.label().vi() != null ? f.label().vi() : f.label().en()) : f.name();
                boolean req = Boolean.TRUE.equals(f.required());
                List<String> options = f.options() != null ? List.copyOf(f.options()) : List.of();
                Map<String, String> optionDisplayLabels = new LinkedHashMap<>();
                Map<String, List<String>> optionAliases = new LinkedHashMap<>();
                if (f.optionLabels() != null) {
                    f.optionLabels().forEach((optKey, optDetail) -> {
                        if (optDetail != null) {
                            if (optDetail.label() != null) {
                                optionDisplayLabels.put(optKey, optDetail.label());
                            }
                            if (optDetail.aliases() != null && !optDetail.aliases().isEmpty()) {
                                optionAliases.put(optKey, optDetail.aliases());
                            }
                        }
                    });
                }
                return new FieldDetail(f.name(), f.type() != null ? f.type() : "string", req, fLabel, options, f.format(), optionDisplayLabels, optionAliases);
            }).toList();
            return new EntityDetail(e.name(), label, fields);
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AppActionOffer offerInstall(UUID tenantId, UUID orgId, String appKind, UUID actorId) {
        var templates = listMarketplaceSummary(tenantId);
        var tmplOpt = templates.stream().filter(t -> t.kind().equalsIgnoreCase(appKind)).findFirst();
        String name = tmplOpt.map(MarketplaceTemplateSummary::name).orElse(appKind);
        String desc = tmplOpt.map(MarketplaceTemplateSummary::description).orElse("");
        int version = tmplOpt.map(MarketplaceTemplateSummary::version).orElse(1);
        String templateRef = tmplOpt.map(MarketplaceTemplateSummary::templateRef).orElse("platform:" + appKind);
        return new AppActionOffer(
                UUID.randomUUID(),
                "bizapp.install",
                appKind,
                name,
                version,
                desc,
                "ORG_OWNER",
                templateRef
        );
    }

    @Override
    @Transactional(readOnly = true)
    public AppActionOffer offerUninstall(UUID tenantId, String appKind, UUID actorId) {
        var installed = listInstalledSummary(tenantId);
        var instOpt = installed.stream().filter(i -> i.kind().equalsIgnoreCase(appKind)).findFirst();
        String name = instOpt.map(InstalledAppSummary::name).orElse(appKind);
        int version = instOpt.map(InstalledAppSummary::version).orElse(1);
        String desc = instOpt.map(InstalledAppSummary::description).orElse("");
        return new AppActionOffer(
                UUID.randomUUID(),
                "bizapp.uninstall",
                appKind,
                name,
                version,
                desc,
                "ORG_OWNER",
                null
        );
    }

    @Override
    @Transactional
    public InstallOutcome executeOffer(UUID tenantId, UUID orgId, UUID actorId, AppActionOffer offer) {
        if ("bizapp.install".equalsIgnoreCase(offer.kind())) {
            String templateRef = offer.templateRef() != null && !offer.templateRef().isBlank()
                    ? offer.templateRef()
                    : "platform:" + offer.appKind();
            Map<String, Object> manifestMap = marketplaceService.templateManifest(orgId, templateRef);
            InstallResult result = installService.install(tenantId, orgId, actorId, manifestMap);
            return new InstallOutcome(true, result.installationId(), "Successfully installed " + offer.appName());
        } else if ("bizapp.uninstall".equalsIgnoreCase(offer.kind())) {
            installService.uninstall(tenantId, actorId, offer.appKind());
            return new InstallOutcome(true, null, "Successfully uninstalled " + offer.appName());
        }
        return new InstallOutcome(false, null, "Unsupported action offer: " + offer.kind());
    }

    @Override
    @Transactional
    public DraftOutcome createDraftFromSession(UUID tenantId, UUID orgId, UUID actorId, UUID sessionId, String hint) {
        List<RecentTurn> turns = recentTurnsProvider != null
                ? recentTurnsProvider.fetchRecentTurns(tenantId, actorId, sessionId, 20)
                : List.of();
        List<String> formattedTurns = turns.stream()
                .map(t -> t.role() + ": " + t.content())
                .toList();
        var resp = draftService.generateDraftFromChat(orgId, tenantId, actorId, sessionId, hint, formattedTurns);
        String title = resp.meta() != null && resp.meta().get("title") != null
                ? String.valueOf(resp.meta().get("title"))
                : "Generated Draft";
        return new DraftOutcome(resp.validation().ok(), resp.draftId(), title, List.of(), resp.validation().errors());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listProjectedDocumentIds(UUID tenantId, String appKind) {
        return documentApi.listProjectedDocumentIds(tenantId, appKind);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listProjectedDocumentIds(UUID tenantId, String appKind, String entity) {
        return documentApi.listProjectedDocumentIds(tenantId, appKind, entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppSchemaSummary> listAppSchemas(UUID tenantId) {
        List<BizAppInstallation> installed = installationRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "ACTIVE");
        List<AppSchemaSummary> list = new java.util.ArrayList<>();
        for (BizAppInstallation inst : installed) {
            Manifest manifest = parser.parse(inst.getManifest());
            var digested = BizAppSchemaDigest.digest(manifest, null);
            if (digested != null) {
                list.add(digested);
            }
        }
        list.sort(java.util.Comparator.comparing(AppSchemaSummary::kind));
        return List.copyOf(list);
    }

    @Override
    @Transactional
    public RecordCommandOutcome executeRecordCommand(
            UUID tenantId,
            UUID orgId,
            UUID actorId,
            String callerRole,
            RecordCommand command
    ) {
        if (command == null || command.kind() == null) {
            return new RecordCommandOutcome(false, null, "Invalid command");
        }
        var installation = installationRepository.findByTenantIdAndKindAndStatus(tenantId, command.appKind(), "ACTIVE")
                .orElseThrow(() -> new com.openvie.api.common.exception.custom.ResourceNotFoundException("App " + command.appKind() + " is not active"));
        Manifest manifest = parser.parse(installation.getManifest());
        boolean isAdmin = "ORG_OWNER".equalsIgnoreCase(callerRole) || "WORKSPACE_ADMIN".equalsIgnoreCase(callerRole);

        switch (command.kind()) {
            case "bizapp.record.create" -> {
                if (!recordService.canWrite(manifest, callerRole)) {
                    throw new org.springframework.security.access.AccessDeniedException("Manifest does not grant write permission to role " + callerRole);
                }
                var view = recordService.create(tenantId, actorId, isAdmin, command.appKind(), command.entity(), command.payload());
                String msg = "Successfully created record in " + command.entity();
                if (command.payload() != null && command.payload().get("name") != null) {
                    msg = "Successfully created " + command.payload().get("name") + " in " + command.entity();
                }
                return new RecordCommandOutcome(true, view.id(), msg);
            }
            case "bizapp.record.update" -> {
                if (!recordService.canWrite(manifest, callerRole)) {
                    throw new org.springframework.security.access.AccessDeniedException("Manifest does not grant write permission to role " + callerRole);
                }
                if (command.recordId() == null) {
                    throw new com.openvie.api.common.exception.custom.BadRequestException("recordId is required for record update");
                }
                var view = recordService.update(tenantId, actorId, isAdmin, command.appKind(), command.entity(), command.recordId(), command.payload());
                return new RecordCommandOutcome(true, view.id(), "Successfully updated record " + view.id());
            }
            case "bizapp.record.archive" -> {
                if (!recordService.canDelete(manifest, callerRole)) {
                    throw new org.springframework.security.access.AccessDeniedException("Manifest does not grant delete permission to role " + callerRole);
                }
                if (command.recordId() == null) {
                    throw new com.openvie.api.common.exception.custom.BadRequestException("recordId is required for record archive");
                }
                var view = recordService.archive(tenantId, actorId, isAdmin, command.appKind(), command.entity(), command.recordId());
                return new RecordCommandOutcome(true, view.id(), "Successfully archived record " + view.id());
            }
            case "bizapp.record.submit" -> {
                if (!recordService.canApprove(manifest, callerRole)) {
                    throw new org.springframework.security.access.AccessDeniedException("Manifest does not grant approve/submit permission to role " + callerRole);
                }
                if (command.recordId() == null) {
                    throw new com.openvie.api.common.exception.custom.BadRequestException("recordId is required for record submission");
                }
                var view = recordService.submit(tenantId, actorId, isAdmin, command.appKind(), command.entity(), command.recordId());
                return new RecordCommandOutcome(true, view.id(), "Successfully submitted record for approval");
            }
            default -> throw new com.openvie.api.common.exception.custom.ConflictException("Unsupported record command kind: " + command.kind());
        }
    }
}
