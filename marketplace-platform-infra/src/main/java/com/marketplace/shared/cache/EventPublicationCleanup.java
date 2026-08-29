package com.marketplace.shared.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodically purges completed event publications from the registry.
 *
 * <p>Official source — Spring Modulith 2.1.1 Reference:
 * <a href="https://docs.spring.io/spring-modulith/reference/events.html#publication-registry.completion">
 * Event Publication Completion</a>:
 * "Completed publications will remain in the Event Publication Registry so that
 * they can be inspected through the {@code CompletedEventPublications} interface.
 * A consequence of this is that you'll need to put some code in place that will
 * periodically purge old, completed {@code EventPublication}s. Otherwise, the
 * persistent abstraction of them, for example a relational database table, will
 * grow unbounded and the interaction with the store creating and completing new
 * {@code EventPublication} might slow down."
 *
 * <p>This scheduler uses {@code completion-mode: archive} (configured in
 * application.yml), which copies completed entries to an archive table.
 * This task purges archive entries older than 7 days.
 */
@Component
public class EventPublicationCleanup {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationCleanup.class);

    private final CompletedEventPublications completedPublications;

    public EventPublicationCleanup(CompletedEventPublications completedPublications) {
        this.completedPublications = completedPublications;
    }

    /**
     * Deletes completed event publications older than 7 days.
     * Runs daily at 03:00 UTC (off-peak hours).
     *
     * <p>Official source — Spring Modulith 2.1.1 Reference:
     * {@code CompletedEventPublications.deleteByCompletionDateBefore(Duration)}
     * "purge all of them from the database or the completed publications older
     * than a given duration (for example, 1 minute)."
     */
    @Scheduled(cron = "0 0 3 * * ?", zone = "UTC")
    void purgeOldPublications() {
        Duration maxAge = Duration.ofDays(7);
        completedPublications.deletePublicationsOlderThan(maxAge);
        log.debug("Purged completed event publications older than {}", maxAge);
    }
}
