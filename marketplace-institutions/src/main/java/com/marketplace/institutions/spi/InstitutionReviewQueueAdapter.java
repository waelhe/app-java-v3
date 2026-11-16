package com.marketplace.institutions.spi;

import com.marketplace.institutions.Institution;
import com.marketplace.institutions.InstitutionService;
import com.marketplace.institutions.InstitutionVerificationState;
import com.marketplace.shared.api.InstitutionReviewQueueEntry;
import com.marketplace.shared.api.InstitutionReviewQueuePort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The institutions module's implementation of the
 * {@link InstitutionReviewQueuePort} cross-module contract — the manager
 * console's registry board reads and moves the PENDING trust-mark queue
 * through this seam, never through a module dependency (the
 * {@code UrgentAlertsAdapter} house sibling in this very package: the
 * interface lives in shared-api, the data owner implements it, the
 * consumer injects it).
 *
 * <p><b>The entity stays inside:</b> the queue read maps the
 * {@link Institution} entity onto the boundary record HERE — the
 * mapping is the owning module's own business (the REST surface's own
 * {@code InstitutionResponse.from} does the same on its side), and the
 * consumer never sees a JPA type. The verdict is the SAME
 * {@code review} service call the module's own REST admin controller
 * ({@code InstitutionAdminController}) makes — no parallel write
 * path.</p>
 */
@Component
public class InstitutionReviewQueueAdapter implements InstitutionReviewQueuePort {

    private final InstitutionService institutionService;

    public InstitutionReviewQueueAdapter(InstitutionService institutionService) {
        this.institutionService = institutionService;
    }

    @Override
    public List<InstitutionReviewQueueEntry> pendingReviewQueue(int limit) {
        return institutionService
                .reviewQueue(InstitutionVerificationState.PENDING, PageRequest.of(0, limit))
                .map(institution -> new InstitutionReviewQueueEntry(
                        institution.getId(),
                        institution.getName(),
                        institution.getType().name(),
                        institution.getLocationId(),
                        institution.getRepresentativeId()))
                .getContent();
    }

    @Override
    public void reviewInstitution(UUID institutionId, boolean approve) {
        institutionService.review(institutionId, approve);
    }
}
