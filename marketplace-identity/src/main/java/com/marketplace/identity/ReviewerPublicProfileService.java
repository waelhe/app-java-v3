package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewerStats;
import com.marketplace.shared.api.ReviewerStatsPort;
import com.marketplace.shared.api.UserSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — G28): the public reviewer page's read side —
 * the plan's own field list "اسم، طابع انضمام، عدّاد مراجعات
 * موثقة/عاملة، أوسمته" composed in one place.
 *
 * <p><b>Module ownership (the plan's §7 allocation: "صفحة المراجع من
 * marketplace-identity"):</b> identity owns the page because the name,
 * the join timestamp and the pseudonymization marker are the identity
 * module's own facts; the review counters and the helpful-vote total flow
 * through the {@link ReviewerStatsPort} seam (the W1
 * {@code PublishedReviewsPort} pattern — measured by the reviews module,
 * rendered here); the badges derive here (G29's presentation rules live
 * with the page, the measurement with the data — one owner each).
 *
 * <p><b>The name rule (I7/W1):</b> the display name honours
 * {@code pseudonymized_at} through {@link UserSummary#publicDisplayName()}
 * — the single shared rule every public surface answers ("Former member"
 * for a closed account, never the stored profile columns, never the
 * email).
 *
 * <p><b>The 404 contract:</b> the users row is the page's existence
 * authority — an unknown id answers the honest 404; a live user with no
 * published reviews is the "not yet a reviewer" profile (all-zero
 * counters, no badges), the same 200-empty contract
 * {@code GET /api/v1/reviews/reviewer/{id}} already answers for the
 * activity list.
 */
@Service
@Transactional(readOnly = true)
public class ReviewerPublicProfileService {

    private final UserRepository userRepository;
    private final ReviewerStatsPort reviewerStatsPort;

    public ReviewerPublicProfileService(UserRepository userRepository,
                                         ReviewerStatsPort reviewerStatsPort) {
        this.userRepository = userRepository;
        this.reviewerStatsPort = reviewerStatsPort;
    }

    public ReviewerPublicProfile getPublicProfile(UUID reviewerId) {
        User user = userRepository.findById(reviewerId)
                .orElseThrow(() -> new ResourceNotFoundException("Reviewer not found: " + reviewerId));

        ReviewerStats stats = reviewerStatsPort.findReviewerStats(reviewerId);

        return new ReviewerPublicProfile(
                user.getId(),
                publicName(user),
                user.getCreatedAt(),
                stats.verifiedReviewCount(),
                stats.organicReviewCount(),
                stats.helpfulVoteCount(),
                ReviewerBadge.derive(stats));
    }

    /**
     * The public name through the shared summary rule — the same
     * 7-arg construction {@code UserLookupPortImpl.toSummary} uses, so the
     * page answers the same word for the same account as every other
     * public surface (the email is deliberately never constructed: the
     * public-name contract never reads it).
     */
    private static String publicName(User user) {
        return new UserSummary(user.getId(), null, user.getDisplayName(),
                user.getRole().name(), user.getCreatedAt(), user.getUpdatedAt(),
                user.getPseudonymizedAt()).publicDisplayName();
    }
}
