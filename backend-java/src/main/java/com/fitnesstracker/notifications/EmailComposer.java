package com.fitnesstracker.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the message bodies, reproducing {@code app/core/email.py} exactly.
 *
 * <p>Wording and links are part of the user-visible product, so they are copied rather
 * than improved: a user mid-flow when traffic switches to Java should not notice.
 */
@Component
public class EmailComposer {

    private final String frontendUrl;

    public EmailComposer(@Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.frontendUrl = frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1)
                : frontendUrl;
    }

    public record Composed(String subject, String text, String html) {}

    public Composed compose(EmailRequest request) {
        return switch (request.kind()) {
            case VERIFICATION -> verification(request.token());
            case PASSWORD_RESET -> passwordReset(request.token());
        };
    }

    private Composed verification(String token) {
        String link = frontendUrl + "/verify-email?token=" + token;
        return new Composed(
                "Verify your Fitness Tracker email",
                """
                Welcome to Fitness Tracker!

                Please verify your email by opening this link:
                %s

                If you did not create an account, ignore this email.
                """.formatted(link),
                "<p>Welcome to Fitness Tracker!</p>"
                        + "<p><a href=\"" + link + "\">Verify your email</a></p>"
                        + "<p>If you did not create an account, ignore this email.</p>");
    }

    private Composed passwordReset(String token) {
        String link = frontendUrl + "/reset-password?token=" + token;
        return new Composed(
                "Reset your Fitness Tracker password",
                """
                Fitness Tracker password reset

                Reset your password using this link (expires in 1 hour):
                %s

                If you did not request this, ignore this email.
                """.formatted(link),
                "<p>Fitness Tracker password reset</p>"
                        + "<p><a href=\"" + link + "\">Reset your password</a></p>"
                        + "<p>This link expires in 1 hour. If you did not request this, "
                        + "ignore this email.</p>");
    }
}
