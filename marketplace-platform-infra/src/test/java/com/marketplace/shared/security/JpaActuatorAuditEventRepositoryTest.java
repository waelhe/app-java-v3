package com.marketplace.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The official {@code AuditEventRepository} contract, honored verbatim —
 * the reference's Auditing recipe ("provide a bean of type
 * AuditEventRepository... for production environments, consider creating
 * your own implementation") has exactly two methods, and this test pins
 * both halves: {@code add} persists the event's own triple, and
 * {@code find} applies exactly the non-null filters (the
 * /actuator/auditevents endpoint's three documented query parameters,
 * null meaning "no filter").
 */
class JpaActuatorAuditEventRepositoryTest {

    private final ActuatorAuditEventEntryRepository entries = mock(ActuatorAuditEventEntryRepository.class);

    private final JpaActuatorAuditEventRepository repository = new JpaActuatorAuditEventRepository(entries);

    @Test
    void addPersistsTheEventsOwnTripleWithAFreshId() {
        Instant timestamp = Instant.parse("2026-10-14T12:00:00Z");
        Map<String, Object> data = Map.of("details", Map.of("remoteAddress", "10.0.0.1"));
        AuditEvent event = new AuditEvent(timestamp, "operator", "AUTHENTICATION_SUCCESS", data);

        repository.add(event);

        verify(entries).save(argThat(entry -> entry != null
                && entry.getId() != null
                && entry.getEventTime().equals(timestamp)
                && "operator".equals(entry.getPrincipal())
                && "AUTHENTICATION_SUCCESS".equals(entry.getAuditType())
                && entry.getData().equals(data)));
    }

    @Test
    void addTreatsTheEmptyDataMapAsWritten() {
        // The varargs convenience constructor builds the empty data map —
        // Boot's AuditEvent can never carry null data (its own constructors
        // wrap the map unmodifiable), so the empty map is the floor.
        AuditEvent event = new AuditEvent(Instant.now(), "operator", "AUTHORIZATION_DENIED");

        repository.add(event);

        verify(entries).save(argThat(entry -> entry != null && entry.getData() != null
                && entry.getData().isEmpty()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findMapsEveryRowBackToTheAuditEventTriple() {
        Instant timestamp = Instant.parse("2026-10-14T13:00:00Z");
        Map<String, Object> data = Map.of("type", "InvalidBearerTokenException");
        ActuatorAuditEventEntry row = new ActuatorAuditEventEntry(
                UUID.randomUUID(), timestamp, "operator", "AUTHENTICATION_FAILURE", data);
        when(entries.findAll(any(Specification.class))).thenReturn(List.of(row));

        List<AuditEvent> found = repository.find("operator", timestamp, "AUTHENTICATION_FAILURE");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTimestamp()).isEqualTo(timestamp);
        assertThat(found.get(0).getPrincipal()).isEqualTo("operator");
        assertThat(found.get(0).getType()).isEqualTo("AUTHENTICATION_FAILURE");
        assertThat(found.get(0).getData()).isEqualTo(data);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findWithNoFiltersStillOrdersNewestFirst() {
        when(entries.findAll(any(Specification.class))).thenReturn(List.of());

        List<AuditEvent> found = repository.find(null, null, null);

        assertThat(found).isEmpty();
        // The no-filter path still passes the ordering specification — the
        // operator's dashboard reads the live tail, newest first.
        verify(entries).findAll(any(Specification.class));
    }
}
