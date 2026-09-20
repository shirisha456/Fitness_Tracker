package com.fitnesstracker.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings, mirroring the Python backend's {@code Settings} fields.
 *
 * <p>The TTLs and the algorithm are read from the same environment variables the Python
 * service uses, so a single {@code .env} configures both while they run side by side.
 *
 * @param secret shared HMAC secret — the same {@code SECRET_KEY} the Python backend signs with
 * @param accessTokenExpireMinutes access-token lifetime (Python default: 15)
 * @param refreshTokenExpireDays refresh-token lifetime (Python default: 7)
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret, int accessTokenExpireMinutes, int refreshTokenExpireDays) {}
