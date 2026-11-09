-- Copyright 2026 LinkedNodeDigital
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- Workspace provisioning requires at least one active model configuration.
-- The public default is the local, self-hosted path (Ollama): no hosted
-- provider endpoint is seeded, and inference egress stays inside the
-- deployment's own network. Operators may add other configurations later.
INSERT INTO model_config_versions (
    name,
    version_label,
    generation_model_id,
    generation_runtime,
    generation_endpoint,
    text_embedding_model_id,
    text_embedding_dimension,
    text_embedding_runtime,
    status
)
SELECT
    'local-default',
    '2026-10',
    'vylinh',
    'ollama',
    'http://localhost:11434/v1',
    'bge-m3',
    1024,
    'ollama',
    'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1
    FROM model_config_versions
    WHERE status = 'ACTIVE'
);
