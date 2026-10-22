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

package com.openvie.api.common.event.durable;

/**
 * A delivery precondition for one class of module events.
 *
 * <p>The outbox relay consults every registered gate before publishing an
 * event. When a gate reports "not ready", the event is held in
 * {@link ModuleEventStatus#NO_CHANNEL} instead of burning retries: the
 * precondition (for example, an organization that has not configured email
 * yet) is a deployment state, not a transient failure. Held events replay
 * automatically once the gate opens.
 *
 * <p>Implementations live in the owning module and must be cheap; they run on
 * the relay's scheduled thread.
 */
public interface ModuleEventGate {

    /** True when this payload may be published now. Payloads this gate does
     *  not govern must return true. */
    boolean readyFor(Object payload);

    /** Operator-facing reason shown on the held event. */
    default String unavailableReason(Object payload) {
        return "A delivery precondition is not met; the event is held until it is.";
    }
}
