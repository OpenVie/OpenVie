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

package com.openvie.api.common.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface DocumentStorage {
    void store(String key, MultipartFile file);

    void store(String key, byte[] content, String contentType);

    void store(String key, InputStream content, long contentLength, String contentType);

    StoredDocument load(String key);

    StoredDocument loadRange(String key,long startInclusive,long endInclusive);

    StoredObjectMetadata metadata(String key);

    boolean exists(String key);

    void delete(String key);
}
