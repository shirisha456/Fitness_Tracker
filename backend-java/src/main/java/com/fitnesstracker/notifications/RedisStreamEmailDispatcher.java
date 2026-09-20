package com.fitnesstracker.notifications;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Enqueues transactional email onto a Redis stream — the replacement for Celery.
 *
 * <p>Redis is already deployed and was already Celery's broker, so this adds no new
 * infrastructure. It preserves the property the previous system actually had:
 * at-least-once delivery once accepted, surviving a worker crash. Kafka or RabbitMQ would
 * be new infrastructure for two email types.
 *
 * <p><b>Enqueue never fails the request.</b> The Python implementation wraps
 * {@code .delay()} in a catch-and-log specifically so that a Redis outage cannot take
 * registration and password reset down with it; email is the non-essential half of both
 * flows, and the user can recover via resend-verification or by requesting another link.
 * That behaviour is preserved deliberately.
 */
@Component
public class RedisStreamEmailDispatcher implements EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamEmailDispatcher.class);

    /**
     * Stream names are configurable rather than constant. In production one namespace is
     * enough; in tests each Spring context needs its own, because they share a Redis
     * container and would otherwise consume one another's messages.
     */
    private final String stream;
    private final String deadLetterStream;
    private final String retryQueue;
    private final String consumerGroup;

    static final String FIELD_KIND = "kind";
    static final String FIELD_RECIPIENT = "recipient";
    static final String FIELD_TOKEN = "token";
    static final String FIELD_ATTEMPTS = "attempts";

    private final StringRedisTemplate redis;
    private final long maxLength;
    private final Counter enqueued;
    private final Counter enqueueFailed;

    public RedisStreamEmailDispatcher(
            StringRedisTemplate redis,
            MeterRegistry meterRegistry,
            @Value("${app.email.stream-max-length:10000}") long maxLength,
            @Value("${app.email.stream:fitness:email}") String stream,
            @Value("${app.email.consumer-group:email-workers}") String consumerGroup) {
        this.redis = redis;
        this.maxLength = maxLength;
        this.stream = stream;
        this.deadLetterStream = stream + ":dead";
        this.retryQueue = stream + ":retry";
        this.consumerGroup = consumerGroup;
        this.enqueued = Counter.builder("email.enqueued")
                .description("Transactional emails accepted onto the queue")
                .register(meterRegistry);
        this.enqueueFailed = Counter.builder("email.enqueue.failed")
                .description("Enqueue attempts that could not reach Redis")
                .register(meterRegistry);
    }

    public String stream() {
        return stream;
    }

    public String deadLetterStream() {
        return deadLetterStream;
    }

    /**
     * Sorted set holding failed messages that are waiting out their backoff, scored by the
     * epoch-millisecond at which they become due. A stream cannot express "not before T",
     * so delayed retries live here until {@code EmailWorker} promotes them back.
     */
    public String retryQueue() {
        return retryQueue;
    }

    public String consumerGroup() {
        return consumerGroup;
    }

    long maxLength() {
        return maxLength;
    }

    @Override
    public void enqueue(EmailRequest request) {
        try {
            redis.opsForStream().add(MapRecord.create(stream, Map.of(
                    FIELD_KIND, request.kind().name(),
                    FIELD_RECIPIENT, request.recipient(),
                    FIELD_TOKEN, request.token(),
                    FIELD_ATTEMPTS, "0")));
            // Approximate trimming: bounded growth without the cost of exact trimming.
            redis.opsForStream().trim(stream, maxLength, true);
            enqueued.increment();
        } catch (Exception ex) {
            // Intentionally broad: any failure to reach Redis must degrade to "no email",
            // never to "registration failed". Logged without the token.
            enqueueFailed.increment();
            log.error("email_enqueue_failed {}", request.describe(), ex);
        }
    }
}
