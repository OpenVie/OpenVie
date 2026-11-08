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
 * One failed connector run. {@link #retryable()} is the only signal the worker
 * uses to choose between a backoff retry and a terminal DEAD job, so adapters
 * must classify provider failures explicitly instead of leaking HTTP codes.
 */
public class ConnectorException extends RuntimeException {

    private final boolean retryable;

    private ConnectorException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public static ConnectorException retryable(String message) {
        return new ConnectorException(message, true);
    }

    public static ConnectorException permanent(String message) {
        return new ConnectorException(message, false);
    }

    public boolean retryable() {
        return retryable;
    }
}
