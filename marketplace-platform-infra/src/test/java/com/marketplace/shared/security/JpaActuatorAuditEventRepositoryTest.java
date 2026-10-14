package com.marketplace.shared.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
 * both halves plus the two CodeRabbit #564 round corrections: the
 * EXCLUSIVE after boundary (the reference implementation's own
 * {@code isAfter} semantics) and the bounded newest-first read (an
 * append-only table is never loaded whole).
 */
@ExtendWith(MockitoExtension.class)
class JpaActuatorAuditEventRepositoryTest {

    private final ActuatorAuditEventEntryRepository entries = mock(ActuatorAuditEventEntryRepository.class);

    @InjectMocks
    private JpaActuatorAuditEventRepository repository;

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
        // The empty data map IS the floor — Boot's AuditEvent constructors
        // never accept null data (the map is wrapped unmodifiable), so an
        // event with no extra details carries Map.of().
        AuditEvent event = new AuditEvent(Instant.now(), "operator", "AUTHORIZATION_DENIED", Map.of());

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
        when(entries.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));

        List<AuditEvent> found = repository.find("operator", timestamp.minusSeconds(60), "AUTHENTICATION_FAILURE");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTimestamp()).isEqualTo(timestamp);
        assertThat(found.get(0).getPrincipal()).isEqualTo("operator");
        assertThat(found.get(0).getType()).isEqualTo("AUTHENTICATION_FAILURE");
        assertThat(found.get(0).getData()).isEqualTo(data);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReadIsBoundedNewestFirstEvenWithNoFilters() {
        when(entries.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        List<AuditEvent> found = repository.find(null, null, null);

        assertThat(found).isEmpty();
        // The bounded window: the newest FIND_LIMIT events, never the whole
        // append-only table (the CodeRabbit #564 round).
        verify(entries).findAll(argThat((Pageable pageable) ->
                pageable.getPageSize() == JpaActuatorAuditEventRepository.FIND_LIMIT
                        && pageable.getSort().getOrderFor("eventTime") != null
                        && pageable.getSort().getOrderFor("eventTime").getDirection().isDescending()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAfterFilterCarriesTheExclusiveBoundary() {
        // The reference implementation's own semantics: an event AT the
        // after instant is excluded — the filter is a STRICTLY-after
        // predicate. With the boundary pinned by the integration test's
        // real query, this unit pins the wiring: an after filter present
        // means the filtered overload runs (never the unfiltered one).
        when(entries.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        repository.find(null, Instant.now(), null);

        verify(entries).findAll(any(Specification.class), any(Pageable.class));
    }
}
