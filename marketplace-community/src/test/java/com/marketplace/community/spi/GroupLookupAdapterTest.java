package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodGroupRepository;
import com.marketplace.shared.api.GroupLookupPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * JT-20 (the discovery waves — AC-20-05): the groups' existence seam,
 * unit-pinned — the LIVE-only contract is the {@code @SoftDelete} filter's
 * own ({@code existsById} hides the withdrawn groups, so a retired club
 * cannot gain new followers), and the adapter is a pure pass-through to
 * the groups' own repository (no module dependency crosses the boundary —
 * the {@code PostLookupAdapter} house pattern verbatim).
 */
@ExtendWith(MockitoExtension.class)
class GroupLookupAdapterTest {

    @Mock
    private NeighborhoodGroupRepository groupRepository;

    @InjectMocks
    private GroupLookupAdapter adapter;

    @Test
    void exists_answersTheGroupsOwnRepositoryVerdict() {
        UUID groupId = UUID.randomUUID();
        when(groupRepository.existsById(groupId)).thenReturn(true);

        assertThat(adapter.exists(groupId)).isTrue();
        assertThat(adapter).isInstanceOf(GroupLookupPort.class);
    }

    @Test
    void exists_answersFalseForAnUnknownOrWithdrawnGroup() {
        UUID groupId = UUID.randomUUID();
        when(groupRepository.existsById(groupId)).thenReturn(false);

        assertThat(adapter.exists(groupId)).isFalse();
    }
}
