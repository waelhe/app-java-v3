package com.marketplace.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 9 (plan D-11, ADR-0005): the planning record's writes and reads —
 * the operator's publish (validate → append), the rollback (republish the
 * target payload as a NEW revision — the append-only trail), and the
 * clients' read of the current planning. Every write publishes the cache
 * invalidation (the plan's «تعديل admin مرئيًا عند حد التشغيل المتوقع دون
 * إعادة تشغيل»): the event rides AFTER_COMMIT (the Modulith events
 * contract), so a rolled-back publish never evicts.
 */
@Service
public class ConsolePlanningService {

    private final ConsolePlanningRevisionRepository revisionRepository;
    private final PlanningPayloadValidator validator;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ConsolePlanningService(ConsolePlanningRevisionRepository revisionRepository,
                                  PlanningPayloadValidator validator,
                                  ObjectMapper objectMapper,
                                  ApplicationEventPublisher eventPublisher) {
        this.revisionRepository = revisionRepository;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The operator's publish — the payload validated at the gate BEFORE
     * any write; the revision number is the next in the append-only trail.
     */
    @Transactional
    public ConsolePlanningRevision publish(JsonNode payload, String note) {
        JsonNode validated = validator.validate(payload);
        int next = revisionRepository.findTopByOrderByRevisionNoDesc()
                .map(current -> current.getRevisionNo() + 1)
                .orElse(1);
        ConsolePlanningRevision revision = revisionRepository.save(
                ConsolePlanningRevision.append(next, validated.toString(), note));
        eventPublisher.publishEvent(new CacheInvalidationRequested(
                java.util.Set.of("consolePlanning"), revision.getId()));
        return revision;
    }

    /**
     * The rollback — the target revision's payload republished as a NEW
     * revision (the trail never rewrites; «آخر تخطيط صالح قابل للاستعادة»
     * is the republish, and the note names the origin).
     */
    @Transactional
    public ConsolePlanningRevision rollback(int targetRevisionNo, String note) {
        ConsolePlanningRevision target = revisionRepository.findByRevisionNo(targetRevisionNo)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConsolePlanningRevision", targetRevisionNo));
        return publish(parse(target.getPayload()),
                (note == null || note.isBlank() ? "Rollback to revision " + targetRevisionNo : note));
    }

    /** The clients' read: the current planning (the highest revision). */
    @Transactional(readOnly = true)
    public ConsolePlanningRevision current() {
        return revisionRepository.findTopByOrderByRevisionNoDesc()
                .orElseThrow(() -> new ResourceNotFoundException("ConsolePlanningRevision"));
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            // A stored revision is always the validator's own output — this
            // is the honest impossible-state guard, not a silent repair.
            throw new IllegalStateException("Stored planning revision is not parseable", e);
        }
    }
}
