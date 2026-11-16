package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * B-19 (compliance plan C.11): the automatic moderation rules' own
 * repository — the evaluation lookup and the operator's board, both
 * derived query methods (the Data JPA
 * {@code query-methods-details} reference's own channel) over the
 * BaseEntity {@code @SoftDelete} live-row filter.
 */
public interface ModerationRuleRepository extends JpaRepository<ModerationRule, UUID> {

    /**
     * The duplicate gate's own lookup: the LIVE rule for the pair —
     * enabled OR disabled (the partial unique covers every live row:
     * the pair is the identity, the toggle only pauses), so a paused
     * rule still holds its slot until retired.
     */
    Optional<ModerationRule> findByTargetTypeAndReason(ReportTargetType targetType, ReportReason reason);

    /**
     * The evaluation's ONE lookup: the live rule for the report's own
     * {@code (targetType, reason)} pair — the {@code AndEnabledTrue}
     * arm IS the pause (a disabled rule never enters the evaluation;
     * the request-time read, never a boot-time conditional — the C.11
     * mandate rides the report-creation transaction).
     */
    Optional<ModerationRule> findByTargetTypeAndReasonAndEnabledTrue(
            ReportTargetType targetType, ReportReason reason);

    /** The board's inventory — every live rule in the stable (targetType, reason) order. */
    List<ModerationRule> findAllByOrderByTargetTypeAscReasonAsc();
}
