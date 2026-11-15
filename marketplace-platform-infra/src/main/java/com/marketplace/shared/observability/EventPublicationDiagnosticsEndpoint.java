package com.marketplace.shared.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.TargetEventPublication;

/**
 * Read-only operator view of old, incomplete Spring Modulith event publications.
 *
 * <p>Only publication metadata is returned. The serialized event body is
 * deliberately never included because authentication and notification events
 * can contain one-time links or other sensitive values. Access is inherited
 * from the application's existing ADMIN-only {@code /actuator/**} security
 * rule.
 */
@Component
@Endpoint(id = "eventpublications")
public class EventPublicationDiagnosticsEndpoint {

    static final Duration STALE_THRESHOLD = Duration.ofHours(6);

    private final EventPublicationRegistry registry;

    public EventPublicationDiagnosticsEndpoint(EventPublicationRegistry registry) {
        this.registry = registry;
    }

    @ReadOperation
    public Report report() {
        Instant measuredAt = Instant.now();
        List<Publication> publications = registry.findIncompletePublicationsOlderThan(STALE_THRESHOLD)
                .stream()
                .map(publication -> toPublication(publication, measuredAt))
                .toList();

        return new Report(measuredAt, STALE_THRESHOLD.toSeconds(), publications.size(), publications);
    }

    private static Publication toPublication(TargetEventPublication publication, Instant measuredAt) {
        Instant publishedAt = publication.getPublicationDate();
        long ageSeconds = Math.max(0, Duration.between(publishedAt, measuredAt).toSeconds());

        return new Publication(
                publication.getIdentifier(),
                publication.getEvent().getClass().getName(),
                publication.getTargetIdentifier().toString(),
                publication.getStatus() == null ? "LEGACY_UNCLASSIFIED" : publication.getStatus().name(),
                publishedAt,
                ageSeconds,
                publication.getCompletionAttempts(),
                publication.getLastResubmissionDate());
    }

    public record Report(
            Instant measuredAt,
            long staleThresholdSeconds,
            int count,
            List<Publication> publications) {
    }

    public record Publication(
            java.util.UUID id,
            String eventType,
            String listener,
            String status,
            Instant publishedAt,
            long ageSeconds,
            int completionAttempts,
            Instant lastResubmissionAt) {
    }
}
