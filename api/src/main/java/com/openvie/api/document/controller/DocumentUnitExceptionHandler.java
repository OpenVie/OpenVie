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

package com.openvie.api.document.controller;

import com.openvie.api.ai.api.AiInferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = DocumentController.class)
public class DocumentUnitExceptionHandler {
    @ExceptionHandler(AiInferenceException.class)
    public ResponseEntity<Map<String, String>> handle(AiInferenceException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Map.of("detail", exception.getMessage()));
    }
}
