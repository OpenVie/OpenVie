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

package com.openvie.api.common.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Retention policy for soft-archived workspaces and BizApp records.
 *
 * <p>A value of {@code 0} disables the window (nothing becomes eligible). The
 * record purge is additionally gated by
 * {@code app.retention.bizapp-record-purge-enabled} because hard deletion is
 * irreversible; workspace hard deletion stays a reviewed ops runbook.
 */
@Component
public class RetentionProperties {

    private final int workspaceDays;
    private final int recordDays;
    private final boolean recordPurgeEnabled;
    private final int maxRecordsPerRun;

    public RetentionProperties(
            @Value("${app.retention.workspace-days:0}") int workspaceDays,
            @Value("${app.retention.bizapp-record-days:0}") int recordDays,
            @Value("${app.retention.bizapp-record-purge-enabled:false}") boolean recordPurgeEnabled,
            @Value("${app.retention.max-records-per-run:200}") int maxRecordsPerRun) {
        this.workspaceDays = Math.max(0, workspaceDays);
        this.recordDays = Math.max(0, recordDays);
        this.recordPurgeEnabled = recordPurgeEnabled;
        this.maxRecordsPerRun = Math.max(1, maxRecordsPerRun);
    }

    public int workspaceDays() {
        return workspaceDays;
    }

    public int recordDays() {
        return recordDays;
    }

    public boolean recordPurgeEnabled() {
        return recordPurgeEnabled;
    }

    public int maxRecordsPerRun() {
        return maxRecordsPerRun;
    }

    /** When a workspace/record archived at {@code archivedAt} becomes purge-eligible. */
    public LocalDateTime purgeEligibleAt(LocalDateTime archivedAt, int days) {
        if (archivedAt == null || days <= 0) {
            return null;
        }
        return archivedAt.plusDays(days);
    }
}
