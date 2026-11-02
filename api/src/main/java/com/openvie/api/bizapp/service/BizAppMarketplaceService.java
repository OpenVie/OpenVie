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

import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppDtos.SaveTemplateRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.TemplateView;
import com.openvie.api.bizapp.model.BizAppMarketplaceTemplate;
import com.openvie.api.bizapp.repository.BizAppMarketplaceTemplateRepository;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two-layer marketplace, per Flowise's model: platform templates ship as JSON
 * under {@code resources/marketplace/bizapps/} and are served read-only;
 * organizations additionally save their installed manifests as reusable
 * templates scoped to the org. {@code platform:<kind>} template refs install a
 * bundled JSON; a UUID installs the org's own template row.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP")
public class BizAppMarketplaceService {

    private static final String BUNDLED_DIR = "marketplace/bizapps";

    private final BizAppMarketplaceTemplateRepository templateRepository;
    private final BizAppMaterializer materializer;
    private final BizAppManifestParser parser;
    private final BizAppManifestValidator validator;
    private final BizAppRuntime runtime;
    private final BizAppProperties properties;

    // ------------------------------------------------------------- catalog

    /** Bundled + org templates in one view; install failures never hide the catalog. */
    @Transactional(readOnly = true)
    public List<TemplateView> catalog(UUID orgId) {
        List<TemplateView> result = new ArrayList<>();
        for (Resource resource : bundledResources()) {
            materializer.readOptional(resource)
                    .ifPresent(raw -> result.add(platformView(raw)));
        }
        templateRepository.findByOrgIdOrderByCreatedAtDesc(orgId)
                .forEach(template -> result.add(orgView(template)));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> templateManifest(UUID orgId, String templateRef) {
        return materializer.loadRaw(orgId, templateRef);
    }

    // ------------------------------------------------------------ custom

    @Transactional
    public TemplateView saveCustom(UUID orgId, UUID tenantId, UUID actorId, SaveTemplateRequest request) {
        requireEnabled();
        var manifest = parser.parse(request.manifest());
        validator.validate(manifest);
        BizAppMarketplaceTemplate existing = templateRepository
                .findByOrgIdAndKindAndName(orgId, manifest.kind(), request.name())
                .orElse(null);
        if (existing == null) {
            existing = new BizAppMarketplaceTemplate();
            existing.setOrgId(orgId);
            existing.setKind(manifest.kind());
            existing.setCreatedBy(actorId);
        }
        existing.setName(request.name());
        existing.setSummary(request.summary());
        existing.setVersion(manifest.version() == null ? 1 : manifest.version());
        existing.setManifest(request.manifest());
        BizAppMarketplaceTemplate saved = templateRepository.save(existing);

        runtime.audit(tenantId, actorId, LogAction.BIZAPP_TEMPLATE_SAVED, "bizapp_template",
                saved.getId(), Map.of("app", manifest.kind(), "name", request.name()));
        return orgView(saved);
    }

    @Transactional
    public void deleteCustom(UUID orgId, UUID tenantId, UUID actorId, UUID templateId) {
        requireEnabled();
        BizAppMarketplaceTemplate template = templateRepository.findByIdAndOrgId(templateId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
        templateRepository.delete(template);
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_TEMPLATE_DELETED, "bizapp_template",
                templateId, Map.of("app", template.getKind()));
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ResourceNotFoundException("BizApps are disabled on this installation");
        }
    }

    // ------------------------------------------------------------ helpers

    private List<Resource> bundledResources() {
        try {
            return List.of(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:" + BUNDLED_DIR + "/*.json"));
        } catch (Exception exception) {
            log.warn("Bundled BizApp templates unavailable: {}", exception.getMessage());
            return List.of();
        }
    }

    private TemplateView platformView(Map<String, Object> raw) {
        var manifest = parser.parse(raw);
        String name = manifest.name() == null ? manifest.kind() : manifest.name().en();
        return new TemplateView(
                null,
                "platform",
                manifest.kind(),
                name,
                manifest.name() == null ? null : manifest.name().vi(),
                null,
                manifest.version() == null ? 1 : manifest.version());
    }

    private TemplateView orgView(BizAppMarketplaceTemplate template) {
        var manifest = parser.parse(template.getManifest());
        return new TemplateView(
                template.getId(),
                "org",
                template.getKind(),
                template.getName(),
                manifest.name() == null ? null : manifest.name().vi(),
                template.getSummary(),
                template.getVersion());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportForRemix(UUID orgId, String templateRef) {
        if (templateRef == null) {
            throw new BadRequestException("templateRef is required");
        }
        return materializer.loadRaw(orgId, templateRef);
    }
}