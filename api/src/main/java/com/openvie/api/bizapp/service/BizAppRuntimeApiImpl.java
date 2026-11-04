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
            return new InstalledAppSummary(inst.getId(), inst.getKind(), name, inst.getVersion(), desc, entities, views, inst.getStatus());
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
            return new MarketplaceTemplateSummary(kind, name, version, desc, List.of(), List.of(), templateRef);
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
            Map<String, Object> manifestMap = marketplaceService.templateManifest(orgId, offer.templateRef());
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
}
