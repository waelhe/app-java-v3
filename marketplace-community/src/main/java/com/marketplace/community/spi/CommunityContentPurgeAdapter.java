package com.marketplace.community.spi;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * L41 (neighborhood community plan §5): the community module's
 * implementation of the {@link AuthoredContentPurgePort} cross-module
 * contract — <b>the documented exception</b> (the plan's own words:
 * "العضوية لا تحمل نصوصًا مؤلَّفة (مفاتيح وحالة) — تُوثَّق استثناءً في
 * محوّل الوحدة بقرار مُعلَّل: الصف بنيوي كالسجل المحاسبي، وb-5 يحكم
 * الاحتفاظ").
 *
 * <p><b>The reasoned decision:</b> a membership row is identifiers and
 * state — {@code user_id}, {@code location_id}, the verification state,
 * timestamps — exactly the category the search module's matches ledger
 * already established as out-of-scope ("identifiers and timestamps
 * only — no authored text, nothing to purge; the accounting-row analogy
 * in the b-3/b-5 discrimination"). There is no authored free text to
 * NULL or tombstone, so this adapter deliberately touches nothing and
 * reports zero — the membership row itself is structural, like the
 * accounting record, and its retention is b-5's decision (the export
 * carries it; the row stays).
 *
 * <p>The adapter still exists, on purpose: the identity module's purge
 * orchestration iterates every {@code AuthoredContentPurgePort} bean —
 * a module that silently abstains makes its purge decision invisible;
 * a module that appears in the loop with a documented zero makes the
 * exception an auditable, machine-visible fact (the same
 * completeness-over-silence reasoning that gave the purge contract its
 * eight converters). When a community entity that DOES carry authored
 * text arrives (L42's posts and comments), that layer's adapter purges
 * for real and this reasoning narrows to the membership row alone.
 */
@Component
public class CommunityContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(CommunityContentPurgeAdapter.class);

    @Override
    public int purgeAuthoredTexts(UUID userId) {
        // Deliberate no-op returning 0 — see the class javadoc for the
        // reasoned decision. The log line mirrors the house convention so
        // the orchestrator's audit trail shows the module's answer.
        log.debug("Community content purge: nothing to purge by design — the membership row "
                + "is structural (keys and state, no authored texts); b-5 governs its "
                + "retention. userId={}", userId);
        return 0;
    }
}
