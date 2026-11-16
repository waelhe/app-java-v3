package com.marketplace.community.spi;

import com.marketplace.community.MembershipVerificationState;
import com.marketplace.community.NeighborhoodMembershipService;
import com.marketplace.shared.api.NeighborhoodVerificationQueueEntry;
import com.marketplace.shared.api.NeighborhoodVerificationQueuePort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The community module's implementation of the
 * {@link NeighborhoodVerificationQueuePort} cross-module contract — the
 * manager console's queue board reads and moves the verification queue
 * through this seam, never through a module dependency (the
 * {@code CommunityMembershipAdapter} house pattern verbatim: the
 * interface lives in shared-api, the data owner implements it, the
 * consumer injects it).
 *
 * <p><b>No parallel write path:</b> every verdict is the SAME
 * {@code reviewVerification} service call the module's own REST admin
 * controller ({@code NeighborhoodVerificationAdminController}) makes —
 * the adapter is pure delegation, so the service's own transaction,
 * observation ({@code community.membership.verification.review}), and
 * method-security ({@code @PreAuthorize("hasRole('ADMIN')")}) contracts
 * apply unchanged to the web lever.</p>
 */
@Component
public class NeighborhoodVerificationQueueAdapter implements NeighborhoodVerificationQueuePort {

    private final NeighborhoodMembershipService membershipService;

    public NeighborhoodVerificationQueueAdapter(NeighborhoodMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @Override
    public List<NeighborhoodVerificationQueueEntry> pendingVerificationQueue(int limit) {
        return membershipService
                .getVerificationQueue(MembershipVerificationState.PENDING, PageRequest.of(0, limit))
                .map(view -> new NeighborhoodVerificationQueueEntry(
                        view.id(),
                        view.userId(),
                        view.locationId(),
                        view.verificationState(),
                        view.createdAt(),
                        view.updatedAt()))
                .getContent();
    }

    @Override
    public void reviewVerification(UUID membershipId, boolean approve) {
        membershipService.reviewVerification(membershipId, approve);
    }
}
