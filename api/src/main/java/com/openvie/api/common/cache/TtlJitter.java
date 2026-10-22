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

package com.openvie.api.common.cache;

import com.openvie.api.common.config.CacheProperties;
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
