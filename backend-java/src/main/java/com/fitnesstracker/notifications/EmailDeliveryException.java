package com.fitnesstracker.notifications;

/** Delivery failed in a way the worker should retry. Never carries token material. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
