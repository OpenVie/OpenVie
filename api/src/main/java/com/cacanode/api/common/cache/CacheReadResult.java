package com.cacanode.api.common.cache;

import java.util.Arrays;

public final class CacheReadResult {

    private final CacheReadStatus status;
    private final byte[] value;

    private CacheReadResult(CacheReadStatus status, byte[] value) {
        this.status = status;
        this.value = value == null ? null : Arrays.copyOf(value, value.length);
    }

    public static CacheReadResult hit(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("A cache hit requires a value");
        }
        return new CacheReadResult(CacheReadStatus.HIT, value);
    }

    public static CacheReadResult of(CacheReadStatus status) {
        if (status == CacheReadStatus.HIT) {
            throw new IllegalArgumentException("Use hit(value) for cache hits");
        }
        return new CacheReadResult(status, null);
    }

    public CacheReadStatus status() {
        return status;
    }

    public byte[] value() {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
