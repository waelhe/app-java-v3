package com.marketplace.shared.observability;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /**
     * The report's row cap. The endpoint exists for the incident it
     * diagnoses — an accumulation of stale publications — and the live
     * backlog under exactly that incident is unbounded (measured on the
     * 2026-11 production mail outage: every notification listener leg
     * stranded its publication). An uncapped report would answer with a
     * multi-megabyte JSON precisely when the operator needs the endpoint
     * fast; the cap keeps the response bounded and reports the true total
     * and the truncation honestly instead.
     */
    static final int MAX_PUBLICATIONS = 200;

    private static final String FIND_STALE_PUBLICATIONS = """
            SELECT id,
                   event_type,
                   listener_id,
                   publication_date,
                   completion_date,
                   status,
                   completion_attempts,
                   last_resubmission_date,
                   COUNT(*) OVER () AS total_stale
              FROM event_publication
             WHERE completion_date IS NULL
               AND publication_date < ?
             ORDER BY publication_date ASC
             LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public EventPublicationDiagnosticsEndpoint(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @ReadOperation
    public Report report() {
        Instant measuredAt = Instant.now();
        Instant cutoff = measuredAt.minus(STALE_THRESHOLD);

        // CodeRabbit round 4 (consistency): ONE statement, ONE snapshot — the
        // window function carries the pre-LIMIT total on every row, so the
        // count, the total, and the returned rows can never disagree (a
        // separate COUNT query had a between-reads window where a completing
        // or newly-stale publication changed one answer and not the other).
        // PostgreSQL's documented READ COMMITTED semantics: each STATEMENT
        // sees a single consistent snapshot.
        List<StaleRow> rows = jdbcTemplate.query(
                FIND_STALE_PUBLICATIONS,
                statement -> {
                    statement.setObject(1, cutoff.atOffset(ZoneOffset.UTC));
                    statement.setInt(2, MAX_PUBLICATIONS);
                },
                (resultSet, rowNumber) -> new StaleRow(
                        toPublication(resultSet, measuredAt),
                        resultSet.getLong("total_stale")));

        List<Publication> publications = rows.stream().map(StaleRow::publication).toList();
        long totalStale = rows.isEmpty() ? 0L : rows.get(0).totalStale();
        return new Report(measuredAt, STALE_THRESHOLD.toSeconds(), publications.size(),
                totalStale, totalStale > publications.size(), publications);
    }

    /** The row and the statement-wide total it carries (one snapshot). */
    private record StaleRow(Publication publication, long totalStale) {
    }

    private static Publication toPublication(ResultSet resultSet, Instant measuredAt) throws SQLException {
        Instant publishedAt = resultSet.getObject("publication_date", OffsetDateTime.class).toInstant();
        OffsetDateTime completionDate = resultSet.getObject("completion_date", OffsetDateTime.class);
        String storedStatus = resultSet.getString("status");
        String status = storedStatus != null
                ? storedStatus
                : completionDate != null ? "COMPLETED" : "LEGACY_UNCLASSIFIED";
        OffsetDateTime lastResubmissionDate = resultSet.getObject("last_resubmission_date", OffsetDateTime.class);
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
            long total,
            boolean truncated,
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
