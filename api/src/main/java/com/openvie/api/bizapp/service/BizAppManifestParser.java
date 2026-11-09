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

package com.openvie.api.bizapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.dto.BizAppManifest;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.common.exception.custom.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Single conversion point raw JSON ⇄ typed manifest. The database stores the
 * raw map (jsonb); interpretation always funnels through here so Jackson
 * coercion errors surface as 400s instead of 500s.
 */
@Component
@RequiredArgsConstructor
public class BizAppManifestParser {

    private final ObjectMapper objectMapper;

    public Manifest parse(Map<String, Object> raw) {
        if (raw == null) {
            throw new BadRequestException("manifest is required");
        }
        try {
            Manifest manifest = objectMapper.convertValue(raw, Manifest.class);
            if (manifest == null) {
                throw new BadRequestException("manifest is required");
            }
            return manifest;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("manifest is not a valid " + BizAppManifest.SCHEMA + " document");
        }
    }
}
