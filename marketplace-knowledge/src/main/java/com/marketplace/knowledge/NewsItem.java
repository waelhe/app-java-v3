package com.marketplace.knowledge;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * D-3 (JT-19/D-30 — «أخبار محلية»): one local news item from one VERIFIED
 * publisher, on the {@code KnowledgeEntry} house shapes — {@code @Audited}
 * over the full BaseEntity column set from day one, the correction and
 * withdrawal riding the knowledge revise/withdraw SEMANTICS with the
 * honesty markers this surface exists for (AC-20-09/AC-20-10).
 *
 * <p><b>The honesty contract (the display IS the product):</b></p>
 * <ul>
 *   <li>{@code publisherId} — the item's outlet, the sanctioned INTERNAL
 *       reference (the V83 {@code event_rsvps.event_id} precedent
 *       verbatim): a plain UUID column in Java, a REAL FK in SQL — the
 *       item and its publisher are one aggregate inside this module's
 *       own boundary. The service gates the write on the publisher's
 *       VERIFIED state (the delegated-source condition — an item from an
 *       unverified outlet is exactly the thing this surface must never
 *       carry).</li>
 *   <li>{@code sourceUrl} / {@code publishedAt} — the original link and
 *       the original date, both REQUIRED and both riding every read
 *       (AC-20-09: the attribution is the product). The date is
 *       IMMUTABLE on correction — a correction edits the content, never
 *       the history.</li>
 *   <li>{@code locationId} — the OPTIONAL geo scope (NULL = the whole
 *       board), a plain UUID column with NO relation across module
 *       boundaries (the V32/V48/V54 discipline); level-3-gated by the
 *       service through {@code GeoLookupPort} when present (D-N2). The
 *       scope is IMMUTABLE on correction (the knowledge revise
 *       discipline — the routing-mismatch 400).</li>
 *   <li>{@code corrected}/{@code correctedAt}/{@code correctionNote} —
 *       the CORRECTION marker: the revised content stays displayed, the
 *       original never muted, the note REQUIRED (a silent edit is the
 *       one thing this surface must never do — AC-20-10). The knowledge
 *       revise's over-write plus the honest marker this surface adds.</li>
 *   <li>{@code withdrawn}/{@code withdrawnAt} — the WITHDRAWAL as a
 *       DOMAIN flag, NOT the infrastructure soft delete: the row keeps
 *       its Envers trail, the public reads stop returning it the moment
 *       the flag lands (the knowledge withdraw's display semantics
 *       without reclaiming the {@code @SoftDelete} seam).</li>
 *   <li>No staged publication state: the item is born displayed (the
 *       admin writes it already live) — the correction/withdrawal pair
 *       is the whole lifecycle, reflected immediately (AC-20-10).</li>
 * </ul>
 */
@Entity
@Table(name = "news_items")
@Audited
public class NewsItem extends BaseEntity {

    @Id
    private UUID id;

    /** The outlet's id — the same-module FK (a plain UUID in Java, the V83 internal-reference discipline). */
    @Column(name = "publisher_id", nullable = false)
    private UUID publisherId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "source_url", nullable = false, length = 1000)
    private String sourceUrl;

    /** The ORIGINAL publication date — required, and immutable on correction (the honest history). */
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    /** The optional geo scope (level-3 node, gated by the service when present) — NULL = the whole board. */
    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "is_corrected", nullable = false)
    private boolean corrected;

    @Column(name = "corrected_at")
    private Instant correctedAt;

    @Column(name = "correction_note", length = 1000)
    private String correctionNote;

    @Column(name = "is_withdrawn", nullable = false)
    private boolean withdrawn;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    protected NewsItem() {
    }

    private NewsItem(UUID id, UUID publisherId, String title, String summary,
                     String sourceUrl, Instant publishedAt, UUID locationId) {
        this.id = id;
        this.publisherId = publisherId;
        this.title = title;
        this.summary = summary;
        this.sourceUrl = sourceUrl;
        this.publishedAt = publishedAt;
        this.locationId = locationId;
    }

    /**
     * The publication factory: born displayed (the admin writes it
     * already live — the create-and-be-seen discipline; the VERIFIED
     * publisher gate and the optional level-3 location gate live in the
     * service before any write). This factory is the honest insert
     * shape.
     */
    public static NewsItem publish(UUID publisherId, String title, String summary,
                                   String sourceUrl, Instant publishedAt, UUID locationId) {
        return new NewsItem(UUID.randomUUID(), publisherId, title, summary,
                sourceUrl, publishedAt, locationId);
    }

    /**
     * The correction: the complete new content re-submits (the knowledge
     * revise shape) and the honest marker lands in the SAME write —
     * {@code corrected} flips true, the timestamp rides the caller's
     * clock, and the REQUIRED note is the correction's whole point (a
     * silent edit is the one thing this surface must never do). The
     * original is never muted — the item stays displayed, marked. The
     * publication date is immutable (the honest history); the scope is
     * immutable (the knowledge revise discipline — the service answers
     * the routing-mismatch 400 before calling this).
     */
    public void correct(String title, String summary, String sourceUrl,
                        String correctionNote, Clock clock) {
        this.title = title;
        this.summary = summary;
        this.sourceUrl = sourceUrl;
        this.corrected = true;
        this.correctedAt = clock.instant();
        this.correctionNote = correctionNote;
    }

    /**
     * The withdrawal: the display's own off switch — the flag lands in
     * the same transaction as the write, so the public board and detail
     * stop returning the item immediately (AC-20-10; the knowledge
     * withdraw's display semantics, the row and its Envers trail kept).
     * A second withdrawal answers {@code IllegalStateException} (the
     * service's 409 — idempotence by refusal, never a silent re-date of
     * the withdrawal timestamp).
     */
    public void withdraw(Clock clock) {
        if (withdrawn) {
            throw new IllegalStateException("News item is already withdrawn");
        }
        this.withdrawn = true;
        this.withdrawnAt = clock.instant();
    }

    @Override
    public UUID getId() { return id; }
    public UUID getPublisherId() { return publisherId; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getSourceUrl() { return sourceUrl; }
    public Instant getPublishedAt() { return publishedAt; }
    public UUID getLocationId() { return locationId; }
    public boolean isCorrected() { return corrected; }
    public Instant getCorrectedAt() { return correctedAt; }
    public String getCorrectionNote() { return correctionNote; }
    public boolean isWithdrawn() { return withdrawn; }
    public Instant getWithdrawnAt() { return withdrawnAt; }

    /** The scope's null-safe compare — the correction's routing-mismatch gate (the knowledge revise discipline). */
    public boolean scopedTo(UUID locationId) {
        return Objects.equals(this.locationId, locationId);
    }
}
