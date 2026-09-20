package com.fitnesstracker.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fitnesstracker.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Redis Streams replacement for Celery, against a real Redis container.
 *
 * <p>Covers the properties the design claims: enqueue never fails the caller, a queued
 * message is delivered, a failing send is retried, a permanently failing send is
 * dead-lettered rather than looping forever, and nothing is lost when a consumer dies
 * without acknowledging.
 */
@Import(EmailDispatchTest.FlakySender.class)
class EmailDispatchTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void fastWorker(DynamicPropertyRegistry registry) {
        registry.add("app.email.worker.enabled", () -> "true");
        registry.add("app.email.poll-interval", () -> "PT0.1S");
        registry.add("app.email.claim-interval", () -> "PT0.2S");
        registry.add("app.email.claim-after", () -> "PT0.5S");
        registry.add("app.email.max-attempts", () -> "3");
        registry.add("app.email.retry-interval", () -> "PT0.1S");
        // Real backoff starts at 10s; compressed here so the suite does not wait it out.
        registry.add("app.email.retry-backoff", () -> "PT0.05S");
        registry.add("app.email.retry-backoff-max", () -> "PT0.4S");
    }

    /** A sender whose behaviour each test sets: succeed, or fail a given number of times. */
    @TestConfiguration
    static class FlakySender {

        @Bean
        @Primary
        ControllableSender controllableSender() {
            return new ControllableSender();
        }
    }

    static class ControllableSender implements EmailSender {
        final AtomicInteger attempts = new AtomicInteger();
        final List<EmailRequest> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int failuresRemaining;

        @Override
        public void send(EmailRequest request) {
            attempts.incrementAndGet();
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new EmailDeliveryException("simulated failure for " + request.describe(), null);
            }
            delivered.add(request);
        }

        void reset() {
            attempts.set(0);
            delivered.clear();
            failuresRemaining = 0;
        }
    }

    @Autowired private RedisStreamEmailDispatcher dispatcher;
    @Autowired private ControllableSender sender;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void resetQueue() {
        sender.reset();
        // Deleting the stream also destroys its consumer group, so it has to be recreated
        // or every XREADGROUP after this point fails with NOGROUP.
        redis.delete(dispatcher.stream());
        redis.delete(dispatcher.deadLetterStream());
        redis.delete(dispatcher.retryQueue());
        try {
            redis.opsForStream().createGroup(
                    dispatcher.stream(),
                    org.springframework.data.redis.connection.stream.ReadOffset.from("0"),
                    dispatcher.consumerGroup());
        } catch (Exception alreadyExists) {
            // BUSYGROUP on the first run of a class where the stream survived.
        }
    }

    private static EmailRequest request(String recipient) {
        return new EmailRequest(EmailKind.VERIFICATION, recipient, "raw-one-time-token");
    }

    @Test
    @DisplayName("a queued email is delivered by the worker")
    void deliversQueuedEmail() {
        dispatcher.enqueue(request("queued@example.com"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(sender.delivered)
                        .extracting(EmailRequest::recipient)
                        .contains("queued@example.com"));
    }

    @Test
    @DisplayName("a transient failure is retried until it succeeds")
    void retriesTransientFailures() {
        sender.failuresRemaining = 2;
        dispatcher.enqueue(request("retry@example.com"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(sender.delivered)
                        .extracting(EmailRequest::recipient)
                        .contains("retry@example.com"));
        assertThat(sender.attempts.get())
                .as("two failures then a success")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("a permanently failing email is dead-lettered, not retried forever")
    void deadLettersAfterMaxAttempts() {
        sender.failuresRemaining = Integer.MAX_VALUE;
        dispatcher.enqueue(request("poison@example.com"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Long dead = redis.opsForStream().size(dispatcher.deadLetterStream());
            assertThat(dead).as("the message must end up on the dead-letter stream").isEqualTo(1L);
        });

        int attemptsAfterDeadLetter = sender.attempts.get();
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(sender.attempts.get())
                        .as("no further attempts once dead-lettered")
                        .isEqualTo(attemptsAfterDeadLetter));
    }

    @Test
    @DisplayName("enqueue never throws, even when Redis is unreachable")
    void enqueueNeverFailsTheCaller() {
        // A dispatcher pointed at a closed port stands in for a broker outage. Registration
        // must still succeed without an email — the Python backend made the same choice.
        var brokenRedis = new StringRedisTemplate(
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        "127.0.0.1", 1) {{
                    afterPropertiesSet();
                }});
        var broken = new RedisStreamEmailDispatcher(
                brokenRedis, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                100, "unreachable:email", "unreachable-workers");

        // The assertion is the absence of an exception.
        broken.enqueue(request("outage@example.com"));
    }

    @Test
    @DisplayName("an unacknowledged message is reclaimed rather than lost")
    void reclaimsUnacknowledgedMessages() {
        // Simulates a consumer that read a message and died: read it under a different
        // consumer name without acking, then let XAUTOCLAIM recover it.
        dispatcher.enqueue(request("orphan@example.com"));
        redis.opsForStream().read(
                org.springframework.data.redis.connection.stream.Consumer.from(
                        dispatcher.consumerGroup(), "consumer-that-died"),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(10),
                org.springframework.data.redis.connection.stream.StreamOffset.create(
                        dispatcher.stream(),
                        org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(sender.delivered)
                        .extracting(EmailRequest::recipient)
                        .contains("orphan@example.com"));
    }

    @Test
    @DisplayName("a failed send waits out its backoff instead of retrying immediately")
    void failedSendsAreSpacedOut() {
        // Backoff here is 50ms doubling to a 400ms cap, against a 100ms poll. Without a
        // delay queue the worker would re-read the message on the very next tick and burn
        // all three attempts in well under a second.
        sender.failuresRemaining = 1;
        long start = System.nanoTime();
        dispatcher.enqueue(request("backoff@example.com"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(sender.delivered)
                        .extracting(EmailRequest::recipient)
                        .contains("backoff@example.com"));

        // The retry had to pass through the sorted set, so it cannot have been instant.
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("the retry waited for its backoff")
                .isGreaterThanOrEqualTo(Duration.ofMillis(50));
        assertThat(redis.opsForZSet().size(dispatcher.retryQueue()))
                .as("the retry queue is drained once delivery succeeds")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("a dead-lettered message keeps the recipient but not the live token")
    void deadLetteredMessagesCarryNoToken() {
        sender.failuresRemaining = Integer.MAX_VALUE;
        dispatcher.enqueue(request("poison-token@example.com"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(redis.opsForStream().size(dispatcher.deadLetterStream())).isEqualTo(1L));

        var dead = redis.opsForStream().range(
                dispatcher.deadLetterStream(),
                org.springframework.data.domain.Range.unbounded());
        assertThat(dead).hasSize(1);
        Map<Object, Object> values = dead.get(0).getValue();
        assertThat(values.get("recipient"))
                .as("operators still need to know who was affected")
                .isEqualTo("poison-token@example.com");
        assertThat(String.valueOf(values.get("token")))
                .as("nothing drains this stream, so the one-time token must not linger in it")
                .isEqualTo("(redacted)")
                .doesNotContain("raw-one-time-token");
    }

    @Test
    @DisplayName("the queued message carries no plaintext beyond the one-time token")
    void messageShapeIsMinimal() {
        EmailRequest request = request("shape@example.com");

        // describe() is what reaches logs: kind and a masked recipient, never the token.
        assertThat(request.describe())
                .contains("VERIFICATION")
                .contains("s***@example.com")
                .doesNotContain("raw-one-time-token");
    }
}
