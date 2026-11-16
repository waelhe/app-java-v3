package com.marketplace.community;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * L45 (neighborhood community plan §5 — the moderation &amp; reports layer):
 * one member's report on one piece of authored content — the moderation
 * queue's unit.
 *
 * <p><b>The domain shape (all plan decisions, all measured):</b>
 * <ul>
 *   <li>{@code reporterId}, {@code targetId} and {@code resolvedBy} are
 *       plain UUID columns with NO JPA relation across module boundaries
 *       (the V32/V48/V60/V61 discipline): the reporter and the moderated
 *       author live in the {@code users.id} space and arrive through the
 *       identity seams; the target id is the target surface's own id
 *       ({@code neighborhood_posts.id} or {@code post_comments.id}).</li>
 *   <li>{@code targetType}, {@code reason} and {@code status} are
 *       DB-enumerated columns (D-N7 — the V64 CHECKs pin the SQL-side
 *       membership guards in the V44 locking shape).</li>
 *   <li>The partial unique index {@code ON (reporter_id, target_type,
 *       target_id) WHERE is_deleted = FALSE} is the plan's own
 *       "تقرير واحد لكل مرسل/هدف" — the service's explicit 409 comes
 *       first, the constraint is the backstop (the 23505⇒409 house
 *       translation, the L30 G-N1 precedent verbatim).</li>
 *   <li>{@code resolutionNote} is the administrative free text, bounded
 *       at 2000 — the house authored-message bound (the L42 comment
 *       body's own documented limit). Nullable: the plan's "نص إداري
 *       اختياري".</li>
 *   <li>{@code resolvedBy}/{@code resolvedAt} are stamped by the resolve
 *       domain method through the injected {@link Clock} — the same seam
 *       every house domain timestamp rides; {@code updatedAt} alone
 *       would not carry the semantic (it moves on any update).</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (the V25/V32 lesson);
 * the Envers mirror rides V64 (the V24 convention) so every state flip
 * is a revision (the plan's criterion 6).
 */
@Entity
@Table(name = "content_reports")
@Audited
public class ContentReport extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    /** The target surface's own id — resolved and gated by the service. */
    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private ReportReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "resolution_note", length = 2000)
    private String resolutionNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ContentReport() {
    }

    private ContentReport(UUID id, UUID reporterId,
                          ReportTargetType targetType, UUID targetId, ReportReason reason) {
        this.id = id;
        this.reporterId = reporterId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
    }

    /**
     * The report factory: a fresh OPEN report. The target-existence and
     * own-content gates live in the service (before any write); this
     * factory is the honest insert shape.
     */
    public static ContentReport report(UUID reporterId, ReportTargetType targetType,
                                       UUID targetId, ReportReason reason) {
        ContentReport report = new ContentReport(UUID.randomUUID(), reporterId,
                targetType, targetId, reason);
        report.status = ReportStatus.OPEN;
        return report;
    }

    /**
     * The resolve flip — the ONE transition out of OPEN, stamped through
     * the injected clock. Called by the service's resolve command for
     * both outcomes ({@code RESOLVED} behind {@code HIDE_CONTENT},
     * {@code DISMISSED} behind {@code DISMISS}); the caller owns the
     * action's side effects (the content flip and the event) so the
     * whole command is one transaction (the plan's criterion 2).
     */
    void resolve(ReportStatus outcome, String note, UUID resolvedBy, Clock clock) {
        this.status = outcome;
        this.resolutionNote = note;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = clock.instant();
    }

    @Override
    public UUID getId() { return id; }
    public UUID getReporterId() { return reporterId; }
    public ReportTargetType getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }
    public ReportReason getReason() { return reason; }
    public ReportStatus getStatus() { return status; }
    public String getResolutionNote() { return resolutionNote; }
    public UUID getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
}
