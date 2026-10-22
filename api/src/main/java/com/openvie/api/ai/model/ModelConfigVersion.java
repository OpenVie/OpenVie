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

package com.openvie.api.ai.model;

import com.openvie.api.ai.enums.ModelConfigStatus;
import com.openvie.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "model_config_versions",
        indexes = {
                @Index(name = "idx_model_config_version_status", columnList = "status")
        }
)
public class ModelConfigVersion extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "version_label", nullable = false, length = 100)
    private String versionLabel;

    @Column(name = "generation_model_id", nullable = false)
    private String generationModelId;

    @Column(name = "generation_adapter_id")
    private String generationAdapterId;

    @Column(name = "generation_runtime", nullable = false, length = 100)
    private String generationRuntime = "ollama";

    @Column(name = "generation_endpoint", nullable = false)
    private String generationEndpoint = "http://localhost:11434/v1";

    @Column(name = "text_embedding_model_id", nullable = false)
    private String textEmbeddingModelId;

    @Column(name = "text_embedding_dimension", nullable = false)
    private Integer textEmbeddingDimension;

    @Column(name = "text_embedding_runtime", nullable = false, length = 100)
    private String textEmbeddingRuntime = "ollama";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ModelConfigStatus status = ModelConfigStatus.ACTIVE;
}
