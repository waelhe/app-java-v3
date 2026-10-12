package com.marketplace.institutions;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UrgentAlertPublishedEvent;
import com.marketplace.shared.api.UrgentAlertWithdrawnEvent;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the official-alert
 * engine, on the {@code InstitutionService}/{@code KnowledgeService}
 * house shapes — the geo gate FIRST (the port's own 404 for an unknown
 * node, the level-3 requirement answering 400 BEFORE any write), the
 * administrative verification as the delegation's ONLY mover, and the
 * SEARCH/NOTIFICATION INTEGRATION BY EVENTS: every publish carries
 * {@link UrgentAlertPublishedEvent} and every withdrawal carries
 * {@link UrgentAlertWithdrawnEvent} on the same transaction — the
 * {@code ApplicationModuleListener} consumers run AFTER_COMMIT in their
 * own transactions (the Modulith events contract), so a published fact
 * always describes a committed alert (the {@code KnowledgeService}
 * usage verbatim: {@code ApplicationEventPublisher.publishEvent} inside
 * the {@code @Transactional} boundary is the whole house mechanism —
 * the {@code EventPublicationRegistry} makes it durable).
 *
 * <p><b>The eligibility engine lives here — once:</b> {@link #activeAlerts}
 * is the ONE read that decides what an urgent-alert surface may serve —
 * the live window (the repository's own query: not withdrawn, window
 * covering {@code now}) AND the source-state leg (AC-20-01: a VERIFIED
 * source is a display precondition — an UNVERIFIED/PENDING/REJECTED
 * source's alerts answer silence, deterministically, at the single seam
 * the {@code spi/UrgentAlertsAdapter} and the public controller both
 * speak through). CMP-46's own rule holds everywhere: the level is text
 * on the way out, never a ranking weight — the only order is the
 * alert's own validity clock (freshest first).</p>
 */
@Service
@Transactional
public class UrgentAlertService {

    /**
     * The geo port's own level contract (GeoNode's javadoc): 3 =
     * neighborhood — the same single administrative hierarchy every
     * neighborhood anchor rides (D-N2; an int constant, not the geo
     * module's enum — the cross-module vocabulary IS the port's int).
     */
    static final int NEIGHBORHOOD_LEVEL = 3;

    private final UrgentAlertSourceRepository sourceRepository;
    private final UrgentAlertRepository alertRepository;
    private final GeoLookupPort geoLookupPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public UrgentAlertService(UrgentAlertSourceRepository sourceRepository,
                              UrgentAlertRepository alertRepository,
                              GeoLookupPort geoLookupPort,
                              ApplicationEventPublisher eventPublisher,
                              Clock clock) {
        this.sourceRepository = sourceRepository;
        this.alertRepository = alertRepository;
        this.geoLookupPort = geoLookupPort;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * The delegation registry's entry: born UNVERIFIED (the honest
     * registry — the {@link Institution#register} shape), by the
     * administrative gate itself (من يفوض؟ نفس بوابة admin الحالية —
     * an official body cannot self-declare its own authority).
     */
    @Observed(name = "urgentAlert.source.create")
    public UrgentAlertSource createSource(UrgentAlertSourceRequest request) {
        return sourceRepository.save(UrgentAlertSource.delegate(request.name(), request.sourceType()));
    }

    /**
     * The delegation review queue (administrative): the optional state
     * axis (PENDING the reviewable queue on its complete drain order —
     * updatedAt ASC, id ASC, oldest pending claim first — the house's
     * own {@code VERIFICATION_QUEUE_SORT} discipline), absent = the
     * whole registry.
     */
    private static final Sort VERIFICATION_QUEUE_SORT =
            Sort.by(Sort.Direction.ASC, "updatedAt").and(Sort.by(Sort.Direction.ASC, "id"));

    @Transactional(readOnly = true)
    public Page<UrgentAlertSource> reviewQueue(InstitutionVerificationState state, Pageable pageable) {
        Pageable queuePageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), VERIFICATION_QUEUE_SORT);
        return state == null
                ? sourceRepository.findAll(queuePageable)
                : sourceRepository.findByVerificationState(state, queuePageable);
    }

    /**
     * The delegation's review request: UNVERIFIED → PENDING only (the
     * {@code requestVerification} house discipline). An unknown source
     * answers 404.
     */
    @Observed(name = "urgentAlert.source.verification.request")
    public UrgentAlertSource requestSourceVerification(UUID sourceId) {
        UrgentAlertSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Urgent alert source not found: " + sourceId));
        source.requestVerification();
        return source;
    }

    /**
     * The verdict's ONLY mover (the {@link InstitutionService#review}
     * discipline verbatim): APPROVE moves a PENDING source to VERIFIED
     * and RE-ADMITS a REJECTED one (the recovery lever); REJECT refuses
     * a PENDING claim. Any other source answers 409 with the machine's
     * own transition words — and until VERIFIED lands, the source's
     * alerts simply never serve (AC-20-01's deterministic eligibility).
     */
    @Observed(name = "urgentAlert.source.verification.review")
    public UrgentAlertSource reviewSource(UUID sourceId, boolean approve) {
        UrgentAlertSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Urgent alert source not found: " + sourceId));
        try {
            if (approve) {
                source.approveVerification();
            } else {
                source.rejectVerification();
            }
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        return source;
    }

    /**
     * The publication (JT-10: مصدر مفوض + نطاق + سريان). The gate order
     * runs BEFORE any write (the {@code InstitutionService#register}
     * order verbatim): the source gate first (unknown 404, non-VERIFIED
     * 409 — AC-20-01's deterministic eligibility is a WRITE gate here
     * and a READ gate in {@link #activeAlerts}), then the geo gate (the
     * port's own 404 for an unknown node, level-3 or 400), then the
     * window rule (the V83 time rule's twin: {@code validUntil} absent
     * or strictly after {@code validFrom} — 400; the V178 CHECK is the
     * backstop). The published fact rides the same transaction —
     * AFTER_COMMIT consumers describe a committed alert, and the honest
     * attribution is the SOURCE's own name (the authority is the
     * delegation's, never the community's — AC-20-06).
     */
    @Observed(name = "urgentAlert.publish")
    public ActiveAlert publishAlert(UrgentAlertPublishRequest request) {
        UrgentAlertSource source = sourceRepository.findById(request.sourceId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Urgent alert source not found: " + request.sourceId()));
        if (source.getVerificationState() != InstitutionVerificationState.VERIFIED) {
            throw new ConflictException("Only VERIFIED sources can publish urgent alerts — source "
                    + source.getId() + " is " + source.getVerificationState());
        }
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(request.locationId());
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "locationId must reference a level-3 neighborhood node, got level "
                            + node.level() + " (" + node.slug() + ")");
        }
        if (request.validUntil() != null && !request.validUntil().isAfter(request.validFrom())) {
            throw new BadRequestException("validUntil must be absent or strictly after validFrom — got "
                    + request.validFrom() + " .. " + request.validUntil());
        }
        UrgentAlert alert = alertRepository.save(UrgentAlert.publish(
                source.getId(), request.locationId(), request.level(),
                request.title(), request.body(), request.validFrom(), request.validUntil()));
        eventPublisher.publishEvent(new UrgentAlertPublishedEvent(
                alert.getId(),
                source.getId(),
                source.getName(),
                alert.getLocationId(),
                alert.getLevel().name(),
                alert.getTitle(),
                clock.instant()));
        return new ActiveAlert(alert, source);
    }

    /**
     * The withdrawal — JT-10's honesty leg («تصحيح/سحب ينعكس على كل
     * الأسطح»): the flags move once (a second withdrawal answers the
     * house 409), the row and its Envers trail stay, and the drop signal
     * rides the same transaction — every surface stops serving the alert
     * the moment this commits (the {@code UrgentAlertsPort} answers
     * withdrawn alerts with silence; the notifications ledger keeps the
     * delivery facts — the knowledge withdraw precedent, applied to
     * institutional alerts).
     */
    @Observed(name = "urgentAlert.withdraw")
    public ActiveAlert withdrawAlert(UUID alertId) {
        UrgentAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Urgent alert not found: " + alertId));
        try {
            alert.withdraw(clock.instant());
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        eventPublisher.publishEvent(new UrgentAlertWithdrawnEvent(alert.getId(), alert.getWithdrawnAt()));
        return new ActiveAlert(alert, source(alert.getSourceId()));
    }

    /**
     * The eligibility engine — the ONE read every urgent-alert surface
     * speaks through (the {@code spi/UrgentAlertsAdapter} and the public
     * controller): the repository's live-window read (not withdrawn,
     * window covering {@code now}, freshest first on the complete
     * {@code (validFrom DESC, id DESC)} key) filtered to VERIFIED sources
     * only (AC-20-01 — a trusted source is a display precondition; an
     * UNVERIFIED/PENDING/REJECTED source's alerts answer silence,
     * deterministically). Empty locations never touch the source read.
     */
    @Transactional(readOnly = true)
    public List<ActiveAlert> activeAlerts(UUID locationId, Instant now) {
        List<UrgentAlert> live = alertRepository.findLiveByLocation(locationId, now);
        if (live.isEmpty()) {
            return List.of();
        }
        Map<UUID, UrgentAlertSource> sources = sourceRepository
                .findAllById(live.stream().map(UrgentAlert::getSourceId).toList())
                .stream()
                .collect(Collectors.toMap(UrgentAlertSource::getId, Function.identity()));
        return live.stream()
                .filter(alert -> {
                    UrgentAlertSource source = sources.get(alert.getSourceId());
                    return source != null
                            && source.getVerificationState() == InstitutionVerificationState.VERIFIED;
                })
                .map(alert -> new ActiveAlert(alert, sources.get(alert.getSourceId())))
                .toList();
    }

    /** One live source by id — the honest 404 for an unknown one. */
    private UrgentAlertSource source(UUID sourceId) {
        return sourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Urgent alert source not found: " + sourceId));
    }

    /**
     * The engine's own pair — the alert and the source as the eligibility
     * engine resolved them (the module-internal carrier the adapter maps
     * to the port's card and the admin controller maps to the wire).
     */
    public record ActiveAlert(UrgentAlert alert, UrgentAlertSource source) {
    }
}
