package com.fitnesstracker.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON settings that keep the wire format identical to the Python backend's.
 *
 * <p>Three decisions, each load-bearing:
 *
 * <ul>
 *   <li><b>snake_case.</b> The Next.js frontend reads {@code weight_kg},
 *       {@code performed_at} and friends directly — {@code weight_kg} alone appears 31
 *       times in the frontend source. Renaming to camelCase would break every page.
 *   <li><b>ISO-8601 timestamps, not epoch numbers.</b> Jackson's default is a numeric
 *       timestamp; {@code Instant} serialises as {@code 2026-09-18T16:48:51.077141Z},
 *       matching Python, once this is disabled.
 *   <li><b>Nulls are written, not omitted.</b> The frontend destructures optional fields,
 *       so an absent value must appear as {@code null} rather than vanish.
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer contractCompatibleJson() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
