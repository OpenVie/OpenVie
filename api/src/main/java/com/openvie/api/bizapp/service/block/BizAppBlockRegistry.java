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

package com.openvie.api.bizapp.service.block;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collects every {@link BizAppBlockExecutor} bean into a type-keyed table —
 * the Spring-context answer to Flowise's NodesPool, minus filesystem
 * discovery because the container already owns construction.
 */
@Component
public class BizAppBlockRegistry {

    private final Map<String, BizAppBlockExecutor> executors = new HashMap<>();

    public BizAppBlockRegistry(List<BizAppBlockExecutor> beans) {
        for (BizAppBlockExecutor executor : beans) {
            executors.put(executor.supports(), executor);
        }
    }

    public Optional<BizAppBlockExecutor> executor(String blockType) {
        return Optional.ofNullable(executors.get(blockType));
    }
}
