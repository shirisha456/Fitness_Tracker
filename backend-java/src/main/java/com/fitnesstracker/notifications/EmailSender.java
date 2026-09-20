package com.fitnesstracker.notifications;

/**
 * Delivers a composed email. Two implementations, selected by {@code EMAIL_BACKEND},
 * matching the previous implementation's {@code smtp} / {@code console} split.
 */
public interface EmailSender {

    void send(EmailRequest request);
}
