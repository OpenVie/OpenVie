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

package com.openvie.api.document.listener;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.openvie.api.document.event.FailedDocumentCleanupRequestedEvent;
import com.openvie.api.document.service.DocumentIndexCleanup;
import com.openvie.api.common.storage.DocumentStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class FailedDocumentCleanupListener {

    private final DocumentIndexCleanup indexCleanup;
    private final DocumentStorage documentStorage;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void cleanup(FailedDocumentCleanupRequestedEvent event) {
        try {
            indexCleanup.delete(event.tenantId(), event.knowledgeBaseId(), event.documentId());
        } catch (RuntimeException e) {
            log.warn("Unable to clean failed document indexes documentId={}", event.documentId(), e);
        }
        try {
            documentStorage.delete(event.storagePath());
        } catch (RuntimeException e) {
            log.warn("Unable to clean failed document storage documentId={}", event.documentId(), e);
        }
    }
}
