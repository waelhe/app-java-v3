package com.marketplace.identity;

import java.util.List;
import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 + §7 Phase 3
 * row — the extended purges, executed on the user's gate-opening word):
 * the identity module's orchestration of the free-text purge. Official
 * basis: the plan's purge option, verbatim — "تطهير نصوص مرسلها
 * ({@code messages.content}/{@code bookings.notes}…) — UPDATE عبر
 * الجداول — يُمسّ تاريخ الطرف المقابل؛ يُقيَّد بحقوق الآخرين (Art.
 * 20(4))".
 *
 * <p><b>The aggregation shape (the plan's R3 sentence, the Phase 2
 * precedent):</b> each module purges its share through its own
 * {@link AuthoredContentPurgePort} implementation (shared-api contract,
 * implemented by the owning modules' {@code spi} adapters), and identity
 * orchestrates through those ports — Spring injects every implementation
 * as the {@code List} below. No module boundary is crossed: identity sees
 * only the shared-api type, {@code ModulithVerificationTest} stays the
 * unedited guard.
 *
 * <p><b>Deliberately NOT one transaction (the plan's §2 b-3 gradualism
 * shape, the §7 "لكلٍّ PRه المستقل بحرّاسه" gradualism):</b> each adapter
 * runs in its own transaction, so a mid-run failure leaves the completed
 * modules committed and the re-run resumes — the purge is idempotent by
 * the port contract (already-purged values match nothing). This service
 * itself carries no {@code @Transactional}: wrapping the whole
 * orchestration would rebuild the single heavy transaction the plan
 * explicitly keeps the purge out of.
 *
 * <p><b>The guard:</b> the purge completes an erasure flow — the target
 * account must already be pseudonymized (Phase 1). A live account's texts
 * are the product's active content; purging them with the identity still
 * alive would damage the counterparties' shared history with no erasure
 * context. {@code pseudonymized_at == null} answers 409 before any
 * statement runs — the same defensive ordering as the last-active-ADMIN
 * guard (the mutation never starts on a rejected target).
 *
 * <p><b>The write-path discipline (the Phase 1 lesson):</b> the guard
 * reads the repository directly, never the {@code @Cacheable} read path —
 * a detached cache copy would bypass dirty checking for any subsequent
 * entity write; this purge issues no entity writes (native SQL through
 * the adapters), but the guard keeps the convention's exact shape.
 *
 * <p><b>The audit record:</b> one structured log line — actor, target
 * (userId), reason, total purged rows — the payments convention, the
 * Phase 1 precedent; no text content ever enters the log store
 * (CWE-532, the same discipline the pseudonymization audit line applies).
 * The per-module breakdown is each adapter's own log line.
 */
@Service
public class AuthoredContentPurgeService {

    private static final Logger log = LoggerFactory.getLogger(AuthoredContentPurgeService.class);

    private final UserRepository userRepository;
    private final List<AuthoredContentPurgePort> contentPurgePorts;

    public AuthoredContentPurgeService(UserRepository userRepository,
                                       List<AuthoredContentPurgePort> contentPurgePorts) {
        this.userRepository = userRepository;
        this.contentPurgePorts = List.copyOf(contentPurgePorts);
    }

    /**
     * Purges every free text the (already-pseudonymized) subject authored
     * across the owning modules, and returns the total number of rows
     * whose text was actually purged (base tables + Envers mirrors). A
     * re-run on an already-purged subject returns zero — the port
     * contract's idempotence.
     *
     * @throws ResourceNotFoundException no users row for the id
     * @throws ConflictException          the account is not pseudonymized
     *                                    — the purge completes an
     *                                    erasure flow, it never operates
     *                                    on a live account
     */
    @Observed(name = "user.content.purge")
    public int purge(UUID userId, String reason, String actor) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getPseudonymizedAt() == null) {
            throw new ConflictException(
                    "Content purge requires a pseudonymized account — pseudonymize first "
                            + "(the purge completes an erasure flow; a live account's texts are "
                            + "active content)");
        }

        int total = 0;
        for (AuthoredContentPurgePort port : contentPurgePorts) {
            total += port.purgeAuthoredTexts(userId);
        }

        log.info("Authored content purge: userId={}, actor={}, reason='{}', "
                        + "modules={}, rowsPurged={} (base + Envers mirrors)",
                userId, actor, reason, contentPurgePorts.size(), total);
        return total;
    }
}
