package com.fitnesstracker.support;

import com.fitnesstracker.notifications.EmailDispatcher;
import com.fitnesstracker.notifications.EmailRequest;
import com.fitnesstracker.notifications.EmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Delivers immediately instead of going through Redis.
 *
 * <p>Tests about <em>auth</em> care that the right email was composed for the right
 * address, not that Redis Streams work; making them wait on a polling worker would make
 * them slow and flaky for no extra coverage. The real dispatcher and worker — enqueue,
 * consume, acknowledge, retry, dead-letter, crash recovery — get their own focused test.
 */
@TestConfiguration
public class SynchronousEmailDispatcher {

    @Bean
    @Primary
    EmailDispatcher synchronousEmailDispatcher(EmailSender sender) {
        return sender::send;
    }
}
