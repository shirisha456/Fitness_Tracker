package com.fitnesstracker.notifications;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Logs instead of sending, and keeps the messages in memory so tests can assert on them.
 *
 * <p>The Python backend has the same `console` backend with the same in-memory capture;
 * tests in both languages assert against it rather than against a mail server, and no
 * automated test ever sends a real email.
 */
@Component
@ConditionalOnProperty(name = "app.email.backend", havingValue = "console")
public class ConsoleEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailSender.class);

    private final EmailComposer composer;
    private final List<SentEmail> sent = new CopyOnWriteArrayList<>();

    public ConsoleEmailSender(EmailComposer composer) {
        this.composer = composer;
    }

    /** The captured message. {@code text} contains the link, which tests need. */
    public record SentEmail(EmailKind kind, String recipient, String subject, String text) {}

    @Override
    public void send(EmailRequest request) {
        EmailComposer.Composed composed = composer.compose(request);
        sent.add(new SentEmail(
                request.kind(), request.recipient(), composed.subject(), composed.text()));
        log.info("console_email {} subject={}", request.describe(), composed.subject());
    }

    public List<SentEmail> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }
}
