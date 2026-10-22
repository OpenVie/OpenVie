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

package com.openvie.api.common.event.durable;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ModuleEventInboxService {
    private final ModuleEventInboxRepository repository;

    public boolean claim(String consumerName) {
        var delivery = ModuleEventDeliveryContext.currentOrNull();
        if (delivery == null) {
            return true;
        }
        return repository.claim(consumerName, delivery.eventId(), delivery.eventType()) == 1;
    }
}
