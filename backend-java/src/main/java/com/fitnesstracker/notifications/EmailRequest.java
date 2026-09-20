package com.fitnesstracker.notifications;

/**
 * A queued email.
 *
 * <p>{@code token} is the raw one-time token that goes in the link. It is never logged and
 * never appears in an error message; only {@link #kind()} and a redacted recipient do.
 */
public record EmailRequest(EmailKind kind, String recipient, String token) {

    /** Safe for logs: kind plus a masked recipient, never the token. */
    public String describe() {
        return kind + " to=" + maskEmail(recipient);
    }

    static String maskEmail(String email) {
        if (email == null) {
            return "(none)";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
