package com.cacanode.api.common.event.durable;

public enum ModuleEventStatus {
    PENDING,
    PUBLISHED,
    /** Held: the consumer's delivery precondition (e.g. an enabled notification
     *  channel) is not met. Not a failure; replays when the precondition opens. */
    NO_CHANNEL,
    DEAD
}
