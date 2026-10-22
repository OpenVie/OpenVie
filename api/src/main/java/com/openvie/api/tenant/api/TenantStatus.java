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

package com.openvie.api.tenant.api;

/**
 * Lifecycle state of a workspace (the table keeps its historical name
 * {@code tenants}).
 */
public enum TenantStatus {
    /** Normal operation: documents, chat, and indexing all work. */
    ACTIVE,

    /** Administrator paused the workspace; data is retained. */
    INACTIVE,

    /**
     * Soft-deleted by the organization owner. Rows stay so audit history
     * remains attributable; the workspace is unreachable from every API.
     */
    ARCHIVED
}
