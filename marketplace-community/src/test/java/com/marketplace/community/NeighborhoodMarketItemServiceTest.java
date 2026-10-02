package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L50 — the market board service's gate orders, unit-pinned (the plan's
 * acceptance criteria 1-6; the module integration test proves the same
 * against the real schema):
 *
 * <ul>
 *   <li>the publish gate order: geo resolve (the port's own 404) →
 *       level-3 (400) → active, writable membership in exactly that
 *       location (403 — absent, a different neighborhood, or a
 *       REJECTED verification) → the ONE pricing rule (each face a
 *       400) → insert;</li>
 *   <li>the board read gate: no active membership ⇒ 403 (G-N3's
 *       default); the board carries sellerVerified (the grouped
 *       membership batch over the page's authors) + mine (the
 *       caller's own authorship) per row; the empty page costs no
 *       batch read;</li>
 *   <li>the withdraw: only the author (403 otherwise), the honest 404
 *       for an unknown item, and the SECOND withdraw of the same item
 *       answers the same honest 404 (the soft-deleted row is absent
 *       to the find — the aggregate's own is_deleted semantics).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodMarketItemServiceTest {

    @Mock
    private NeighborhoodMarketItemRepository itemRepository;

    @Mock
    private NeighborhoodMembershipRepository membershipRepository;

    @Mock
    private GeoLookupPort geoLookupPort;

    private NeighborhoodMarketItemService service;

    @BeforeEach
    void setUp() {
        service = new NeighborhoodMarketItemService(itemRepository, membershipRepository,
                geoLookupPort);
    }

    private UUID authorId = UUID.randomUUID();
    private UUID otherMemberId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();
    private UUID itemId = UUID.randomUUID();

    private GeoLookupPort.GeoNode node(int level) {
        return new GeoLookupPort.GeoNode(locationId, null, level, "حي", null, "node");
    }

    private NeighborhoodMembership membershipOf(UUID user, UUID location) {
        return NeighborhoodMembership.join(user, location,
                java.time.Clock.systemUTC());
    }

    /** The full manual-administrative review path: join → request → approve. */
    private NeighborhoodMembership membershipVerified(UUID user, UUID location) {
        NeighborhoodMembership membership = membershipOf(user, location);
        membership.requestVerification();
        membership.approveVerification();
        return membership;
    }

    /** The full refusal path: join → request → reject. */
    private NeighborhoodMembership membershipRejected(UUID user, UUID location) {
        NeighborhoodMembership membership = membershipOf(user, location);
        membership.requestVerification();
        membership.rejectVerification();
        return membership;
    }

    private NeighborhoodMarketItem itemBy(UUID author, UUID location, MarketCategory category,
                                          Integer priceCents) {
        return NeighborhoodMarketItem.marketItem(author, location, category,
                "أريكة جلسة عائلية", MarketCondition.GOOD, priceCents,
                priceCents == null ? null : "SAR", "شارع المسجد - مربع 2");
    }

    // ---------- createItem: the gate order ----------

    @Test
    void createItem_unknownLocation_isThePortsOwn404() {
        when(geoLookupPort.getLocation(locationId))
                .thenThrow(new ResourceNotFoundException("Location", locationId));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.FURNITURE, "Title", MarketCondition.GOOD, 48000, "SAR", "Spot"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItem_nonLevel3Node_is400BeforeAnyWrite() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(2));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.FURNITURE, "Title", MarketCondition.GOOD, 48000, "SAR", "Spot"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItem_noMembership_is403() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.FURNITURE, "Title", MarketCondition.GOOD, 48000, "SAR", "Spot"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItem_membershipInAnotherNeighborhood_is403() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.FURNITURE, "Title", MarketCondition.GOOD, 48000, "SAR", "Spot"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("your own neighborhood");
        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItem_rejectedVerification_is403TheWriteGate() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        NeighborhoodMembership rejected = membershipRejected(authorId, locationId);
        when(membershipRepository.findByUserId(authorId)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.FURNITURE, "Title", MarketCondition.GOOD, 48000, "SAR", "Spot"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Rejected neighborhood verification");
        verify(itemRepository, never()).save(any());
    }

    // ---------- createItem: the ONE pricing rule ----------

    @Test
    void createItem_freeCategoryWithAnyPrice_is400AGiftCarriesNoPrice() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.FREE, "Title", MarketCondition.LIKE_NEW, 12000, "SAR", "Spot"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("FREE item is a gift");
        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItem_freeCategoryWithCurrencyOnly_is400() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.FREE, "Title", MarketCondition.LIKE_NEW, null, "SAR", "Spot"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("gift");
        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItem_saleCategoryWithoutPrice_is400() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.ELECTRONICS, "Title", MarketCondition.GOOD, null, null, "Spot"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("strictly positive price");
        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItem_nonPositivePrice_is400() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.TOOLS, "Title", MarketCondition.GOOD, 0, "SAR", "Spot"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("strictly positive price");
        verify(itemRepository, never()).save(any());
    }

    @Test
    void createItem_nonIsoCurrency_is400() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.FURNITURE, "Title", MarketCondition.GOOD, 48000, "rsa", "Spot"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ISO 4217");
        verify(itemRepository, never()).save(any());
    }

    /**
     * The review round's own case (greptile + CodeRabbit agree): a
     * three-uppercase-letter NON-ISO code passes the regex shape but has
     * no ISO 4217 meaning — the JDK's currency table is the authority the
     * house helper already carries (Currencies.normalize), and the 400
     * teaches the caller before the V90 backstop ever sees the row.
     */
    @Test
    void createItem_unknownThreeLetterCurrency_is400() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createItem(authorId, locationId,
                MarketCategory.FURNITURE, "Title", MarketCondition.GOOD, 48000, "ZZZ", "Spot"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ISO 4217")
                .hasMessageContaining("ZZZ");
        verify(itemRepository, never()).save(any());
    }

    /**
     * The stored form is the canonical uppercase code — the house helper
     * normalizes on the way in (trim/upper), so the DB CHECK's uppercase
     * pin and the ISO authority agree on one representation.
     */
    @Test
    void createItem_normalizesTheStoredCurrencyForm() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        when(itemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createItem(authorId, locationId, MarketCategory.FURNITURE, "Title",
                MarketCondition.GOOD, 48000, " sar ", "Spot");

        org.mockito.ArgumentCaptor<NeighborhoodMarketItem> saved =
                org.mockito.ArgumentCaptor.forClass(NeighborhoodMarketItem.class);
        verify(itemRepository).save(saved.capture());
        assertThat(saved.getValue().getPriceCurrency()).isEqualTo("SAR");
    }

    // ---------- createItem: the honest echo ----------

    @Test
    void createItem_validPricedItem_echoesMineTrueAndTheUnverifiedFloorBadge() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodMarketItem saved = itemBy(authorId, locationId,
                MarketCategory.FURNITURE, 48000);
        when(itemRepository.save(any())).thenReturn(saved);

        NeighborhoodMarketItemView view = service.createItem(authorId, locationId,
                MarketCategory.FURNITURE, "أريكة جلسة عائلية 7 مقاعد — قماش قابل للغسل",
                MarketCondition.GOOD, 48000, "SAR", "شارع المسجد - مربع 2");

        assertThat(view.mine()).isTrue();
        assertThat(view.sellerVerified()).isFalse();
        assertThat(view.category()).isEqualTo("FURNITURE");
        assertThat(view.condition()).isEqualTo("GOOD");
        assertThat(view.priceCents()).isEqualTo(48000);
        assertThat(view.priceCurrency()).isEqualTo("SAR");
        assertThat(view.status()).isEqualTo("ACTIVE");
    }

    @Test
    void createItem_validFreeGift_carriesNoPriceAtAll() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodMarketItem saved = itemBy(authorId, locationId, MarketCategory.FREE, null);
        when(itemRepository.save(any())).thenReturn(saved);

        NeighborhoodMarketItemView view = service.createItem(authorId, locationId,
                MarketCategory.FREE, "مكتب دراسي خشبي بحالة ممتازة — إهداء لأسرة طلاب",
                MarketCondition.LIKE_NEW, null, null, "شارع المسجد - مربع 2");

        assertThat(view.priceCents()).isNull();
        assertThat(view.priceCurrency()).isNull();
        assertThat(view.category()).isEqualTo("FREE");
    }

    @Test
    void createItem_verifiedMember_echoesTheEarnedBadge() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        NeighborhoodMembership verified = membershipVerified(authorId, locationId);
        when(membershipRepository.findByUserId(authorId)).thenReturn(Optional.of(verified));
        when(itemRepository.save(any()))
                .thenReturn(itemBy(authorId, locationId, MarketCategory.FURNITURE, 48000));

        NeighborhoodMarketItemView view = service.createItem(authorId, locationId,
                MarketCategory.FURNITURE, "Title", MarketCondition.GOOD, 48000, "SAR", "Spot");

        assertThat(view.sellerVerified()).isTrue();
    }

    // ---------- getBoard ----------

    @Test
    void getBoard_noMembership_is403() {
        when(membershipRepository.findByUserId(authorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBoard(authorId, null, null, false,
                PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(itemRepository, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class));
    }

    @Test
    void getBoard_carriesTheGroupedBadgeAndMinePerRow() {
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodMarketItem own = itemBy(authorId, locationId, MarketCategory.FREE, null);
        NeighborhoodMarketItem others = itemBy(otherMemberId, locationId,
                MarketCategory.FURNITURE, 48000);
        NeighborhoodMembership otherVerified = membershipVerified(otherMemberId, locationId);
        when(itemRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(own, others)));
        when(membershipRepository.findByUserIdIn(anyCollection()))
                .thenReturn(List.of(otherVerified));

        Page<NeighborhoodMarketItemView> board = service.getBoard(authorId, null, null, false,
                PageRequest.of(0, 20));

        assertThat(board.getContent()).hasSize(2);
        // The caller's own row: mine=true, badge from their own membership
        // (absent from the batch below — the honest unverified floor).
        NeighborhoodMarketItemView ownView = board.getContent().get(0);
        assertThat(ownView.mine()).isTrue();
        assertThat(ownView.sellerVerified()).isFalse();
        // The other member's row: mine=false, the EARNED badge.
        NeighborhoodMarketItemView otherView = board.getContent().get(1);
        assertThat(otherView.mine()).isFalse();
        assertThat(otherView.sellerVerified()).isTrue();
    }

    @Test
    void getBoard_authorWhoLeftRendersTheUnverifiedFloor() {
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodMarketItem leftAuthorsItem = itemBy(otherMemberId, locationId,
                MarketCategory.TOOLS, 26000);
        when(itemRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(leftAuthorsItem)));
        // The batch read returns NOTHING for the author — they left.
        when(membershipRepository.findByUserIdIn(anyCollection()))
                .thenReturn(List.of());

        Page<NeighborhoodMarketItemView> board = service.getBoard(authorId, null, null, false,
                PageRequest.of(0, 20));

        assertThat(board.getContent().get(0).sellerVerified()).isFalse();
    }

    /**
     * The review round's root case (greptile P1 + CodeRabbit Major agree):
     * an author who left THIS neighborhood for another one — even earning
     * VERIFIED there — keeps their old items readable on this board, but
     * the badge does not follow them out: the batch read returns the
     * author's CURRENT membership (another location, VERIFIED), and the
     * board's location-scoped filter still renders the honest floor.
     */
    @Test
    void getBoard_authorVerifiedElsewhere_isTheUnverifiedFloorHere() {
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodMarketItem moversItem = itemBy(otherMemberId, locationId,
                MarketCategory.ELECTRONICS, 15000);
        when(itemRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(moversItem)));
        // The mover's CURRENT row: a VERIFIED membership in ANOTHER
        // neighborhood — exactly the badge-follows-the-seller defect the
        // round named.
        UUID elsewhere = UUID.randomUUID();
        when(membershipRepository.findByUserIdIn(anyCollection()))
                .thenReturn(List.of(membershipVerified(otherMemberId, elsewhere)));

        Page<NeighborhoodMarketItemView> board = service.getBoard(authorId, null, null, false,
                PageRequest.of(0, 20));

        assertThat(board.getContent().get(0).sellerVerified()).isFalse();
    }

    @Test
    void getBoard_emptyPage_costsNoBatchRead() {
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        when(itemRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<NeighborhoodMarketItemView> board = service.getBoard(authorId, null, null, false,
                PageRequest.of(0, 20));

        assertThat(board.getContent()).isEmpty();
        verify(membershipRepository, never()).findByUserIdIn(anyCollection());
    }

    // ---------- deleteByAuthor ----------

    @Test
    void deleteByAuthor_unknownItem_isTheHonest404() {
        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteByAuthor(authorId, itemId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(itemRepository, never()).delete(any(NeighborhoodMarketItem.class));
    }

    @Test
    void deleteByAuthor_notTheAuthor_is403() {
        when(itemRepository.findById(itemId))
                .thenReturn(Optional.of(itemBy(otherMemberId, locationId,
                        MarketCategory.FURNITURE, 48000)));

        assertThatThrownBy(() -> service.deleteByAuthor(authorId, itemId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only the item's author");
        verify(itemRepository, never()).delete(any(NeighborhoodMarketItem.class));
    }

    @Test
    void deleteByAuthor_theAuthor_isSoftDeleted() {
        NeighborhoodMarketItem own = itemBy(authorId, locationId, MarketCategory.FREE, null);
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(own));

        service.deleteByAuthor(authorId, itemId);

        verify(itemRepository).delete(own);
    }

    @Test
    void deleteByAuthor_theSecondWithdrawOfTheSameItem_isTheSameHonest404() {
        // The measured draft lesson, pinned as the contract: the first
        // withdraw soft-deletes the row, and the SECOND call on the
        // SAME id finds nothing (the @SoftDelete filter hides it) and
        // answers the honest 404 — one id, both rounds, never two
        // different UUIDs pretending to be the same item.
        when(itemRepository.findById(itemId))
                .thenReturn(Optional.of(itemBy(authorId, locationId,
                        MarketCategory.OTHER, 12000)))
                .thenReturn(Optional.empty());

        service.deleteByAuthor(authorId, itemId);
        assertThatThrownBy(() -> service.deleteByAuthor(authorId, itemId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
