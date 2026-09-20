package com.fitnesstracker.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the email stream. The Celery worker's replacement.
 *
 * <p>Delivery semantics, spelled out because "it uses a queue" is not a design:
 *
 * <ul>
 *   <li><b>Enqueue</b> — {@code XADD}, best-effort (see {@link RedisStreamEmailDispatcher}).
 *   <li><b>Consume</b> — {@code XREADGROUP} on group {@code email-workers}; each instance
 *       is one named consumer, so running several does not duplicate work.
 *   <li><b>Acknowledge</b> — {@code XACK} only after the send returns. A crash mid-send
 *       leaves the entry pending, not lost.
 *   <li><b>Recover</b> — {@code XAUTOCLAIM} reclaims entries pending longer than
 *       {@code app.email.claim-after}; that is the crashed-worker path.
 *   <li><b>Retry</b> — an attempt counter rides on the message. A failed send is parked
 *       in a sorted set scored by its due time and promoted back onto the stream once the
 *       backoff elapses, so attempts are spread over minutes rather than burned in a few
 *       seconds. Exponential from {@code app.email.retry-backoff}, capped at
 *       {@code app.email.retry-backoff-max}.
 *   <li><b>Give up</b> — past {@code app.email.max-attempts} the message moves to
 *       {@code fitness:email:dead} and is acked, so one poison message cannot loop
 *       forever. The one-time token is <b>stripped</b> on the way out: a dead-lettered
 *       message is never delivered, so keeping a live credential in a stream nobody drains
 *       is a liability with no upside.
 *   <li><b>Idempotency</b> — at-least-once is safe here: a duplicate verification email
 *       contains the same one-time token, which is single-use at the database level.
 *   <li><b>Shutdown</b> — the loop stops accepting work and lets the in-flight send finish;
 *       anything unacked is recovered by {@code XAUTOCLAIM} on the next start.
 * </ul>
 *
 * <p>Runs inside the API process rather than as a separate container: two email types at
 * this volume do not justify a second deployable, and the migration plan's rule is that
 * new infrastructure needs a real reason.
 */
@Component
@ConditionalOnProperty(name = "app.email.worker.enabled", havingValue = "true", matchIfMissing = true)
public class EmailWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);

    private final StringRedisTemplate redis;
    private final EmailSender sender;
    private final RedisStreamEmailDispatcher dispatcher;
    private final String consumerName;
    private final int maxAttempts;
    private final int batchSize;
    private final Duration claimAfter;
    private final Duration retryBackoff;
    private final Duration retryBackoffMax;
    private final ObjectMapper json;

    private final Counter sent;
    private final Counter failed;
    private final Counter deadLettered;
    private final Counter reclaimed;
    private final Counter retriesScheduled;
    private final Timer sendTimer;

    private final AtomicBoolean running = new AtomicBoolean(true);

    /** Distinguishes two sorted-set members that would otherwise be byte-identical. */
    private static final String FIELD_RETRY_ID = "retry_id";

    public EmailWorker(
            StringRedisTemplate redis,
            EmailSender sender,
            RedisStreamEmailDispatcher dispatcher,
            MeterRegistry meterRegistry,
            @Value("${app.email.max-attempts:5}") int maxAttempts,
            @Value("${app.email.batch-size:10}") int batchSize,
            @Value("${app.email.claim-after:PT2M}") Duration claimAfter,
            @Value("${app.email.retry-backoff:PT10S}") Duration retryBackoff,
            @Value("${app.email.retry-backoff-max:PT10M}") Duration retryBackoffMax,
            ObjectMapper json) {
        this.redis = redis;
        this.sender = sender;
        this.dispatcher = dispatcher;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.claimAfter = claimAfter;
        this.retryBackoff = retryBackoff;
        this.retryBackoffMax = retryBackoffMax;
        this.json = json;
        // Distinct per process so several instances can share the group safely.
        this.consumerName = "worker-" + java.util.UUID.randomUUID();

        this.sent = Counter.builder("email.sent")
                .description("Emails delivered successfully").register(meterRegistry);
        this.failed = Counter.builder("email.failed")
                .description("Delivery attempts that failed and will be retried")
                .register(meterRegistry);
        this.deadLettered = Counter.builder("email.dead_lettered")
                .description("Emails abandoned after exhausting retries").register(meterRegistry);
        this.reclaimed = Counter.builder("email.reclaimed")
                .description("Entries reclaimed from a stalled or crashed consumer")
                .register(meterRegistry);
        this.retriesScheduled = Counter.builder("email.retry.scheduled")
                .description("Failed sends parked for a later attempt").register(meterRegistry);
        this.sendTimer = Timer.builder("email.send.duration")
                .description("Time spent in the email sender").register(meterRegistry);
    }

    @PostConstruct
    void ensureConsumerGroup() {
        try {
            redis.opsForStream().createGroup(dispatcher.stream(), ReadOffset.from("0"),
                    dispatcher.consumerGroup());
        } catch (Exception ex) {
            // BUSYGROUP — the group already exists, which is the normal case after the
            // first start. Anything else surfaces on the first poll.
            log.debug("email consumer group not created (likely already exists): {}",
                    ex.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
    }

    /** Polls for new work. Fixed delay, so a slow batch does not overlap itself. */
    @Scheduled(fixedDelayString = "${app.email.poll-interval:PT1S}")
    public void poll() {
        if (!running.get()) {
            return;
        }
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(dispatcher.consumerGroup(), consumerName),
                    StreamReadOptions.empty().count(batchSize),
                    StreamOffset.create(dispatcher.stream(), ReadOffset.lastConsumed()));
            if (records != null) {
                records.forEach(this::process);
            }
        } catch (Exception ex) {
            // A Redis outage must not kill the scheduler thread; the next tick retries.
            log.warn("email_poll_failed: {}", ex.getMessage());
        }
    }

    /** Reclaims entries a crashed consumer never acked. */
    @Scheduled(fixedDelayString = "${app.email.claim-interval:PT30S}")
    public void reclaimStalled() {
        if (!running.get()) {
            return;
        }
        try {
            String[] pending = pendingIds();
            if (pending.length == 0) {
                // XCLAIM with no ids is an error, and the common case is an empty backlog.
                return;
            }
            var claimed = redis.opsForStream().claim(
                    dispatcher.stream(), dispatcher.consumerGroup(), consumerName,
                    XClaimOptions.minIdle(claimAfter).ids(pending));
            if (claimed != null && !claimed.isEmpty()) {
                reclaimed.increment(claimed.size());
                log.info("email_reclaimed count={}", claimed.size());
                claimed.forEach(this::process);
            }
        } catch (Exception ex) {
            log.warn("email_reclaim_failed: {}", ex.getMessage());
        }
    }

    private String[] pendingIds() {
        var pending = redis.opsForStream()
                .pending(dispatcher.stream(), dispatcher.consumerGroup(),
                        org.springframework.data.domain.Range.unbounded(), (long) batchSize);
        if (pending == null || pending.isEmpty()) {
            return new String[0];
        }
        return pending.stream().map(p -> p.getId().getValue()).toArray(String[]::new);
    }

    private void process(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        EmailRequest request;
        int attempts;
        try {
            request = new EmailRequest(
                    EmailKind.valueOf(String.valueOf(values.get(RedisStreamEmailDispatcher.FIELD_KIND))),
                    String.valueOf(values.get(RedisStreamEmailDispatcher.FIELD_RECIPIENT)),
                    String.valueOf(values.get(RedisStreamEmailDispatcher.FIELD_TOKEN)));
            attempts = Integer.parseInt(
                    String.valueOf(values.getOrDefault(RedisStreamEmailDispatcher.FIELD_ATTEMPTS, "0")));
        } catch (Exception ex) {
            // Unparseable entry: dead-letter immediately rather than retry forever.
            log.error("email_message_unreadable id={}", record.getId());
            deadLetter(record, null);
            return;
        }

        try {
            sendTimer.recordCallable(() -> {
                sender.send(request);
                return null;
            });
            sent.increment();
            ack(record.getId());
        } catch (Exception ex) {
            failed.increment();
            int next = attempts + 1;
            log.warn("email_send_failed attempt={} {} reason={}",
                    next, request.describe(), ex.getMessage());
            if (next >= maxAttempts) {
                deadLetter(record, request);
            } else {
                scheduleRetry(values, next);
                ack(record.getId());
            }
        }
    }

    /**
     * Parks a failed message until its backoff elapses.
     *
     * <p>Re-adding it to the stream immediately would let the 1s poll burn every attempt
     * within a few seconds, which is no defence against the thing retries exist for — a
     * mail host that is briefly unreachable. A stream has no notion of "not before T", so
     * the message waits in a sorted set scored by its due time.
     */
    private void scheduleRetry(Map<Object, Object> values, int attempts) {
        Map<String, String> retried = new LinkedHashMap<>();
        values.forEach((k, v) -> retried.put(String.valueOf(k), String.valueOf(v)));
        retried.put(RedisStreamEmailDispatcher.FIELD_ATTEMPTS, String.valueOf(attempts));
        // Two failures of the same message would otherwise be one sorted-set member.
        retried.put(FIELD_RETRY_ID, java.util.UUID.randomUUID().toString());
        try {
            long dueAt = System.currentTimeMillis() + backoffFor(attempts).toMillis();
            redis.opsForZSet().add(dispatcher.retryQueue(), json.writeValueAsString(retried), dueAt);
            retriesScheduled.increment();
        } catch (Exception ex) {
            // Losing the retry is better than losing the worker. The message was already
            // acked as a failure, and the user can request another link.
            log.error("email_retry_schedule_failed attempt={}", attempts, ex);
        }
    }

    /** Exponential, capped: backoff * 2^(attempt-1). */
    private Duration backoffFor(int attempt) {
        Duration delay = retryBackoff.multipliedBy(1L << Math.min(attempt - 1, 20));
        return delay.compareTo(retryBackoffMax) > 0 ? retryBackoffMax : delay;
    }

    /** Moves retries whose backoff has elapsed back onto the stream. */
    @Scheduled(fixedDelayString = "${app.email.retry-interval:PT1S}")
    public void promoteDueRetries() {
        if (!running.get()) {
            return;
        }
        try {
            Set<String> due = redis.opsForZSet().rangeByScore(
                    dispatcher.retryQueue(), 0, System.currentTimeMillis(), 0, batchSize);
            if (due == null || due.isEmpty()) {
                return;
            }
            for (String member : due) {
                // Remove first: if the XADD then fails we drop one retry, whereas removing
                // second could replay the same message on every tick.
                Long removed = redis.opsForZSet().remove(dispatcher.retryQueue(), member);
                if (removed == null || removed == 0) {
                    continue; // another instance got there first
                }
                Map<String, String> values =
                        json.readValue(member, new TypeReference<Map<String, String>>() {});
                values.remove(FIELD_RETRY_ID);
                redis.opsForStream().add(MapRecord.create(dispatcher.stream(),
                        new LinkedHashMap<Object, Object>(values)));
            }
        } catch (Exception ex) {
            log.warn("email_retry_promotion_failed: {}", ex.getMessage());
        }
    }

    private void deadLetter(MapRecord<String, Object, Object> record, EmailRequest request) {
        try {
            // The token is redacted, not carried over. Nothing drains the dead-letter
            // stream, so anything kept here is kept indefinitely; a live single-use
            // credential does not belong in that category. Operators need to know that a
            // message was abandoned and to whom, not how to complete its link.
            Map<Object, Object> redacted = new HashMap<>(record.getValue());
            redacted.put(RedisStreamEmailDispatcher.FIELD_TOKEN, "(redacted)");
            redis.opsForStream().add(
                    MapRecord.create(dispatcher.deadLetterStream(), redacted));
            redis.opsForStream().trim(dispatcher.deadLetterStream(), dispatcher.maxLength(), true);
            deadLettered.increment();
            log.error("email_dead_lettered {}",
                    request == null ? "unreadable message" : request.describe());
        } finally {
            ack(record.getId());
        }
    }

    private void ack(RecordId id) {
        redis.opsForStream().acknowledge(dispatcher.stream(), dispatcher.consumerGroup(), id);
    }
}
