package com.cacanode.api.tenant.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cacanode.api.tenant.repository.KnowledgeBaseRepository;

class KnowledgeBaseRevisionServiceTest {

    @Test
    void incrementIsTenantScopedAndRequiresExactlyOneRow() {
        UUID tenantId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        KnowledgeBaseRevisionService service = new KnowledgeBaseRevisionService(repository);
        when(repository.incrementSearchRevision(tenantId, knowledgeBaseId)).thenReturn(1, 0);

        service.increment(tenantId, knowledgeBaseId);
        assertThrows(IllegalStateException.class,
                () -> service.increment(tenantId, knowledgeBaseId));

        verify(repository, org.mockito.Mockito.times(2))
                .incrementSearchRevision(tenantId, knowledgeBaseId);
    }
}
