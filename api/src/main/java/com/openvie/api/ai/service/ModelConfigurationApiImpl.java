package com.openvie.api.ai.service;

import com.openvie.api.ai.api.ModelConfigurationApi;
import com.openvie.api.ai.enums.ModelConfigStatus;
import com.openvie.api.ai.repository.ModelConfigVersionRepository;
import com.openvie.api.common.exception.custom.InternalServerErrorException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelConfigurationApiImpl implements ModelConfigurationApi {
    private final ModelConfigVersionRepository repository;

    @Override
    @Transactional(readOnly = true)
    public UUID activeModelConfigurationId() {
        return repository.findFirstByStatusOrderByCreatedAtDesc(ModelConfigStatus.ACTIVE)
                .map(model -> model.getId())
                .orElseThrow(() -> new InternalServerErrorException(
                        "Cannot provision tenant workspace: no active model configuration exists"));
    }
}
