package com.fitnesstracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling drives the email worker's poll and reclaim loops. There are no cron
// jobs: Celery Beat scheduled nothing, so nothing was migrated.
@EnableScheduling
@SpringBootApplication
public class FitnessTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FitnessTrackerApplication.class, args);
    }
}
