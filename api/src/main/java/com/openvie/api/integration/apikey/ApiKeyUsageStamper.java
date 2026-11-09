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

package com.openvie.api.integration.apikey;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stamps key usage on the authentication hot path. Executing the update here
 * in its own {@link Propagation#REQUIRES_NEW} transaction keeps it writable
 * although the caller resolves the membership inside a read-only one; a self
 * call inside {@link ApiKeyService} would bypass the proxy and inherit that
 * read-only context (the silent no-update bug).
 */
@Service
@RequiredArgsConstructor
public class ApiKeyUsageStamper {

    private static final long THROTTLE_SECONDS = 30;

    private final ApiKeyRepository keyRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stampUsage(UUID keyId) {
        LocalDateTime now = LocalDateTime.now();
        keyRepository.stampUsage(keyId, now, now.minusSeconds(THROTTLE_SECONDS));
    }
}
