package com.fitnesstracker.notifications;

/**
 * Hands a transactional email off for asynchronous delivery.
 *
 * <p>An interface so that tests exercising auth can substitute a synchronous double and
 * assert on the resulting message, while the Redis Streams path gets its own focused
 * tests. Implementations must never throw: email is the non-essential half of
 * registration and password reset, and a broker outage must not fail those requests.
 */
public interface EmailDispatcher {

    void enqueue(EmailRequest request);
}
