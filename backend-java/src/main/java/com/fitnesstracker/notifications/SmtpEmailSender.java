package com.fitnesstracker.notifications;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Real SMTP — Mailhog in development, a provider in production. */
@Component
@ConditionalOnProperty(name = "app.email.backend", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final EmailComposer composer;
    private final String from;

    public SmtpEmailSender(
            JavaMailSender mailSender,
            EmailComposer composer,
            @Value("${app.email.from:Fitness Tracker <noreply@fitness-tracker.local>}") String from) {
        this.mailSender = mailSender;
        this.composer = composer;
        this.from = from;
    }

    @Override
    public void send(EmailRequest request) {
        EmailComposer.Composed composed = composer.compose(request);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(request.recipient());
            helper.setSubject(composed.subject());
            helper.setText(composed.text(), composed.html());
            mailSender.send(message);
        } catch (Exception ex) {
            // Rethrown so the worker retries. The message carries no token — only the kind
            // and a masked recipient — because it will be logged.
            throw new EmailDeliveryException("Could not send " + request.describe(), ex);
        }
        log.info("smtp_email_sent {}", request.describe());
    }
}
