package com.fitnesstracker.notifications;

/** The two transactional emails auth actually sends. Nothing else is queued. */
public enum EmailKind {
    VERIFICATION,
    PASSWORD_RESET
}
