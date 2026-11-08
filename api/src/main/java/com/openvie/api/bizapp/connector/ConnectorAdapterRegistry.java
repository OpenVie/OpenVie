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

package com.openvie.api.bizapp.connector;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Protocol → adapter lookup; a duplicate protocol is a wiring bug, not a runtime race. */
@Component
public class ConnectorAdapterRegistry {

    private final Map<String, ConnectorAdapter> adapters = new HashMap<>();

    public ConnectorAdapterRegistry(List<ConnectorAdapter> adapterList) {
        for (ConnectorAdapter adapter : adapterList) {
            String protocol = adapter.protocol().toLowerCase(Locale.ROOT);
            if (adapters.putIfAbsent(protocol, adapter) != null) {
                throw new IllegalStateException("duplicate connector adapter for protocol " + protocol);
            }
        }
    }

    public Optional<ConnectorAdapter> adapter(String protocol) {
        return protocol == null
                ? Optional.empty()
                : Optional.ofNullable(adapters.get(protocol.toLowerCase(Locale.ROOT)));
    }
}
