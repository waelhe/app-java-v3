package com.marketplace.knowledge;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * B-14 (compliance plan C.4 — the «تعرف على» guide): the knowledge
 * engine, on the {@code NeighborhoodMembershipService}/{@code PostsService}
 * house shapes — the geo gate FIRST (the port's own 404 for an unknown
 * node, the level-3 requirement answering 400 BEFORE any write —
 * realestate L31's own discipline), the {@code CurrentUserProvider} /me
 * seam, the author ownership (a foreign entry answers 404 — the caller's
 * own resource or nothing), and the SEARCH INTEGRATION BY EVENTS: every
 * contribution and every revision publishes
 * {@link KnowledgeEntryPublishedEvent} (the upsert signal — the complete
 * indexing fact, no consumer read), every withdrawal publishes
 * {@link KnowledgeEntryWithdrawnEvent} (the drop signal). The eventual
 * consumer is the search side (a reserve module — the late-lander rule's
 * assignee; the catalog's own {@code ListingActivatedEvent} integration
 * is the measured precedent of the shape).
 */
@Service
@Transactional
public class KnowledgeService {

    /**
     * The geo port's own level contract (GeoNode's javadoc): 3 =
     * neighborhood — the same single administrative hierarchy every
     * neighborhood anchor rides (D-N2; an int constant, not the geo
     * module's enum — the cross-module vocabulary IS the port's int).
     */
    static final int NEIGHBORHOOD_LEVEL = 3;

    private final KnowledgeEntryRepository repository;
    private final GeoLookupPort geoLookupPort;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    public KnowledgeService(KnowledgeEntryRepository repository,
                            GeoLookupPort geoLookupPort,
                            CurrentUserProvider currentUserProvider,
                            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.geoLookupPort = geoLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The contribution: the caller IS the author ({@code users.id} at the
     * A1 seam), the entry is born PUBLISHED (the community builds the
     * guide in the open), and the indexing fact rides the same
     * transaction — {@code ApplicationModuleListener} consumers run
     * AFTER_COMMIT in their own transactions (the Modulith events
     * contract), so the published fact describes a committed entry.
     */
    @Observed(name = "knowledge.entry.create")
    public KnowledgeEntry contribute(KnowledgeEntryRequest request, Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(request.locationId());
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "locationId must reference a level-3 neighborhood node, got level "
                            + node.level() + " (" + node.slug() + ")");
        }
        KnowledgeEntry entry = repository.save(KnowledgeEntry.contribute(
                authorId, request.locationId(), request.category(), request.title(), request.body()));
        eventPublisher.publishEvent(new KnowledgeEntryPublishedEvent(
                entry.getId(), entry.getLocationId(), entry.getCategory(),
                entry.getTitle(), entry.getBody(), entry.getAuthorId()));
        return entry;
    }

    /**
     * The guide's board for one neighborhood: the category axis optional
     * (null = the whole guide). The service passes the stable
     * {@code (created_at, id)} sort (the L32/D-N5 lesson).
     */
    @Transactional(readOnly = true)
    public Page<KnowledgeEntry> board(UUID locationId, KnowledgeCategory category, Pageable pageable) {
        return category != null
                ? repository.findByLocationIdAndCategoryOrderByCreatedAtDescIdDesc(locationId, category, pageable)
                : repository.findByLocationIdOrderByCreatedAtDescIdDesc(locationId, pageable);
    }

    /** The full-text read (the guide's discovery): the official websearch parser, the category axis composed. */
    @Transactional(readOnly = true)
    public Page<KnowledgeEntry> search(String query, KnowledgeCategory category, Pageable pageable) {
        return repository.searchFullText(query, category == null ? null : category.name(), pageable);
    }

    /** The detail read: any live entry by id — unknown is 404. */
    @Transactional(readOnly = true)
    public KnowledgeEntry getEntry(UUID entryId) {
        return repository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge entry not found: " + entryId));
    }

    /** The contributor's own entries — their contributions to the guide. */
    @Transactional(readOnly = true)
    public Page<KnowledgeEntry> myEntries(Authentication authentication, Pageable pageable) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        return repository.findByAuthorIdOrderByCreatedAtDescIdDesc(authorId, pageable);
    }

    /**
     * The author's revision: the foreign entry answers 404 (the caller's
     * own resource or nothing — the inbox discipline). The revision
     * republishes the indexing fact (the upsert signal — the consumer's
     * index reflects the revised text, never the stale one).
     */
    @Observed(name = "knowledge.entry.update")
    public KnowledgeEntry revise(UUID entryId, KnowledgeEntryRequest request,
                                 Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        KnowledgeEntry entry = repository.findById(entryId)
                .filter(e -> e.getAuthorId().equals(authorId))
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge entry not found: " + entryId));
        entry.revise(request.category(), request.title(), request.body());
        eventPublisher.publishEvent(new KnowledgeEntryPublishedEvent(
                entry.getId(), entry.getLocationId(), entry.getCategory(),
                entry.getTitle(), entry.getBody(), entry.getAuthorId()));
        return entry;
    }

    /**
     * The author's withdrawal — the BaseEntity soft delete (the row
     * keeps its audit trail, the reads stop returning it). The drop
     * signal rides the same transaction: the consumer's index never
     * serves a withdrawn entry.
     */
    @Observed(name = "knowledge.entry.withdraw")
    public void withdraw(UUID entryId, Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        KnowledgeEntry entry = repository.findById(entryId)
                .filter(e -> e.getAuthorId().equals(authorId))
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge entry not found: " + entryId));
        repository.delete(entry);
        eventPublisher.publishEvent(new KnowledgeEntryWithdrawnEvent(
                entry.getId(), entry.getLocationId()));
    }
}
