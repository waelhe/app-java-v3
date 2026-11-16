package com.marketplace.knowledge;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * D-3 (JT-19/D-30 — «أخبار محلية»): the news engine, on the
 * {@code KnowledgeService}/{@code InstitutionService} house shapes — the
 * VERIFIED-publisher gate (the delegated-source condition the urgent
 * alert wave rides too: an item from an unverified outlet is exactly the
 * thing this surface must never carry), the optional geo gate (the
 * port's own 404 for an unknown node, the level-3 requirement answering
 * 400 before any write — the knowledge/events discipline, applied only
 * when the scope is present), and the knowledge revise/withdraw
 * SEMANTICS with the honesty markers (AC-20-09/AC-20-10): a correction
 * re-submits the complete content and lands its REQUIRED note in the
 * same write (the item stays displayed, marked — the original never
 * muted), a withdrawal flips the domain flag (the public reads stop
 * returning the item immediately; unknown and withdrawn read as the
 * same 404).
 *
 * <p><b>NO EVENTS, BY DECISION:</b> news is a public DISPLAY surface
 * only — it publishes no integration facts and is not AI-indexed today.
 * News indexing in AI follows the knowledge module's events pattern
 * (the {@code KnowledgeEntryPublishedEvent}/{@code
 * KnowledgeEntryWithdrawnEvent} upsert/drop pair) LATER, by an explicit
 * decision; the pattern is the ready-made template when that lands.</p>
 *
 * <p><b>The view assembly lives here</b> (the {@code ProductQaService}
 * discipline): the controller never touches the entities — the service
 * returns the data-minimized records only, with the publisher's name
 * and trust mark batch-resolved per page (one {@code findAllById} per
 * read, never an N+1).</p>
 *
 * <p><b>The observations note:</b> the house's commands-not-reads
 * {@code @Observed} inventory is PINNED by the app-side
 * {@code ObservationCoverageFilesTest}; the news commands join that pin
 * with the wave's app-side task (this module's files alone cannot carry
 * it). The HTTP layer still measures every surface through
 * {@code http.server.requests}.</p>
 */
@Service
@Transactional
public class NewsService {

    /**
     * The geo port's own level contract (GeoNode's javadoc): 3 =
     * neighborhood — the same single administrative hierarchy every
     * neighborhood anchor rides (D-N2; an int constant, not the geo
     * module's enum — the cross-module vocabulary IS the port's int).
     */
    static final int NEIGHBORHOOD_LEVEL = 3;

    // The board's stable page boundary: newest-published first with the
    // complete (published_at, id) key — the idx_news_items_board index
    // shape (V179). The service owns the sort exactly as the
    // InstitutionService adoption documented: an unsorted client request
    // still produces the ORDER BY, and a client-supplied sort can never
    // request arbitrary properties.
    private static final Sort NEWS_BOARD_SORT =
            Sort.by(Sort.Direction.DESC, "publishedAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final NewsPublisherRepository publisherRepository;
    private final NewsItemRepository itemRepository;
    private final GeoLookupPort geoLookupPort;
    private final Clock clock;

    public NewsService(NewsPublisherRepository publisherRepository,
                       NewsItemRepository itemRepository,
                       GeoLookupPort geoLookupPort,
                       Clock clock) {
        this.publisherRepository = publisherRepository;
        this.itemRepository = itemRepository;
        this.geoLookupPort = geoLookupPort;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // The administrative writes (the ADMIN surface — the caller is the
    // platform's operator; the audit columns carry the actor through the
    // house @CreatedBy auditing).
    // ------------------------------------------------------------------

    /**
     * The outlet's registration: born UNVERIFIED (the honest registry —
     * the trust mark arrives only through the review). The name/URL
     * shape guards ride the request record; this is the honest insert.
     */
    public NewsPublisherResponse createPublisher(NewsPublisherRequest request) {
        return NewsPublisherResponse.from(
                publisherRepository.save(NewsPublisher.register(request.name(), request.websiteUrl())));
    }

    /**
     * The verdict's ONLY mover (the
     * {@code NeighborhoodVerificationAdminController} discipline):
     * APPROVE moves the outlet to VERIFIED (from UNVERIFIED, a future
     * PENDING claim, or a REJECTED one — the recovery lever) and REJECT
     * refuses an UNVERIFIED/PENDING claim (the row stays — the honest
     * registry). Any other source answers 409 with the entity's own
     * transition words. The verdict is what gates {@link #createNews}:
     * only a VERIFIED outlet publishes news items.
     */
    public NewsPublisherResponse verifyPublisher(UUID publisherId, boolean approve) {
        NewsPublisher publisher = publisherRepository.findById(publisherId)
                .orElseThrow(() -> new ResourceNotFoundException("News publisher not found: " + publisherId));
        try {
            if (approve) {
                publisher.approveVerification();
            } else {
                publisher.rejectVerification();
            }
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        return NewsPublisherResponse.from(publisher);
    }

    /**
     * The publication: the gate ladder is the publisher's (404 unknown —
     * the FK's own seam) → the VERIFIED state (409 — the delegated-source
     * condition, the urgent-alert wave's own gate) → the OPTIONAL geo
     * gate (the port's 404 for an unknown node, the level-3 400 —
     * applied only when the scope is present) → the write. The item is
     * born displayed (no staged publication state — the attribution pair
     * and the optional scope land complete). No event publishes here (the
     * display-only decision — the class javadoc).
     */
    public NewsItemResponse createNews(NewsItemRequest request) {
        NewsPublisher publisher = publisherRepository.findById(request.publisherId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "News publisher not found: " + request.publisherId()));
        if (publisher.getVerificationState() != NewsPublisherState.VERIFIED) {
            throw new ConflictException("News publisher " + publisher.getId() + " is "
                    + publisher.getVerificationState() + " — only a VERIFIED publisher can publish news items");
        }
        gateOptionalLocation(request.locationId());
        NewsItem item = itemRepository.save(NewsItem.publish(
                publisher.getId(), request.title(), request.summary(),
                request.sourceUrl(), request.publishedAt(), request.locationId()));
        return NewsItemResponse.from(item, publisher);
    }

    /**
     * The correction (the knowledge revise shape plus the honesty
     * markers): the complete new content re-submits, the REQUIRED note
     * lands in the same write ({@code corrected}/{@code correctedAt}/
     * {@code correctionNote} — a blank note is the silent edit this
     * surface must never carry, the D-N7 two-sided discipline with the
     * record's own constraint), the item stays DISPLAYED — the
     * correction is shown, never the muting. The publication date is
     * immutable (the honest history); the scope is immutable (the
     * routing-mismatch 400 — the knowledge revise discipline). A
     * WITHDRAWN item answers 409 (the withdrawal is the display's off
     * switch; correcting a hidden item is a conflict, not an edit —
     * there is no un-withdraw lever in this wave). An unknown item is
     * the house 404.
     */
    public NewsItemResponse correctNews(UUID itemId, NewsItemCorrectionRequest request) {
        if (request.correctionNote() == null || request.correctionNote().isBlank()) {
            throw new BadRequestException(
                    "correctionNote is required — a correction without its note is a silent edit");
        }
        NewsItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("News item not found: " + itemId));
        if (item.isWithdrawn()) {
            throw new ConflictException("News item " + itemId + " is withdrawn — it cannot be corrected");
        }
        if (!item.scopedTo(request.locationId())) {
            throw new BadRequestException("locationId cannot change on correction: item is scoped to "
                    + item.getLocationId() + " but the request carries " + request.locationId());
        }
        item.correct(request.title(), request.summary(), request.sourceUrl(),
                request.correctionNote(), clock);
        return NewsItemResponse.from(item, publisherOf(item));
    }

    /**
     * The withdrawal: the domain flag lands with its timestamp in the
     * same transaction — the public board and detail stop returning the
     * item immediately (AC-20-10; the knowledge withdraw's display
     * semantics, the row and its Envers trail kept). A second withdrawal
     * answers 409 (idempotence by refusal — the withdrawal timestamp is
     * never silently re-dated). An unknown item is the house 404.
     */
    public void withdrawNews(UUID itemId) {
        NewsItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("News item not found: " + itemId));
        try {
            item.withdraw(clock);
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // The public reads (the display surface — no events, no indexing;
    // the class javadoc carries the decision).
    // ------------------------------------------------------------------

    /**
     * The news board: every live, non-withdrawn item, newest-published
     * first on the complete {@code (published_at, id)} key (the service
     * owns the sort — the L32/D-N5 discipline), the location axis
     * optional (the composed read — the R6 lesson: the axis never drops).
     * Corrected items ride WITH their marker (the honest display);
     * withdrawn ones never appear (the repository's own flag filter).
     */
    @Transactional(readOnly = true)
    public Page<NewsItemResponse> board(UUID locationId, Pageable pageable) {
        PageRequest effective = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), NEWS_BOARD_SORT);
        Page<NewsItem> page = locationId == null
                ? itemRepository.findByWithdrawnFalse(effective)
                : itemRepository.findByLocationIdAndWithdrawnFalse(locationId, effective);
        Map<UUID, NewsPublisher> publishers = publishersOf(page.getContent());
        return page.map(item -> NewsItemResponse.from(item, publishers.get(item.getPublisherId())));
    }

    /**
     * The detail read: any live item by id with its honest status — the
     * publisher, the original link, the original date, the correction
     * marker when it exists. A WITHDRAWN item reads as unknown (the
     * same 404 — the withdrawal is immediate and public, AC-20-10).
     */
    @Transactional(readOnly = true)
    public NewsItemResponse getNews(UUID itemId) {
        NewsItem item = itemRepository.findById(itemId)
                .filter(candidate -> !candidate.isWithdrawn())
                .orElseThrow(() -> new ResourceNotFoundException("News item not found: " + itemId));
        return NewsItemResponse.from(item, publisherOf(item));
    }

    // ------------------------------------------------------------------
    // The assembly helpers (the N+1 guard: one batched publisher read
    // per page — the ProductQaService discipline).
    // ------------------------------------------------------------------

    private NewsPublisher publisherOf(NewsItem item) {
        return publisherRepository.findById(item.getPublisherId()).orElse(null);
    }

    private Map<UUID, NewsPublisher> publishersOf(List<NewsItem> items) {
        Set<UUID> ids = items.stream()
                .map(NewsItem::getPublisherId)
                .collect(Collectors.toSet());
        return publisherRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(NewsPublisher::getId, Function.identity()));
    }

    /**
     * The optional scope's gate (applied only when the scope is
     * present): the port's own 404 for an unknown node, then the
     * level-3 requirement answering 400 BEFORE any write (the
     * knowledge/events discipline — realestate L31's own rule).
     */
    private void gateOptionalLocation(UUID locationId) {
        if (locationId == null) {
            return;
        }
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(locationId);
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "locationId must reference a level-3 neighborhood node, got level "
                            + node.level() + " (" + node.slug() + ")");
        }
    }
}
