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

/**
 * One transport for {@code external_call}. Adapters own the wire protocol and
 * authentication; the executor owns persistence, sinks, audit and retries.
 */
public interface ConnectorAdapter {

    /** Lowercase protocol key declared in the manifest ({@code http}, {@code mqtt}). */
    String protocol();

    ConnectorResponse execute(BizAppConnectorSpec spec, ConnectorContext context);
}
