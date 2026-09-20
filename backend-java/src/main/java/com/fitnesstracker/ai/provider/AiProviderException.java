package com.fitnesstracker.ai.provider;

/**
 * An upstream AI failure, pre-classified so the service layer does not inspect vendor
 * exception types.
 *
 * <p>The two kinds map onto the status codes the Python backend already returns, which the
 * frontend distinguishes:
 *
 * <ul>
 *   <li>{@link Kind#UNAVAILABLE} → <b>503</b> — not configured, bad key, rate limit or
 *       quota. The feature is off right now; retrying later may work.
 *   <li>{@link Kind#BAD_RESPONSE} → <b>502</b> — the provider answered with something
 *       unusable: malformed JSON, a truncated response, or a shape that fails validation.
 * </ul>
 */
public class AiProviderException extends RuntimeException {

    public enum Kind {
        UNAVAILABLE,
        BAD_RESPONSE
    }

    private final Kind kind;

    public AiProviderException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AiProviderException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public static AiProviderException notConfigured() {
        return new AiProviderException(Kind.UNAVAILABLE, "AI features are not configured.");
    }

    public static AiProviderException misconfigured() {
        return new AiProviderException(Kind.UNAVAILABLE, "AI service is misconfigured.");
    }

    public static AiProviderException rateLimited() {
        return new AiProviderException(
                Kind.UNAVAILABLE,
                "AI service is temporarily unavailable (rate limit or quota exceeded).");
    }

    public static AiProviderException requestFailed(Throwable cause) {
        return new AiProviderException(Kind.BAD_RESPONSE, "AI service request failed.", cause);
    }

    public static AiProviderException malformedResponse() {
        return new AiProviderException(
                Kind.BAD_RESPONSE, "AI service returned a malformed response.");
    }

    public static AiProviderException unexpectedShape(Throwable cause) {
        return new AiProviderException(
                Kind.BAD_RESPONSE, "AI service returned an unexpected response.", cause);
    }
}
