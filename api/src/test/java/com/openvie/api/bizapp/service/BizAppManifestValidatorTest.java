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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openvie.api.bizapp.dto.BizAppManifest;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.dto.BizAppManifest.Ui;
import com.openvie.api.common.exception.custom.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class BizAppManifestValidatorTest {

    private final BizAppManifestValidator validator = new BizAppManifestValidator();

    private static final Map<String, List<String>> RBAC = Map.of(
            "read", List.of("WORKSPACE_ADMIN", "MEMBER"),
            "write", List.of("WORKSPACE_ADMIN", "MEMBER"));

    private static Entity leads() {
        return new Entity("leads", new Localized("Khách hàng", "Leads"), List.of(
                new Field("name", "string", true, 200, null, null),
                new Field("email", "string", false, 200, "email", null),
                new Field("stage", "enum", true, null, null,
                        List.of("NEW", "CONTACTED", "WON"))),
                Map.of());
    }

    private static Block notifyBlock() {
        Map<String, Object> params = new HashMap<>();
        params.put("to", List.of("creator"));
        params.put("subject", "New lead");
        return new Block("notifyOwner", "notify", params);
    }

    private static Block reportBlock() {
        Map<String, Object> view = new HashMap<>();
        view.put("name", "pipeline");
        view.put("entity", "leads");
        view.put("groupBy", List.of("stage"));
        view.put("metrics", List.of(Map.of("fn", "count", "as", "leads_count")));
        Map<String, Object> params = new HashMap<>();
        params.put("views", List.of(view));
        return new Block("leadPipeline", "report", params);
    }

    private static Manifest crm(Entity entity, List<Block> blocks, Ui ui) {
        return new Manifest("bizapp.v1", "crm", new Localized("CRM Lite", "CRM Lite"),
                1, "briefcase", RBAC, List.of(entity), blocks, ui);
    }

    @Test
    void acceptsAValidManifest() {
        validator.validate(crm(leads(), List.of(notifyBlock(), reportBlock()),
                new Ui(List.of("registry:leads", "form:leads", "report:pipeline"))));
    }

    @Test
    void rejectsWrongSchemaVersion() {
        Manifest bad = new Manifest("bizflow.v9", "crm", new Localized("x", "x"),
                1, null, RBAC, List.of(leads()), List.of(), null);
        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> validator.validate(bad));
        assertTrue(exception.getMessage().contains("bizapp.v1"));
    }

    @Test
    void rejectsUnknownBlockType() {
        Block unknown = new Block("mystery", "send_payment", Map.of());
        assertThrows(BadRequestException.class,
                () -> validator.validate(crm(leads(), List.of(unknown), null)));
    }

    @Test
    void rejectsTraversalBpmnTemplate() {
        Map<String, Object> params = new HashMap<>();
        params.put("bpmnTemplate", "processes/../secrets.bpmn20.xml");
        params.put("candidateRole", "WORKSPACE_ADMIN");
        Block approval = new Block("confirm", "approval_workitem", params);
        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> validator.validate(crm(leads(), List.of(approval), null)));
        assertTrue(exception.getMessage().contains("bpmnTemplate"));
    }

    @Test
    void externalCallMustUseCredentialRef() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://crm.example.com/api");
        params.put("password", "inline-secret");
        Block bad = new Block("syncCrm", "external_call", params);
        assertThrows(BadRequestException.class,
                () -> validator.validate(crm(leads(), List.of(bad), null)));
    }

    @Test
    void rawSqlInReportViewMustBeRejected() {
        Map<String, Object> view = new HashMap<>();
        view.put("name", "legacy_view");
        view.put("sql", "SELECT * FROM bizapp_records WHERE tenant_id = :tenant");
        Map<String, Object> params = new HashMap<>();
        params.put("views", List.of(view));
        Block report = new Block("legacyReport", "report", params);
        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> validator.validate(crm(leads(), List.of(report), null)));
        assertTrue(exception.getMessage().contains("free-form sql"), exception.getMessage());
    }

    @Test
    void reportViewRequiresDeclaredEntityAndValidMetrics() {
        Map<String, Object> badEntityView = new HashMap<>();
        badEntityView.put("name", "bad_view");
        badEntityView.put("entity", "nonexistent_entity");
        Block report1 = new Block("badReport", "report", Map.of("views", List.of(badEntityView)));
        assertThrows(BadRequestException.class,
                () -> validator.validate(crm(leads(), List.of(report1), null)));

        Map<String, Object> validView = new HashMap<>();
        validView.put("name", "pipeline_report");
        validView.put("entity", "leads");
        validView.put("groupBy", List.of("stage"));
        validView.put("metrics", List.of(Map.of("fn", "count", "as", "leads_count")));
        Block validReport = new Block("validReport", "report", Map.of("views", List.of(validView)));
        assertDoesNotThrow(() -> validator.validate(crm(leads(), List.of(validReport), null)));
    }

    @Test
    void hooksMustReferenceExistingBlocksOrDeclaredEvents() {
        Map<String, Object> events = new HashMap<>();
        events.put("events", List.of("record.created"));
        Block webhook = new Block("pushEvents", "webhook_out", events);
        Entity hooked = new Entity("leads", null,
                List.of(new Field("name", "string", true, 100, null, null)),
                Map.of("onCreate", List.of("noSuchBlock")));
        assertThrows(BadRequestException.class, () -> validator.validate(
                crm(hooked, List.of(notifyBlock(), webhook), null)));
    }

    @Test
    void uiViewMustResolveToEntityOrReportView() {
        assertThrows(BadRequestException.class, () -> validator.validate(
                crm(leads(), List.of(notifyBlock(), reportBlock()),
                        new Ui(List.of("registry:ghosts")))));
        assertThrows(BadRequestException.class, () -> validator.validate(
                crm(leads(), List.of(notifyBlock(), reportBlock()),
                        new Ui(List.of("report:ghost-view")))));
    }

    @Test
    void rbacCapabilityKeysAreValidated() {
        Map<String, List<String>> rbac = new HashMap<>();
        rbac.put("moderate", List.of("WORKSPACE_ADMIN"));
        Manifest bad = new Manifest("bizapp.v1", "crm", new Localized("x", "x"),
                1, null, rbac, List.of(leads()), List.of(), null);
        assertThrows(BadRequestException.class, () -> validator.validate(bad));
    }

    @Test
    void duplicateFieldsAreRejected() {
        Entity dup = new Entity("leads", null, List.of(
                new Field("name", "string", true, 100, null, null),
                new Field("name", "date", false, null, null, null)),
                Map.of());
        assertThrows(BadRequestException.class,
                () -> validator.validate(crm(dup, List.of(), null)));
    }

    @Test
    void manifestFieldLookupHelperWorks() {
        Manifest manifest = crm(leads(), List.of(), null);
        assertEquals("enum", manifest.entityField("leads", "stage"));
        assertEquals(null, manifest.entityField("leads", "ghost"));
        assertFalse(manifest.block("notifyOwner").isPresent());
    }
}
