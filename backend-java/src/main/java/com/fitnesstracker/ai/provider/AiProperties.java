package com.fitnesstracker.ai.provider;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI provider configuration, read from the same environment variables the Python backend
 * uses so one {@code .env} configures both while they run side by side.
 *
 * @param apiKey null or blank means the feature is unconfigured, not broken
 * @param model the completion model
 * @param baseUrl overridable so a stub can stand in during end-to-end validation
 * @param timeout <b>explicit, always.</b> The OpenAI SDK defaults to 600s; one hung
 *     upstream request would otherwise occupy a worker for ten minutes
 * @param maxConcurrentRequests hard ceiling on in-flight calls — virtual threads make it
 *     cheaper to pile requests onto a degraded provider, not safer, so the bound is
 *     explicit rather than emergent
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        String apiKey,
        String model,
        String baseUrl,
        Duration timeout,
        int maxConcurrentRequests) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
