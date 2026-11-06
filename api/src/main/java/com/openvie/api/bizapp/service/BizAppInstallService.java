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
import com.openvie.api.bizapp.dto.BizAppDtos.InstallResult;
import com.openvie.api.bizapp.dto.BizAppDtos.InstalledAppView;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.service.block.ApprovalWorkItemBlock;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.workflow.api.WorkflowRuntimeApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Install / uninstall / config lifecycle for Tier-3 apps. Installing validates
 * the manifest, freezes it onto the installation row, registers the app's
 * BPMN approval definition with the Flowable engine, and audits everything.
 * Uninstall is soft: records and audit history survive.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP")
public class BizAppInstallService {

    /** The bundled generic record-approval process parameterized by block params. */
    private static final String GENERIC_APPROVAL_BPMN = "processes/bizapp-single-approval.bpmn20.xml";

    private final BizAppInstallationRepository installationRepository;
    private final BizAppManifestValidator validator;
    private final BizAppManifestParser parser;
    private final WorkflowRuntimeApi workflowRuntimeApi;
    private final BizAppRuntime runtime;
    private final BizAppProperties properties;

    public void requireEnabled() {
        if (!properties.enabled()) {
            throw new ResourceNotFoundException("BizApps are disabled on this installation");
        }
    }

    @Transactional
    public InstallResult install(UUID tenantId, UUID orgId, UUID actorId, Map<String, Object> rawManifest) {
        requireEnabled();
        Manifest parsed = parser.parse(rawManifest);
        boolean isPlatform = isPlatformBundledTemplate(parsed.kind());
        validator.validate(parsed, isPlatform);
        BizAppInstallation installation = installationRepository.findByTenantIdAndKind(tenantId, parsed.kind())
                .orElseGet(BizAppInstallation::new);
        if (installation.getId() != null && "ACTIVE".equalsIgnoreCase(installation.getStatus())) {
            throw new ConflictException("App " + parsed.kind() + " is already installed");
        }
        String processKey = deployApprovalDefinition(parsed, tenantId, actorId);

        installation.setOrgId(orgId);
        installation.setTenantId(tenantId);
        installation.setKind(parsed.kind());
        installation.setVersion(parsed.version() == null ? 1 : parsed.version());
        installation.setManifest(rawManifest);
        installation.setProcessKey(processKey);
        installation.setInstalledBy(actorId);
        installation.setStatus("ACTIVE");
        installation = installationRepository.save(installation);
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_APP_INSTALLED, "bizapp_installation",
                installation.getId(), Map.of("app", parsed.kind()));
        log.info("BizApp {} installed workspace={} processKey={}", parsed.kind(), tenantId, processKey);
        return new InstallResult(installation.getId(), parsed.kind(), installation.getVersion(), processKey);
    }

    @Transactional
    public void uninstall(UUID tenantId, UUID actorId, String kind) {
        requireEnabled();
        BizAppInstallation installation = installationRepository.findByTenantIdAndKind(tenantId, kind)
                .orElseThrow(() -> new ResourceNotFoundException("App " + kind + " is not installed"));
        installation.setStatus("DISABLED");
        installationRepository.save(installation);
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_APP_UNINSTALLED, "bizapp_installation",
                installation.getId(), Map.of("app", kind));
    }

    @Transactional
    public InstalledAppView updateConfig(UUID tenantId, UUID actorId, String kind, Map<String, Object> config) {
        requireEnabled();
        BizAppInstallation installation = installationRepository.findByTenantIdAndKind(tenantId, kind)
                .orElseThrow(() -> new ResourceNotFoundException("App " + kind + " is not installed"));
        installation.setConfig(config == null ? Map.of() : config);
        BizAppInstallation saved = installationRepository.save(installation);
        return toView(saved, parser.parse(saved.getManifest()));
    }

    @Transactional(readOnly = true)
    public List<InstalledAppView> listInstalled(UUID tenantId) {
        requireEnabled();
        return installationRepository
                .findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "ACTIVE").stream()
                .map(installation -> toView(installation, parser.parse(installation.getManifest())))
                .toList();
    }

    @Transactional(readOnly = true)
    public InstalledAppView getInstalled(UUID tenantId, String kind) {
        requireEnabled();
        BizAppInstallation installation = installationRepository
                .findByTenantIdAndKindAndStatus(tenantId, kind, "ACTIVE")
                .orElseThrow(() -> new ResourceNotFoundException("App " + kind + " is not installed"));
        return toView(installation, parser.parse(installation.getManifest()));
    }

    @Transactional(readOnly = true)
    public BizAppInstallation getInstallationEntity(UUID tenantId, String kind) {
        requireEnabled();
        return installationRepository
                .findByTenantIdAndKindAndStatus(tenantId, kind, "ACTIVE")
                .orElseThrow(() -> new ResourceNotFoundException("App " + kind + " is not installed"));
    }

    private String deployApprovalDefinition(Manifest manifest, UUID tenantId, UUID actorId) {
        if (manifest.blocks() == null) {
            return null;
        }
        var approval = manifest.blocks().stream()
                .filter(block -> ApprovalWorkItemBlock.TYPE.equals(block.type()))
                .findFirst();
        if (approval.isEmpty()) {
            return null;
        }
        String templatePath = String.valueOf(approval.get().params().get("bpmnTemplate"));
        String xml = readTemplate(templatePath);
        return workflowRuntimeApi.deploy(tenantId, actorId, "bizapp-" + manifest.kind(), xml).key();
    }

    private String readTemplate(String templatePath) {
        String xml = tryRead(templatePath);
        if (xml != null) {
            return xml;
        }
        // Chat-drafted and locally authored manifests may reference a bespoke
        // process file that does not ship with the platform. The bundled
        // generic single-approval definition is parameterized by the block's
        // own params (candidateRole, SLA timers), so it is a faithful
        // drop-in: degrade to it ("skip, not fail") instead of refusing the
        // whole install over a template path the generator could never
        // create. Only a missing GENERIC template is fatal.
        if (templatePath != null && !templatePath.isBlank() && !"null".equals(templatePath)) {
            log.warn("BPMN template {} is not bundled; falling back to the generic single-approval process", templatePath);
        }
        xml = tryRead(GENERIC_APPROVAL_BPMN);
        if (xml != null) {
            return xml;
        }
        throw new ResourceNotFoundException("BPMN template not available: " + templatePath);
    }

    private String tryRead(String path) {
        if (path == null || path.isBlank() || "null".equals(path)) {
            return null;
        }
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream stream = resource.getInputStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isPlatformBundledTemplate(String kind) {
        if (kind == null || kind.isBlank()) return false;
        try {
            return new ClassPathResource("marketplace/bizapps/" + kind + ".json").exists();
        } catch (Exception ex) {
            return false;
        }
    }

    private InstalledAppView toView(BizAppInstallation installation, Manifest manifest) {
        var name = manifest.name();
        return new InstalledAppView(
                installation.getKind(),
                installation.getVersion(),
                name == null ? installation.getKind() : name.en(),
                name == null ? installation.getKind() : name.vi(),
                manifest.icon(),
                installation.getStatus(),
                installation.getManifest(),
                installation.getConfig(),
                installation.getProcessKey(),
                installation.getCreatedAt());
    }
}
