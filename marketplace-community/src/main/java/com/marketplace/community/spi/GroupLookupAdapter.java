package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodGroupRepository;
import com.marketplace.shared.api.GroupLookupPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * JT-20 (the discovery waves — AC-20-05): the community module's
 * implementation of the {@link GroupLookupPort} cross-module contract —
 * the identity module's generalized follow checks a GROUP source's
 * liveness through this seam, never through a module dependency (the
 * {@code PostLookupAdapter} house pattern verbatim: the interface lives
 * in shared-api, the data owner implements it, the consumer injects it).
 *
 * <p>The LIVE-only contract is Hibernate's {@code @SoftDelete} filter's
 * own: a retired (withdrawn) group is absent from {@code existsById}
 * itself, so a dead club cannot gain new followers — the same honesty
 * the groups board read applies.
 */
@Component
@Transactional(readOnly = true)
public class GroupLookupAdapter implements GroupLookupPort {

    private final NeighborhoodGroupRepository groupRepository;

    public GroupLookupAdapter(NeighborhoodGroupRepository groupRepository) {
        this.groupRepository = groupRepository;
    }

    @Override
    public boolean exists(UUID groupId) {
        return groupRepository.existsById(groupId);
    }
}
