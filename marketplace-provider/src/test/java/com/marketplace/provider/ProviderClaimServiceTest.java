package com.marketplace.provider;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Instancio.create;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * J14 (the journeys register — «مطالبة صاحب العمل بالملف»): the claim
 * cycle engine's contracts — the FULL gate matrix the register's
 * confirmed gap demanded («لا دورة مطالبة/تحقق/اعتراض مقيسة» — now
 * measured): the pre-seed's ownerless page, the submission's gate order
 * BEFORE any write (unknown page 404, owned page 409, live claim 409,
 * the concurrent-race loss translated to the same 409), the verdict's
 * movers (APPROVE links the ownership — user_id AND claimed_at, one
 * transaction; REJECT leaves the page unowned and claimable again), the
 * claimant's own recovery lever (withdraw — 403 for anyone else, 409
 * once decided), and the queue's drain order.
 */
@ExtendWith(MockitoExtension.class)
class ProviderClaimServiceTest {

    @Mock
    private ProviderClaimRepository claimRepository;

    @Mock
    private ProviderRepository providerRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Instant now = Instant.parse("2026-10-18T12:00:00Z");
    private final Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);

    private ProviderClaimService service() {
        return new ProviderClaimService(claimRepository, providerRepository, eventPublisher, clock);
    }

    private ProviderProfile unclaimedPage() {
        return ProviderProfile.createUnclaimed("مخبز القصيّة", "الصفحة القابلة للمطالبة",
                ProviderActorType.INDIVIDUAL, null, null);
    }

    private ProviderProfile ownedPage(UUID ownerId) {
        return ProviderProfile.create("مخبز مملوك", "صفحة صاحبها مسجل", ownerId,
                ProviderActorType.INDIVIDUAL, null, null);
    }

    private ProviderClaim pendingClaim(UUID pageId, UUID claimantId) {
        return ProviderClaim.submit(pageId, claimantId, "+963-9xx-xxx-xxx", "أنا المالك");
    }

    // -- the pre-seed -------------------------------------------------------

    @Test
    void createUnclaimedProfileSeedsAnOwnerlessPage() {
        when(providerRepository.save(any(ProviderProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderRequest request = new ProviderRequest("مخبز القصيّة", "الخبز الطازج يومياً",
                ProviderActorType.INDIVIDUAL, null, null);

        ProviderProfile seeded = service().createUnclaimedProfile(request, "w5-admin");

        assertThat(seeded.getUserId()).as("the claimable page carries NO owner").isNull();
        assertThat(seeded.getClaimedAt()).as("no handover happened yet").isNull();
        assertThat(seeded.getStatus()).isEqualTo(ProviderStatus.PENDING);
        assertThat(seeded.getVerificationState()).isEqualTo(ProviderVerificationState.UNVERIFIED);
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    // -- the submission's gate matrix ---------------------------------------

    @Test
    void submitClaimOfAnUnknownPageAnswers404() {
        UUID unknown = create(UUID.class);
        when(providerRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().submitClaim(unknown, UUID.randomUUID(), "channel", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Provider not found");
        verifyNoInteractions(claimRepository);
    }

    @Test
    void submitClaimOnAnOwnedPageAnswers409BeforeAnyWrite() {
        UUID ownerId = UUID.randomUUID();
        ProviderProfile owned = ownedPage(ownerId);
        when(providerRepository.findById(owned.getId())).thenReturn(Optional.of(owned));

        assertThatThrownBy(() -> service().submitClaim(owned.getId(), UUID.randomUUID(), "channel", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already owned");
        verifyNoInteractions(claimRepository);
    }

    @Test
    void submitClaimUnderALiveClaimAnswers409() {
        ProviderProfile page = unclaimedPage();
        when(providerRepository.findById(page.getId())).thenReturn(Optional.of(page));
        when(claimRepository.existsByProviderIdAndState(page.getId(), ProviderClaimState.PENDING))
                .thenReturn(true);

        assertThatThrownBy(() -> service().submitClaim(page.getId(), UUID.randomUUID(), "channel", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("one claim at a time");
    }

    @Test
    void submitClaimRaceLossTranslatesTheUniqueIndexToTheSame409() {
        ProviderProfile page = unclaimedPage();
        when(providerRepository.findById(page.getId())).thenReturn(Optional.of(page));
        when(claimRepository.existsByProviderIdAndState(page.getId(), ProviderClaimState.PENDING))
                .thenReturn(false);
        // The concurrent-race loss: the V233 partial unique index fired
        // between the guard and the insert (the L31/23505 law).
        when(claimRepository.save(any(ProviderClaim.class)))
                .thenThrow(new DataIntegrityViolationException("uq_provider_claims_one_live"));

        assertThatThrownBy(() -> service().submitClaim(page.getId(), UUID.randomUUID(), "channel", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("one claim at a time");
    }

    @Test
    void submitClaimCreatesThePendingClaim() {
        ProviderProfile page = unclaimedPage();
        UUID claimant = UUID.randomUUID();
        when(providerRepository.findById(page.getId())).thenReturn(Optional.of(page));
        when(claimRepository.existsByProviderIdAndState(page.getId(), ProviderClaimState.PENDING))
                .thenReturn(false);
        when(claimRepository.save(any(ProviderClaim.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderClaim claim = service().submitClaim(page.getId(), claimant,
                "  +963-9xx-xxx-xxx  ", "أنا المالك");

        assertThat(claim.getState()).isEqualTo(ProviderClaimState.PENDING);
        assertThat(claim.getProviderId()).isEqualTo(page.getId());
        assertThat(claim.getClaimantUserId()).isEqualTo(claimant);
        assertThat(claim.getContactChannel()).as("the channel rides trimmed").isEqualTo("+963-9xx-xxx-xxx");
        assertThat(claim.getDecidedBy()).isNull();
        assertThat(claim.getDecidedAt()).isNull();
    }

    // -- the verdict's movers ----------------------------------------------

    @Test
    void reviewApproveLinksTheOwnershipAndItsOwnClock() {
        ProviderProfile page = unclaimedPage();
        UUID claimant = UUID.randomUUID();
        ProviderClaim claim = pendingClaim(page.getId(), claimant);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(providerRepository.findById(page.getId())).thenReturn(Optional.of(page));

        ProviderClaim reviewed = service().review(claim.getId(), true, "w5-admin", "الرخصة مطابقة");

        assertThat(reviewed.getState()).isEqualTo(ProviderClaimState.APPROVED);
        assertThat(reviewed.getDecidedBy()).isEqualTo("w5-admin");
        assertThat(reviewed.getDecisionNote()).isEqualTo("الرخصة مطابقة");
        assertThat(reviewed.getDecidedAt()).isEqualTo(now);
        // THE handover: the page's owner is the claimant, on the claim's own clock.
        assertThat(page.getUserId()).isEqualTo(claimant);
        assertThat(page.getClaimedAt()).isEqualTo(now);
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void reviewRejectLeavesThePageUnownedAndClaimableAgain() {
        ProviderProfile page = unclaimedPage();
        UUID claimant = UUID.randomUUID();
        ProviderClaim claim = pendingClaim(page.getId(), claimant);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(providerRepository.findById(page.getId())).thenReturn(Optional.of(page));

        ProviderClaim reviewed = service().review(claim.getId(), false, "w5-admin", "المستندات غير كافية");

        assertThat(reviewed.getState()).isEqualTo(ProviderClaimState.REJECTED);
        assertThat(page.getUserId()).as("the page stays unowned").isNull();
        assertThat(page.getClaimedAt()).isNull();

        // The claimant's recovery path: a FRESH claim — the live-claim gate
        // no longer blocks once the state moved.
        when(claimRepository.existsByProviderIdAndState(page.getId(), ProviderClaimState.PENDING))
                .thenReturn(false);
        when(claimRepository.save(any(ProviderClaim.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderClaim fresh = service().submitClaim(page.getId(), claimant, "channel", "مستندات جديدة");
        assertThat(fresh.getState()).isEqualTo(ProviderClaimState.PENDING);
    }

    @Test
    void reviewOfADecidedClaimAnswers409() {
        ProviderProfile page = unclaimedPage();
        ProviderClaim claim = pendingClaim(page.getId(), UUID.randomUUID());
        claim.approve("w5-admin", null, now);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service().review(claim.getId(), true, "w5-admin", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only PENDING");
        verifyNoInteractions(providerRepository, eventPublisher);
    }

    @Test
    void reviewOfAnUnknownClaimAnswers404() {
        UUID unknown = create(UUID.class);
        when(claimRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().review(unknown, true, "w5-admin", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Provider claim not found");
    }

    @Test
    void reviewApproveOnAPageThatGainedAnOwnerAnswers409() {
        // The entity-level backstop: the page was unowned at submission but
        // gained an owner before the verdict — a second handover would
        // silently orphan the first, so the machine refuses.
        ProviderProfile page = unclaimedPage();
        UUID otherOwner = UUID.randomUUID();
        page.claimOwnership(otherOwner, now.minusSeconds(60));
        ProviderClaim claim = pendingClaim(page.getId(), UUID.randomUUID());
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(providerRepository.findById(page.getId())).thenReturn(Optional.of(page));

        assertThatThrownBy(() -> service().review(claim.getId(), true, "w5-admin", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already owned");
        assertThat(claim.getState()).as("the claim stays PENDING — the transaction refused").isEqualTo(ProviderClaimState.PENDING);
    }

    // -- the claimant's own recovery lever ----------------------------------

    @Test
    void withdrawOnlyByTheClaimantAnswers403ForAnyoneElse() {
        ProviderClaim claim = pendingClaim(UUID.randomUUID(), UUID.randomUUID());
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service().withdraw(claim.getId(), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("do not own this provider claim");
    }

    @Test
    void withdrawMovesTheClaimantsOwnPendingClaim() {
        UUID claimant = UUID.randomUUID();
        ProviderClaim claim = pendingClaim(UUID.randomUUID(), claimant);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

        ProviderClaim withdrawn = service().withdraw(claim.getId(), claimant);

        assertThat(withdrawn.getState()).isEqualTo(ProviderClaimState.WITHDRAWN);
        assertThat(withdrawn.getDecidedBy()).as("the decider is the claimant's own hand")
                .isEqualTo(claimant.toString());
        assertThat(withdrawn.getDecisionNote()).isNull();
        assertThat(withdrawn.getDecidedAt()).isEqualTo(now);
    }

    @Test
    void withdrawOfADecidedClaimAnswers409() {
        ProviderClaim claim = pendingClaim(UUID.randomUUID(), UUID.randomUUID());
        claim.reject("w5-admin", null, now);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service().withdraw(claim.getId(), claim.getClaimantUserId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only PENDING");
    }

    @Test
    void withdrawOfAnUnknownClaimAnswers404() {
        UUID unknown = create(UUID.class);
        when(claimRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().withdraw(unknown, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Provider claim not found");
    }

    // -- the queue -----------------------------------------------------------

    @Test
    void claimsQueueDrainsOnTheClaimsOwnClock() {
        Sort queueSort = Sort.by(Sort.Direction.ASC, "updatedAt").and(Sort.by(Sort.Direction.ASC, "id"));
        ProviderClaim pending = pendingClaim(UUID.randomUUID(), UUID.randomUUID());
        when(claimRepository.findByState(ProviderClaimState.PENDING,
                PageRequest.of(0, 20, queueSort)))
                .thenReturn(new PageImpl<>(java.util.List.of(pending),
                        PageRequest.of(0, 20, queueSort), 1));

        var page = service().claims(null, ProviderClaimState.PENDING, PageRequest.of(0, 20));

        assertThat(page.getContent()).singleElement()
                .satisfies(claim -> assertThat(claim.getState()).isEqualTo(ProviderClaimState.PENDING));
    }

    /**
     * The register's J14 acceptance as one orchestration pin over the whole
     * cycle (the plan's §1.2 rule): pre-seed → claim → approve — the
     * ownership handover measured on the page itself.
     */
    @Test
    void theFullJourneySeedClaimApprove() {
        ProviderClaimService service = service();
        when(providerRepository.save(any(ProviderProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderRequest request = new ProviderRequest("مخبز القصيّة", "الخبز الطازج يومياً",
                ProviderActorType.INDIVIDUAL, null, null);

        // 1. The admin pre-seeds the claimable page — ownerless.
        ProviderProfile page = service.createUnclaimedProfile(request, "w5-admin");
        assertThat(page.getUserId()).isNull();

        // 2. The member claims it — born PENDING.
        UUID claimant = UUID.randomUUID();
        when(providerRepository.findById(page.getId())).thenReturn(Optional.of(page));
        when(claimRepository.existsByProviderIdAndState(page.getId(), ProviderClaimState.PENDING))
                .thenReturn(false);
        when(claimRepository.save(any(ProviderClaim.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderClaim claim = service.submitClaim(page.getId(), claimant, "+963-9xx-xxx-xxx", "أنا المالك");
        assertThat(claim.getState()).isEqualTo(ProviderClaimState.PENDING);

        // 3. The admin approves — the ownership lands on the claimant.
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        service.review(claim.getId(), true, "w5-admin", "الرخصة مطابقة");
        assertThat(page.getUserId()).isEqualTo(claimant);
        assertThat(page.getClaimedAt()).isEqualTo(now);
    }
}
