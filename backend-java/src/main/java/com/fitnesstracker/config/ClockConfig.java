package com.fitnesstracker.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable clock.
 *
 * <p>The analytics engine takes {@code today} as a parameter so its results are
 * reproducible; the Java port keeps that property by injecting a {@link Clock} rather
 * than calling {@code Instant.now()} inline.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
