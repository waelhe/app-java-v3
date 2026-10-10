package com.marketplace.identity;

import com.marketplace.identity.spi.FollowedSourcesAdapter;
import com.marketplace.shared.api.FollowedSourcesPort;
import com.marketplace.shared.api.GroupLookupPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JT-20 (the discovery waves — AC-20-05) — the spi seam: the identity
 * module's {@code FollowedSourcesAdapter} implements the shared port and
 * its union reaches the wire as ONE honest set — USER + GROUP from
 * V177's {@code follows} and PROVIDER from V93's
 * {@code provider_follows}, the two homes the rail must never learn
 * about.
 */
class FollowedSourcesAdapterTest {

    private final FollowRepository repository = mock(FollowRepository.class);
    private final ProviderFollowRepository providerFollowRepository = mock(ProviderFollowRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final GroupLookupPort groupLookupPort = mock(GroupLookupPort.class);

    private FollowedSourcesAdapter adapter;

    @BeforeEach
    void setUp() {
        // The REAL service (the union body is production code) over mocked
        // stores — the adapter's contract is that the port's consumers see
        // the honest union, not a mock of it.
        FollowService service = new FollowService(repository, providerFollowRepository,
                userRepository, groupLookupPort);
        adapter = new FollowedSourcesAdapter(service);
    }

    @Test
    void theAdapterIsTheSharedPortsIdentityImplementation() {
        assertThat(adapter).isInstanceOf(FollowedSourcesPort.class);
    }

    @Test
    void followedSources_answersTheUnionOfUserGroupAndProviderTogether() {
        UUID userId = UUID.randomUUID();
        UUID followedUserId = UUID.randomUUID();
        UUID followedGroupId = UUID.randomUUID();
        UUID followedProviderUserId = UUID.randomUUID();
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(userId)).thenReturn(List.of(
                Follow.create(UUID.randomUUID(), userId, FollowableType.USER, followedUserId),
                Follow.create(UUID.randomUUID(), userId, FollowableType.GROUP, followedGroupId)));
        when(providerFollowRepository.findByUserIdOrderByCreatedAtDescIdDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        ProviderFollow.create(UUID.randomUUID(), userId, followedProviderUserId))));

        Set<FollowedSourcesPort.FollowedSource> sources = adapter.followedSources(userId);

        assertThat(sources).containsExactlyInAnyOrder(
                new FollowedSourcesPort.FollowedSource("USER", followedUserId),
                new FollowedSourcesPort.FollowedSource("GROUP", followedGroupId),
                new FollowedSourcesPort.FollowedSource("PROVIDER", followedProviderUserId));
    }
}
