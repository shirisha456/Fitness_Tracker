package com.fitnesstracker.config;

import com.fitnesstracker.common.web.PgEnumConverterFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the lowercase-label enum binding for query parameters and path variables. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PgEnumConverterFactory pgEnumConverterFactory;

    public WebMvcConfig(PgEnumConverterFactory pgEnumConverterFactory) {
        this.pgEnumConverterFactory = pgEnumConverterFactory;
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(pgEnumConverterFactory);
    }
}
