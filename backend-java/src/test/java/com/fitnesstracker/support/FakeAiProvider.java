package com.fitnesstracker.support;

import com.fitnesstracker.ai.provider.AiProvider;
import com.fitnesstracker.ai.provider.AiProviderException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A scriptable stand-in for the model.
 *
 * <p><b>No automated test ever calls the real provider.</b> Beyond the obvious cost, a
 * paid dependency makes tests slow, flaky and non-deterministic — and the thing worth
 * testing is our handling of what comes back, not the model.
 *
 * <p>Records the prompts it was given, so tests can assert that a prompt carries computed
 * facts rather than raw history.
 */
@TestConfiguration
public class FakeAiProvider {

    @Bean
    @Primary
    ScriptedProvider scriptedProvider() {
        return new ScriptedProvider();
    }

    public static class ScriptedProvider implements AiProvider {

        private final List<List<Message>> prompts = new CopyOnWriteArrayList<>();
        private volatile String nextResponse = "";
        private volatile AiProviderException nextFailure;
        private volatile boolean configured = true;

        public void respondWith(String response) {
            this.nextResponse = response;
            this.nextFailure = null;
        }

        public void failWith(AiProviderException failure) {
            this.nextFailure = failure;
        }

        public void setConfigured(boolean configured) {
            this.configured = configured;
        }

        public List<List<Message>> prompts() {
            return List.copyOf(prompts);
        }

        /** The most recent user-role prompt — what the model was actually shown. */
        public String lastUserPrompt() {
            List<Message> last = prompts.get(prompts.size() - 1);
            return last.stream().filter(message -> "user".equals(message.role()))
                    .map(Message::content).reduce((first, second) -> second).orElse("");
        }

        public String lastSystemPrompt() {
            List<Message> last = prompts.get(prompts.size() - 1);
            return last.stream().filter(message -> "system".equals(message.role()))
                    .map(Message::content).findFirst().orElse("");
        }

        public void reset() {
            prompts.clear();
            nextResponse = "";
            nextFailure = null;
            configured = true;
        }

        @Override
        public String complete(List<Message> messages, int maxTokens) {
            return record(messages);
        }

        @Override
        public String completeJson(List<Message> messages, int maxTokens) {
            return record(messages);
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        private String record(List<Message> messages) {
            prompts.add(new ArrayList<>(messages));
            if (!configured) {
                throw AiProviderException.notConfigured();
            }
            if (nextFailure != null) {
                throw nextFailure;
            }
            return nextResponse;
        }
    }
}
