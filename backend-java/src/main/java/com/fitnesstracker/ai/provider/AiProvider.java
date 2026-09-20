package com.fitnesstracker.ai.provider;

import java.util.List;

/**
 * The boundary between business logic and whichever model vendor is behind it.
 *
 * <p>Business code depends on this interface, never on an SDK. Two implementations exist:
 * {@code OpenAiProvider} for production and {@code FakeAiProvider} for tests — automated
 * tests never call a paid provider.
 *
 * <p>Implementations are responsible for their own timeouts and for translating vendor
 * errors into {@link AiProviderException}. They are <em>not</em> responsible for deciding
 * anything: the caller supplies already-computed facts and the model returns prose or a
 * structured suggestion.
 */
public interface AiProvider {

    /** A chat turn, in the shape both the API and the provider use. */
    record Message(String role, String content) {

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }
    }

    /**
     * Free-text completion.
     *
     * @throws AiProviderException on any upstream failure, already classified
     */
    String complete(List<Message> messages, int maxTokens);

    /**
     * JSON-mode completion. The raw JSON string is returned; validating it against a DTO
     * is the caller's job, because only the caller knows the expected shape.
     */
    String completeJson(List<Message> messages, int maxTokens);

    /** Whether the provider is configured at all. False means "no API key". */
    boolean isConfigured();
}
