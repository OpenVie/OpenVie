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
 * Authority of a user inside one workspace, stored on the membership row.
 * The historical tenant vocabulary is retained internally: a workspace row
 * lives in the {@code tenants} table and is addressed by {@code tenant_id}.
 */
public enum WorkspaceRole {
    WORKSPACE_ADMIN,
    MEMBER
}
