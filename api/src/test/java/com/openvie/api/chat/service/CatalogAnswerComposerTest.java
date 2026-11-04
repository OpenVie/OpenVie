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

package com.openvie.api.chat.service;

import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogAnswerComposerTest {

    private BizAppRuntimeApi runtimeApi;
    private CatalogAnswerComposer composer;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runtimeApi = mock(BizAppRuntimeApi.class);
        composer = new CatalogAnswerComposer(runtimeApi);
    }

    @Test
    void composeListAppsReturnsEmptyMessageWhenNoApps() {
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of());

        String vi = composer.composeListApps(tenantId, "vi-VN");
        assertTrue(vi.contains("chưa có ứng dụng nào được cài đặt"));

        String en = composer.composeListApps(tenantId, "en-US");
        assertTrue(en.contains("No applications are currently installed"));
    }

    @Test
    void composeListAppsFormatsInstalledAppsWithoutCitations() {
        InstalledAppSummary crm = new InstalledAppSummary(
                UUID.randomUUID(), "crm", "CRM Lite", 1, "Quản lý khách hàng",
                List.of("leads", "contacts"), List.of("registry", "pipeline"), "ACTIVE"
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(crm));

        String result = composer.composeListApps(tenantId, "vi-VN");
        assertTrue(result.contains("CRM Lite"));
        assertTrue(result.contains("crm"));
        assertTrue(result.contains("leads, contacts"));
        assertFalse(result.contains("[S1]"));
    }

    @Test
    void composeAppDetailReturnsInstalledDetails() {
        InstalledAppSummary booking = new InstalledAppSummary(
                UUID.randomUUID(), "booking", "Booking Pro", 2, "Đặt lịch hẹn",
                List.of("appointments"), List.of("calendar"), "ACTIVE"
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(booking));

        String detail = composer.composeAppDetail(tenantId, "booking", "vi-VN");
        assertTrue(detail.contains("Booking Pro"));
        assertTrue(detail.contains("Phiên bản"));
        assertTrue(detail.contains("appointments"));
        assertFalse(detail.contains("[S1]"));
    }

    @Test
    void composeAppDetailFallsBackToMarketplaceCatalog() {
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of());
        MarketplaceTemplateSummary template = new MarketplaceTemplateSummary(
                "signature-request", "Ký số", 1, "Trình ký văn bản",
                List.of("signatures"), List.of("inbox"), "platform:signature-request"
        );
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of(template));

        String detail = composer.composeAppDetail(tenantId, "signature-request", "vi-VN");
        assertTrue(detail.contains("Ký số"));
        assertTrue(detail.contains("Chưa cài đặt"));
    }

    @Test
    void composeAppDetailHandlesUnknownApp() {
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of());
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());

        String result = composer.composeAppDetail(tenantId, "unknown-app", "en-US");
        assertTrue(result.contains("Application details not found"));
    }
}
