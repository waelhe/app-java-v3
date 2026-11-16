package com.marketplace.shared.observability;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only operator view of old, incomplete Spring Modulith event publications.
 *
 * <p>The query reads persisted publication metadata only. It deliberately
 * avoids the serialized event column and Spring Modulith's
 * {@code TargetEventPublication.getEvent()}, which lazily deserializes the
 * stored payload. Legacy event types can therefore still be diagnosed even
 * when their original classes are no longer present or compatible.
 *
 * <p>Access is inherited from the application's existing ADMIN-only
 * {@code /actuator/**} security rule.
 */
@Component
@Endpoint(id = "eventpublications")
public class EventPublicationDiagnosticsEndpoint {

    static final Duration STALE_THRESHOLD = Duration.ofHours(6);

    private static final String FIND_STALE_PUBLICATIONS = """
            SELECT id,
                   event_type,
                   listener_id,
                   publication_date,
                   completion_date,
                   status,
                   completion_attempts,
                   last_resubmission_date
              FROM event_publication
             WHERE completion_date IS NULL
               AND publication_date < ?
             ORDER BY publication_date ASC
            """;

    private final JdbcTemplate jdbcTemplate;

    public EventPublicationDiagnosticsEndpoint(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @ReadOperation
    public Report report() {
        Instant measuredAt = Instant.now();
        Instant cutoff = measuredAt.minus(STALE_THRESHOLD);

        List<Publication> publications = jdbcTemplate.query(
                FIND_STALE_PUBLICATIONS,
                statement -> statement.setTimestamp(1, Timestamp.from(cutoff)),
                (resultSet, rowNumber) -> toPublication(resultSet, measuredAt));

        return new Report(measuredAt, STALE_THRESHOLD.toSeconds(), publications.size(), publications);
    }

    private static Publication toPublication(ResultSet resultSet, Instant measuredAt) throws SQLException {
        Instant publishedAt = resultSet.getTimestamp("publication_date").toInstant();
        Timestamp completionDate = resultSet.getTimestamp("completion_date");
        String storedStatus = resultSet.getString("status");
        String status = storedStatus != null
                ? storedStatus
                : completionDate != null ? "COMPLETED" : "LEGACY_UNCLASSIFIED";
        Timestamp lastResubmissionDate = resultSet.getTimestamp("last_resubmission_date");
        long ageSeconds = Math.max(0, Duration.between(publishedAt, measuredAt).toSeconds());

        return new Publication(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("listener_id"),
                status,
                publishedAt,
                ageSeconds,
                resultSet.getInt("completion_attempts"),
                lastResubmissionDate == null ? null : lastResubmissionDate.toInstant());
    }

    public record Report(
            Instant measuredAt,
            long staleThresholdSeconds,
            int count,
            List<Publication> publications) {
    }

    public record Publication(
            UUID id,
            String eventType,
            String listener,
            String status,
            Instant publishedAt,
            long ageSeconds,
            int completionAttempts,
            Instant lastResubmissionAt) {
    }
}
