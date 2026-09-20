package com.fitnesstracker.notifications;

/**
 * Delivers a composed email. Two implementations, selected by {@code EMAIL_BACKEND},
 * matching the Python backend's {@code smtp} / {@code console} split.
 */
public interface EmailSender {

    void send(EmailRequest request);
}
