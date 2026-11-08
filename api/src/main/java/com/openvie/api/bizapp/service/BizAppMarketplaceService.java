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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppDtos.CatalogEntryView;
import com.openvie.api.bizapp.dto.BizAppDtos.DraftView;
import com.openvie.api.bizapp.dto.BizAppDtos.ImportTemplateRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.RemixRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.SaveTemplateRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.TemplateView;
import com.openvie.api.bizapp.model.BizAppDraft;
import com.openvie.api.bizapp.model.BizAppMarketplaceTemplate;
import com.openvie.api.bizapp.repository.BizAppDraftRepository;
import com.openvie.api.bizapp.repository.BizAppMarketplaceTemplateRepository;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.common.net.SafeOutboundHttp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP")
public class BizAppMarketplaceService {

    private static final String BUNDLED_DIR = "marketplace/bizapps";
    private static final Set<String> ALLOWED_SPDX = Set.of(
            "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "CC0-1.0", "CC-BY-4.0");

    private final BizAppMarketplaceTemplateRepository templateRepository;
    private final BizAppDraftRepository draftRepository;
    private final BizAppMaterializer materializer;
    private final BizAppManifestParser parser;
    private final BizAppManifestValidator validator;
    private final BizAppRuntime runtime;
    private final BizAppProperties properties;
    private final SafeOutboundHttp safeOutboundHttp;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------- catalog

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
        requireKnownKeys(request.manifest());
        var manifest = parser.parse(request.manifest());
        validator.validate(manifest, false);
        BizAppMarketplaceTemplate existing = templateRepository
                .findByOrgIdAndKindAndName(orgId, manifest.kind(), request.name())
                .orElse(null);
        if (existing == null) {
            existing = new BizAppMarketplaceTemplate();
            existing.setOrgId(orgId);
            existing.setKind(manifest.kind());
            existing.setCreatedBy(actorId);
            existing.setSource("org");
            existing.setMeta(Map.of("author", "Org " + orgId, "license", "Apache-2.0", "source", "org"));
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

    // --------------------------------------------------- export / import / remix

    @Transactional(readOnly = true)
    public Map<String, Object> exportEnvelope(UUID orgId, UUID tenantId, UUID actorId, String templateRef) {
        requireEnabled();
        Map<String, Object> raw = materializer.loadRawEnvelope(orgId, templateRef);
        Map<String, Object> envelope;
        if ("openvie.template.v1".equals(raw.get("envelope"))) {
            envelope = raw;
        } else {
            // Build envelope
            String manifestJson = canonicalJson(raw);
            String sha = sha256(manifestJson);
            envelope = new LinkedHashMap<>();
            envelope.put("envelope", "openvie.template.v1");
            envelope.put("meta", Map.of(
                    "author", "Org " + orgId,
                    "license", "Apache-2.0",
                    "source", "org",
                    "sha256", sha,
                    "generator", "human",
                    "changes", List.of()
            ));
            envelope.put("manifest", raw);
        }
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_TEMPLATE_EXPORTED, "bizapp_template",
                null, Map.of("templateRef", templateRef));
        return envelope;
    }

    @Transactional
    public DraftView importTemplate(UUID orgId, UUID tenantId, UUID actorId, ImportTemplateRequest request) {
        requireEnabled();
        String jsonContent = request.content();

        if (request.url() != null && !request.url().isBlank()) {
            var httpReq = new SafeOutboundHttp.Request(
                    request.url(),
                    "GET",
                    Map.of(),
                    null,
                    properties.remoteCatalogHosts()
            );
            SafeOutboundHttp.Response resp = safeOutboundHttp.execute(httpReq);
            if (resp.statusCode() != 200) {
                throw new BadRequestException("Remote template fetch failed with HTTP " + resp.statusCode());
            }
            jsonContent = resp.body();

            if (request.sha256() != null && !request.sha256().isBlank()) {
                String fetchedSha = sha256(jsonContent);
                if (!request.sha256().equalsIgnoreCase(fetchedSha)) {
                    throw new BadRequestException("Remote template sha256 mismatch: expected " + request.sha256() + " but got " + fetchedSha);
                }
            }
        }

        if (jsonContent == null || jsonContent.isBlank()) {
            throw new BadRequestException("Import content or URL is required");
        }

        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(jsonContent, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new BadRequestException("Template import is not valid JSON: " + ex.getMessage());
        }

        if (!"openvie.template.v1".equals(envelope.get("envelope"))) {
            throw new BadRequestException("Template must use envelope format openvie.template.v1");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) envelope.get("meta");
        if (meta == null) {
            throw new BadRequestException("Template envelope is missing meta");
        }

        String license = String.valueOf(meta.get("license"));
        if (!ALLOWED_SPDX.contains(license)) {
            throw new BadRequestException("License " + license + " is not an allowed SPDX license: " + ALLOWED_SPDX);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> manifestMap = (Map<String, Object>) envelope.get("manifest");
        if (manifestMap == null) {
            throw new BadRequestException("Template envelope is missing manifest");
        }

        String computedSha = sha256(canonicalJson(manifestMap));
        if (meta.get("sha256") != null && !computedSha.equalsIgnoreCase(String.valueOf(meta.get("sha256")))) {
            throw new BadRequestException("Template manifest sha256 checksum mismatch");
        }

        // Validate manifest (re-run full validator from scratch)
        requireKnownKeys(manifestMap);
        var parsed = parser.parse(manifestMap);
        validator.validate(parsed, false);

        // Store as draft ONLY (never auto-installs!)
        BizAppDraft draft = new BizAppDraft();
        draft.setOrgId(orgId);
        draft.setCreatedBy(actorId);
        draft.setSource("import");
        draft.setTitle(parsed.name() != null ? parsed.name().en() : parsed.kind());
        draft.setManifest(manifestMap);
        draft.setValidation(Map.of("ok", true, "errors", List.of()));
        draft.setMeta(meta);
        BizAppDraft saved = draftRepository.save(draft);

        runtime.audit(tenantId, actorId, LogAction.BIZAPP_TEMPLATE_IMPORTED, "bizapp_draft",
                saved.getId(), Map.of("kind", parsed.kind(), "author", meta.get("author")));

        return new DraftView(
                saved.getId(),
                saved.getTitle(),
                saved.getSource(),
                saved.getManifest(),
                saved.getValidation(),
                saved.getMeta(),
                saved.getCreatedAt(),
                saved.getUpdatedAt()
        );
    }

    @Transactional
    public DraftView remixTemplate(UUID orgId, UUID tenantId, UUID actorId, String templateRef, RemixRequest request) {
        requireEnabled();
        Map<String, Object> raw = materializer.loadRawEnvelope(orgId, templateRef);
        Map<String, Object> manifestMap;
        Map<String, Object> metaMap;

        if ("openvie.template.v1".equals(raw.get("envelope"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw.get("manifest");
            @SuppressWarnings("unchecked")
            Map<String, Object> me = (Map<String, Object>) raw.get("meta");
            manifestMap = m;
            metaMap = new LinkedHashMap<>(me != null ? me : Map.of());
        } else {
            manifestMap = raw;
            metaMap = new LinkedHashMap<>();
            metaMap.put("author", "Org " + orgId);
            metaMap.put("license", "Apache-2.0");
            metaMap.put("source", templateRef);
        }

        // Retain upstream author, license, source; append changes
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = metaMap.get("changes") instanceof List<?> list
                ? new ArrayList<>((List<Map<String, Object>>) list)
                : new ArrayList<>();
        changes.add(Map.of(
                "by", orgId.toString(),
                "at", Instant.now().toString(),
                "summary", request != null && request.summary() != null ? request.summary() : "Remixed template"
        ));
        metaMap.put("changes", changes);
        metaMap.put("generator", "human");

        var parsed = parser.parse(manifestMap);
        BizAppDraft draft = new BizAppDraft();
        draft.setOrgId(orgId);
        draft.setCreatedBy(actorId);
        draft.setSource("remix");
        draft.setTitle("Remix of " + (parsed.name() != null ? parsed.name().en() : parsed.kind()));
        draft.setManifest(manifestMap);
        draft.setValidation(Map.of("ok", true, "errors", List.of()));
        draft.setMeta(metaMap);
        BizAppDraft saved = draftRepository.save(draft);

        runtime.audit(tenantId, actorId, LogAction.BIZAPP_TEMPLATE_REMIXED, "bizapp_draft",
                saved.getId(), Map.of("templateRef", templateRef));

        return new DraftView(
                saved.getId(),
                saved.getTitle(),
                saved.getSource(),
                saved.getManifest(),
                saved.getValidation(),
                saved.getMeta(),
                saved.getCreatedAt(),
                saved.getUpdatedAt()
        );
    }

    public List<CatalogEntryView> remoteCatalog() {
        if (!properties.remoteCatalogEnabled()) {
            return List.of();
        }
        List<CatalogEntryView> catalog = new ArrayList<>();
        for (String host : properties.remoteCatalogHosts()) {
            try {
                String url = "https://" + host + "/index.json";
                var httpReq = new SafeOutboundHttp.Request(url, "GET", Map.of(), null, List.of(host));
                var resp = safeOutboundHttp.execute(httpReq);
                if (resp.statusCode() == 200 && resp.body() != null) {
                    List<CatalogEntryView> items = objectMapper.readValue(
                            resp.body(), new TypeReference<List<CatalogEntryView>>() {});
                    catalog.addAll(items);
                }
            } catch (Exception ex) {
                log.warn("Remote catalog fetch failed from host {}: {}", host, ex.getMessage());
            }
        }
        return catalog;
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ResourceNotFoundException("BizApps are disabled on this installation");
        }
    }

    /** Templates and imports must not carry keys the manifest contract ignores. */
    private void requireKnownKeys(Map<String, Object> manifest) {
        List<String> unknown = BizAppManifestKeys.unknownKeys(manifest);
        if (!unknown.isEmpty()) {
            throw new BadRequestException(String.join("; ", unknown));
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

    private String canonicalJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new BadRequestException("JSON serialization error: " + e.getMessage());
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new BadRequestException("Checksum calculation failed: " + e.getMessage());
        }
    }
}
