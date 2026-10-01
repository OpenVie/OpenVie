package com.cacanode.api.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class UtcClockConfig {
    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }
}
