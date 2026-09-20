package com.fitnesstracker.common.web;

import com.fitnesstracker.common.persistence.PgEnum;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.stereotype.Component;

/**
 * Binds query parameters and path variables to the lowercase enum labels.
 *
 * <p>Spring's default {@code String → Enum} converter matches {@link Enum#name()}, so
 * {@code ?category=cardio} would fail while {@code ?category=CARDIO} succeeded — the
 * opposite of the API's contract. Jackson's {@code @JsonValue} does not help here: it
 * governs request <em>bodies</em>, not URL binding. This closes that gap for every
 * {@link PgEnum} at once rather than one converter per enum.
 */
@Component
public class PgEnumConverterFactory implements ConverterFactory<String, PgEnum> {

    @Override
    public <T extends PgEnum> Converter<String, T> getConverter(Class<T> targetType) {
        return source -> {
            if (source == null || source.isBlank()) {
                return null;
            }
            for (T candidate : targetType.getEnumConstants()) {
                if (candidate.getValue().equalsIgnoreCase(source)) {
                    return candidate;
                }
            }
            // Surfaces as a 400 via MethodArgumentTypeMismatchException, matching the
            // Python backend's handling of an unknown enum value.
            throw new IllegalArgumentException(
                    "Unknown value '" + source + "' for " + targetType.getSimpleName());
        };
    }
}
