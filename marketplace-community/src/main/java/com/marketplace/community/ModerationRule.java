package com.marketplace.community;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * B-19 (compliance plan C.11 — محرك قواعد إشراف تلقائية): one AUTOMATIC
 * moderation rule — a DATA row standing above the existing reports
 * machine (L45), never a compiled-in condition. The rule's whole shape
 * is the report's own measured axes: WHICH target type and WHICH
 * reason, watched until HOW MANY distinct reporters have live OPEN
 * reports on the same target — then the engine's single measured verb
 * fires (the automatic {@code HIDE_CONTENT}: hide the content, alert
 * its author, resolve every OPEN report on the target, and publish the
 * reporters' adjudication facts — {@link ModerationRuleEngine}).
 *
 * <p><b>The values are DATA, never migrations (the V70 dictionary
 * discipline verbatim):</b> the operator registers, revises, pauses and
 * retires rules through the administrative surface
 * ({@link ModerationRuleAdminController}); a rule never ships inside a
 * Flyway file. One LIVE row per {@code (targetType, reason)} — the
 * V161 partial unique in the V70/V157/V160 shape: a retired
 * (soft-deleted) rule never blocks its own re-registration.
 *
 * <p><b>The closed vocabularies carry their DB-level guards (the V64
 * shape — the CHECK pair on a table born with them):</b>
 * {@code target_type} and {@code reason} are the report machine's own
 * enums (the same words {@code content_reports} guards), so the rule
 * cannot name a target type or a reason the machine cannot receive.
 * The threshold is {@code >= 1} by the CHECK — a rule that fires on
 * the first report is legal, a rule that fires on zero is not a rule.
 *
 * <p><b>The enabled toggle (the V157 flags' own shape):</b> the pause
 * without losing the row — the operator's audit trail (the BaseEntity
 * auditing fields) keeps who registered, revised and retired, while a
 * paused rule is simply skipped by the evaluation lookup. The
 * evaluation reads the row AT REQUEST TIME (inside the report-creation
 * transaction — the C.11 mandate) — never a boot-time conditional.
 */
@Entity
@Table(name = "moderation_rules")
@Audited
public class ModerationRule extends BaseEntity {

    @Id
    private UUID id;

    /** The report axis the rule watches — POST, COMMENT or REVIEW (the machine's own vocabulary). */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    /** The report axis the rule watches — SPAM, HARASSMENT, INAPPROPRIATE or OTHER. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private ReportReason reason;

    /**
     * How many DISTINCT live OPEN reporters on the same target fire the
     * rule — the community's own signal strength. The V64 partial
     * unique (one live report per reporter+target) makes the OPEN
     * report count on a target EXACTLY its distinct reporter count.
     */
    @Column(name = "threshold", nullable = false)
    private int threshold;

    /** The pause — a disabled rule is skipped by the evaluation lookup without losing the row. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected ModerationRule() {
    }

    private ModerationRule(UUID id, ReportTargetType targetType, ReportReason reason, int threshold) {
        this.id = id;
        this.targetType = targetType;
        this.reason = reason;
        this.threshold = threshold;
        this.enabled = true;
    }

    /**
     * The registration factory — born ENABLED (a registered rule is
     * live; the pause is the operator's separate verb). The duplicate
     * live {@code (targetType, reason)} pair answers 409 in the service
     * (the V161 partial unique's polite face); the threshold's own
     * floor is the defense-in-depth behind the request validation.
     */
    public static ModerationRule register(ReportTargetType targetType, ReportReason reason, int threshold) {
        if (targetType == null || reason == null) {
            throw new IllegalArgumentException("A moderation rule's target type and reason cannot be null");
        }
        if (threshold < 1) {
            throw new IllegalArgumentException(
                    "A moderation rule's threshold is at least 1 distinct reporter — got " + threshold);
        }
        return new ModerationRule(UUID.randomUUID(), targetType, reason, threshold);
    }

    /** The operator's revision — the auditing fields record who and when. */
    void setThreshold(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException(
                    "A moderation rule's threshold is at least 1 distinct reporter — got " + threshold);
        }
        this.threshold = threshold;
    }

    /** The operator's pause/resume — the same audit trail. */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public UUID getId() { return id; }
    public ReportTargetType getTargetType() { return targetType; }
    public ReportReason getReason() { return reason; }
    public int getThreshold() { return threshold; }
    public boolean isEnabled() { return enabled; }
}
