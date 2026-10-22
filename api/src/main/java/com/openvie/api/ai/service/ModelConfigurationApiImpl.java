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
