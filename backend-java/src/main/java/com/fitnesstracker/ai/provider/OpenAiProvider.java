package com.fitnesstracker.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI over plain HTTP.
 *
 * <p>Deliberately not a vendor SDK: the previous implementation makes ordinary
 * {@code chat/completions} calls, so a {@link RestClient} is fewer dependencies, keeps the
 * vendor boundary honest, and respects {@code OPENAI_BASE_URL} — which is what lets a stub
 * stand in for end-to-end validation without a paid call.
 *
 * <h2>Failure isolation</h2>
 *
 * Three mechanisms, each doing one job, because none substitutes for another:
 *
 * <ul>
 *   <li><b>Timeouts</b> — connect and read, from configuration. A virtual thread waiting
 *       forever is still waiting forever; cheap threads are not a timeout.
 *   <li><b>A concurrency bound</b> — a semaphore caps in-flight calls. Virtual threads make
 *       it <em>easier</em> to pile thousands of requests onto a degraded provider, so the
 *       ceiling is explicit. Over the limit, fail fast as unavailable rather than queue.
 *   <li><b>Error translation</b> — vendor failures become {@link AiProviderException}, so
 *       nothing downstream inspects HTTP codes or SDK types.
 * </ul>
 *
 * <p>No database transaction is ever open across a call to this class; callers read what
 * the prompt needs and commit first.
 */
@Component
@EnableConfigurationProperties(AiProperties.class)
public class OpenAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Semaphore inFlight;

    private final Timer latency;
    private final Counter failures;
    private final Counter rejected;

    public OpenAiProvider(
            AiProperties properties, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.inFlight = new Semaphore(properties.maxConcurrentRequests());

        // JdkClientHttpRequestFactory (java.net.http.HttpClient), not the legacy
        // SimpleClientHttpRequestFactory: HttpURLConnection streams the request body
        // chunked and has long-standing quirks around connection reuse. The JDK client
        // sends a Content-Length for a buffered body and handles the connect timeout on
        // the client itself.
        Duration timeout = properties.timeout();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();

        this.latency = Timer.builder("ai.request.duration")
                .description("Time spent waiting on the AI provider")
                .register(meterRegistry);
        this.failures = Counter.builder("ai.request.failed")
                .description("AI provider calls that failed")
                .register(meterRegistry);
        this.rejected = Counter.builder("ai.request.rejected")
                .description("AI calls refused because the concurrency bound was reached")
                .register(meterRegistry);
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public String complete(List<Message> messages, int maxTokens) {
        return call(messages, maxTokens, false);
    }

    @Override
    public String completeJson(List<Message> messages, int maxTokens) {
        return call(messages, maxTokens, true);
    }

    private String call(List<Message> messages, int maxTokens, boolean jsonMode) {
        if (!properties.isConfigured()) {
            throw AiProviderException.notConfigured();
        }
        // Fail fast rather than queue: a degraded provider should shed load, and the
        // caller's 503 is more useful to a user than a request that eventually times out.
        if (!inFlight.tryAcquire()) {
            rejected.increment();
            throw new AiProviderException(
                    AiProviderException.Kind.UNAVAILABLE,
                    "AI service is temporarily unavailable (too many concurrent requests).");
        }

        long start = System.nanoTime();
        try {
            Map<String, Object> body = buildBody(messages, maxTokens, jsonMode);
            String response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        String text = new String(clientResponse.getBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        if (status.isError()) {
                            throw translate(status);
                        }
                        return text;
                    });
            return extractContent(response);
        } catch (AiProviderException ex) {
            failures.increment();
            throw ex;
        } catch (Exception ex) {
            failures.increment();
            // Never log the prompt: it can contain a user's training history.
            // The prompt is never logged — it can contain a user's training history —
            // but the failure needs to be diagnosable, so the cause chain is.
            log.warn("ai_request_failed reason={} detail={}",
                    ex.getClass().getSimpleName(),
                    ex.getCause() == null ? ex.getMessage() : ex.getCause().toString());
            throw AiProviderException.requestFailed(ex);
        } finally {
            inFlight.release();
            latency.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private Map<String, Object> buildBody(
            List<Message> messages, int maxTokens, boolean jsonMode) {
        List<Map<String, String>> payload = new ArrayList<>();
        for (Message message : messages) {
            payload.add(Map.of("role", message.role(), "content", message.content()));
        }
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", properties.model());
        body.put("messages", payload);
        body.put("max_tokens", maxTokens);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    /** Maps upstream status onto the same 503/502 split the previous implementation uses. */
    private static AiProviderException translate(HttpStatusCode status) {
        if (status.value() == 401 || status.value() == 403) {
            return AiProviderException.misconfigured();
        }
        if (status.value() == 429) {
            return AiProviderException.rateLimited();
        }
        return new AiProviderException(
                AiProviderException.Kind.BAD_RESPONSE, "AI service request failed.");
    }

    private String extractContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            // A response truncated at max_tokens is still valid HTTP but useless here.
            return content.isMissingNode() || content.isNull() ? "" : content.asText();
        } catch (Exception ex) {
            throw AiProviderException.malformedResponse();
        }
    }
}
