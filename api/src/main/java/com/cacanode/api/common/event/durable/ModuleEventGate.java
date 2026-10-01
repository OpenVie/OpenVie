package com.cacanode.api.common.event.durable;

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
