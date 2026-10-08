package com.marketplace.community;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * B-19 (compliance plan C.11): the rules' own CRUD — the operator's
 * registration, revision, pause and retirement, all behind the
 * administrative role gate (the class-level controller gate + the
 * service-level defense-in-depth gate — the L30 three-layer pattern the
 * whole moderation surface rides). The values are DATA: no rule ever
 * ships in a migration, and a retired rule's row stays (the audit
 * trail) while releasing its {@code (targetType, reason)} slot for a
 * fresh registration — the V70/V157/V160 partial-unique discipline.
 */
@Service
@Transactional
public class ModerationRuleService {

    private final ModerationRuleRepository ruleRepository;

    public ModerationRuleService(ModerationRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    /**
     * The registration — born ENABLED (a registered rule is live; the
     * pause is the operator's separate verb). A duplicate LIVE
     * {@code (targetType, reason)} pair answers 409 — the V161 partial
     * unique's polite face (the 23505⇒409 house translation).
     */
    @Observed(name = "community.rule.register")
    public ModerationRule registerRule(ReportTargetType targetType, ReportReason reason, int threshold) {
        ruleRepository.findByTargetTypeAndReason(targetType, reason)
                .ifPresent(existing -> {
                    throw new ConflictException("A live moderation rule already exists for "
                            + targetType + "/" + reason + " (" + existing.getId() + ")");
                });
        return ruleRepository.save(ModerationRule.register(targetType, reason, threshold));
    }

    /** The board — every live rule in the stable (targetType, reason) order. */
    @Transactional(readOnly = true)
    public List<ModerationRule> rules() {
        return ruleRepository.findAllByOrderByTargetTypeAscReasonAsc();
    }

    /**
     * The operator's revision — the threshold is the rule's whole
     * value; the auditing fields record who and when. Unknown rule
     * answers the honest 404.
     */
    @Observed(name = "community.rule.update")
    public ModerationRule setThreshold(UUID ruleId, int threshold) {
        ModerationRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Moderation rule", ruleId));
        rule.setThreshold(threshold);
        return rule;
    }

    /**
     * The operator's pause/resume — a disabled rule is skipped by the
     * evaluation lookup without losing the row (the flags' own toggle
     * semantics).
     */
    @Observed(name = "community.rule.toggle")
    public ModerationRule setEnabled(UUID ruleId, boolean enabled) {
        ModerationRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Moderation rule", ruleId));
        rule.setEnabled(enabled);
        return rule;
    }

    /**
     * The retirement — the house soft delete: the row stays for the
     * audit trail (the Envers DEL revision + the auditing fields) and
     * the {@code (targetType, reason)} slot releases for a fresh
     * registration.
     */
    @Observed(name = "community.rule.retire")
    public void retireRule(UUID ruleId) {
        ModerationRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Moderation rule", ruleId));
        ruleRepository.delete(rule);
    }
}
