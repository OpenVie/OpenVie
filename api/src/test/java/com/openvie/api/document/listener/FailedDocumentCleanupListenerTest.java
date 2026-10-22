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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.openvie.api.document.event.FailedDocumentCleanupRequestedEvent;
import com.openvie.api.document.service.DocumentIndexCleanup;
import com.openvie.api.common.storage.DocumentStorage;

class FailedDocumentCleanupListenerTest {

    @Test
    void storageCleanupStillRunsWhenIndexCleanupFails() {
        DocumentIndexCleanup indexCleanup = mock(DocumentIndexCleanup.class);
        DocumentStorage documentStorage = mock(DocumentStorage.class);
        FailedDocumentCleanupListener listener = new FailedDocumentCleanupListener(
                indexCleanup,
                documentStorage
        );
        UUID tenantId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        doThrow(new RuntimeException("cleanup unavailable"))
                .when(indexCleanup).delete(tenantId, knowledgeBaseId, documentId);

        listener.cleanup(new FailedDocumentCleanupRequestedEvent(
                tenantId,
                knowledgeBaseId,
                documentId,
                "storage-key"
        ));

        verify(documentStorage).delete("storage-key");
    }
}
