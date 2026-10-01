package com.cacanode.api.common.cache;

import com.cacanode.api.common.config.CacheProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

@Component
public class TtlJitter {

    private static final long MINIMUM_TTL_MILLIS = 1_000;
    private final int jitterPercent;
    private final DoubleSupplier random;

    @Autowired
    public TtlJitter(CacheProperties properties) {
        this(properties.getTtlJitterPercent(), () -> ThreadLocalRandom.current().nextDouble());
    }

    TtlJitter(int jitterPercent, DoubleSupplier random) {
        if (jitterPercent < 0 || jitterPercent > 100) {
            throw new IllegalArgumentException("TTL jitter percent must be between 0 and 100");
        }
        this.jitterPercent = jitterPercent;
        this.random = random;
    }

    public Duration apply(Duration baseTtl) {
        long baseMillis = Math.max(MINIMUM_TTL_MILLIS, baseTtl.toMillis());
        double factor = 1.0 + ((random.getAsDouble() * 2.0 - 1.0) * jitterPercent / 100.0);
        long jitteredMillis = Math.round(baseMillis * factor);
        return Duration.ofMillis(Math.max(MINIMUM_TTL_MILLIS, jitteredMillis));
    }
}
