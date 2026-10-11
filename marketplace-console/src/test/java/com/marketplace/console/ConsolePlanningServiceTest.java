package com.marketplace.console;

import tools.jackson.databind.ObjectMapper;
import com.marketplace.shared.api.CacheInvalidationRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 9 (ADR-0005) — the planning record's write gate: the publish
 * validates then appends (the next revision number), every write carries
 * the cache invalidation (the event fires in this transaction; its
 * AFTER_COMMIT phase does the eviction), and the rollback REPUBLISHES the
 * target payload as a NEW revision (the append-only trail never rewrites).
 */
@ExtendWith(MockitoExtension.class)
class ConsolePlanningServiceTest {

    @Mock
    private ConsolePlanningRevisionRepository revisionRepository;
    @Mock
    private PlanningPayloadValidator validator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper mapper = new ObjectMapper();

    private ConsolePlanningService service;

    @BeforeEach
    void setUp() {
        service = new ConsolePlanningService(revisionRepository, validator, mapper, eventPublisher);
    }

    @Test
    void thePublishAppendsTheNextRevisionAndCarriesTheInvalidation() {
        var payload = mapper.createObjectNode();
        when(revisionRepository.findTopByOrderByRevisionNoDesc())
                .thenReturn(java.util.Optional.of(
                        ConsolePlanningRevision.append(3, "{}", "previous")));
        when(revisionRepository.save(any(ConsolePlanningRevision.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var published = service.publish(payload, "stage-9 layout");

        assertThat(published.getRevisionNo()).isEqualTo(4);
        ArgumentCaptor<CacheInvalidationRequested> event =
                ArgumentCaptor.forClass(CacheInvalidationRequested.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().cacheNames()).contains("consolePlanning");
    }

    @Test
    void theFirstPublishStartsTheTrailAtOne() {
        when(revisionRepository.findTopByOrderByRevisionNoDesc())
                .thenReturn(java.util.Optional.empty());
        when(revisionRepository.save(any(ConsolePlanningRevision.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.publish(mapper.createObjectNode(), "first").getRevisionNo()).isEqualTo(1);
    }

    @Test
    void theRollbackRepublishesTheTargetPayloadAsANewRevision() {
        var target = ConsolePlanningRevision.append(2, "{\"sections\":[]}", "the good layout");
        when(revisionRepository.findByRevisionNo(2)).thenReturn(java.util.Optional.of(target));
        when(revisionRepository.findTopByOrderByRevisionNoDesc())
                .thenReturn(java.util.Optional.of(ConsolePlanningRevision.append(5, "{}", "later")));
        when(revisionRepository.save(any(ConsolePlanningRevision.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var rolled = service.rollback(2, null);

        // The trail moved FORWARD: revision 6 carries revision 2's payload —
        // nothing was rewritten, the note names the origin.
        assertThat(rolled.getRevisionNo()).isEqualTo(6);
        assertThat(rolled.getPayload()).contains("sections");
        assertThat(rolled.getNote()).contains("Rollback to revision 2");
    }
}
