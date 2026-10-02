package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewerStats;
import com.marketplace.shared.api.ReviewerStatsPort;
import com.marketplace.shared.api.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W4 (yelp-level plan §5 — G28/G29) — the public reviewer page's read
 * side: the plan's own field list (name, join timestamp, split counters,
 * badges), the pseudonymization-honouring name rule, and the honest 404
 * for an unknown account.
 */
class ReviewerPublicProfileServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ReviewerStatsPort reviewerStatsPort = mock(ReviewerStatsPort.class);

    private ReviewerPublicProfileService service;

    @BeforeEach
    void setUp() {
        service = new ReviewerPublicProfileService(userRepository, reviewerStatsPort);
    }

    private static User user(UUID id, String displayName) {
        return new User(id, "subject-" + id, "login-" + id + "@example.com", displayName, UserRole.CONSUMER);
    }

    @Test
    void getPublicProfile_composesThePlanFieldListWithBadges() {
        UUID reviewerId = UUID.randomUUID();
        User reviewer = user(reviewerId, "Sara");
        when(userRepository.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(reviewerStatsPort.findReviewerStats(reviewerId))
                .thenReturn(new ReviewerStats(4L, 12L, 40L));

        ReviewerPublicProfile profile = service.getPublicProfile(reviewerId);

        assertEquals(reviewerId, profile.reviewerId());
        assertEquals("Sara", profile.displayName());
        assertEquals(reviewer.getCreatedAt(), profile.joinedAt());
        assertEquals(4L, profile.verifiedReviewCount());
        assertEquals(12L, profile.organicReviewCount());
        assertEquals(40L, profile.helpfulVoteCount());
        assertEquals(List.of(ReviewerBadge.VERIFIED_REVIEWER, ReviewerBadge.HELPFUL_REVIEWER),
                profile.badges());
    }

    @Test
    void getPublicProfile_honoursThePseudonymizationMarker() {
        // I7/W1: a closed account answers the neutral label — the same word
        // every public surface answers for the same account. The entity's
        // own one-transaction marker application (the same method the
        // pseudonymization command runs).
        User closed = User.create("subject-closed", "closed@example.com", "Real Name", UserRole.CONSUMER);
        closed.applyPseudonymization("anon-closed");
        when(userRepository.findById(closed.getId())).thenReturn(Optional.of(closed));
        when(reviewerStatsPort.findReviewerStats(closed.getId()))
                .thenReturn(new ReviewerStats(2L, 0L, 0L));

        ReviewerPublicProfile profile = service.getPublicProfile(closed.getId());

        assertEquals(UserSummary.FORMER_MEMBER_LABEL, profile.displayName());
    }

    @Test
    void getPublicProfile_aBlankDisplayNameFallsBackToTheGenericLabel() {
        UUID reviewerId = UUID.randomUUID();
        when(userRepository.findById(reviewerId))
                .thenReturn(Optional.of(user(reviewerId, "  ")));
        when(reviewerStatsPort.findReviewerStats(reviewerId))
                .thenReturn(new ReviewerStats(0L, 0L, 0L));

        assertEquals("User", service.getPublicProfile(reviewerId).displayName());
    }

    @Test
    void getPublicProfile_aNeverReviewerIsTheHonestZeroProfile() {
        UUID reviewerId = UUID.randomUUID();
        when(userRepository.findById(reviewerId))
                .thenReturn(Optional.of(user(reviewerId, "Nour")));
        when(reviewerStatsPort.findReviewerStats(reviewerId))
                .thenReturn(new ReviewerStats(0L, 0L, 0L));

        ReviewerPublicProfile profile = service.getPublicProfile(reviewerId);

        assertEquals(0L, profile.verifiedReviewCount());
        assertEquals(0L, profile.organicReviewCount());
        assertEquals(0L, profile.helpfulVoteCount());
        assertEquals(List.of(), profile.badges());
    }

    @Test
    void getPublicProfile_unknownAccountIsTheHonest404() {
        UUID reviewerId = UUID.randomUUID();
        when(userRepository.findById(reviewerId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getPublicProfile(reviewerId));
    }
}
