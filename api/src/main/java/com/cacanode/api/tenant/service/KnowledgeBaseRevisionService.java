package com.cacanode.api.tenant.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cacanode.api.tenant.repository.KnowledgeBaseRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseRevisionService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    @Transactional
    public void increment(UUID tenantId, UUID knowledgeBaseId) {
        int updated = knowledgeBaseRepository.incrementSearchRevision(tenantId, knowledgeBaseId);
        if (updated != 1) {
            throw new IllegalStateException("Knowledge base revision target was not found");
        }
    }
}
