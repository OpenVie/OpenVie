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

package com.openvie.api.chat.service.implement;

import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.chat.api.ChatRuntimeApi;
import com.openvie.api.chat.dto.ChatDtos;
import com.openvie.api.chat.query.ChatControlPlaneService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatRuntimeApiImpl implements ChatRuntimeApi, BizAppRuntimeApi.RecentTurnsProvider {

    private final ChatControlPlaneService controlPlaneService;

    @Override
    public AppSessionResult createScopedSession(
            UUID tenantId, UUID actorId, UUID chatbotId, UUID knowledgeBaseId, String locale) {
        var req = new ChatDtos.CreateSessionRequest(chatbotId, knowledgeBaseId, locale, Map.of());
        var session = controlPlaneService.createEmployeeSession(tenantId, actorId, req);
        return new AppSessionResult(
                UUID.fromString(session.id()),
                session.chatbotId() != null ? UUID.fromString(session.chatbotId()) : null,
                session.knowledgeBaseId() != null ? UUID.fromString(session.knowledgeBaseId()) : null,
                session.locale());
    }

    @Override
    public AppAnswerResult submitScopedTurn(
            UUID tenantId, UUID actorId, UUID sessionId, String query, List<UUID> documentScope) {
        var resp = controlPlaneService.submitEmployeeMessage(
                tenantId,
                actorId,
                sessionId,
                query,
                Map.of(),
                documentScope,
                null,
                UUID.randomUUID().toString(),
                new ChatControlPlaneService.AnswerStreamListener() {
                    @Override
                    public void onContent(String content) {}
                    @Override
                    public void onReset() {}
                }
        );
        List<String> citations = new ArrayList<>();
        if (resp.citations() != null) {
            for (var c : resp.citations()) {
                if (c.id() != null) {
                    citations.add(c.id());
                }
            }
        }
        return new AppAnswerResult(resp.content(), citations);
    }

    @Override
    public List<RecentTurn> listRecentTurns(UUID tenantId, UUID actorId, UUID sessionId, int limit) {
        return controlPlaneService.listRecentTurns(tenantId, actorId, sessionId, limit);
    }

    @Override
    public List<BizAppRuntimeApi.RecentTurn> fetchRecentTurns(UUID tenantId, UUID actorId, UUID sessionId, int limit) {
        return controlPlaneService.listRecentTurns(tenantId, actorId, sessionId, limit).stream()
                .map(t -> new BizAppRuntimeApi.RecentTurn(t.role(), t.content()))
                .toList();
    }
}
