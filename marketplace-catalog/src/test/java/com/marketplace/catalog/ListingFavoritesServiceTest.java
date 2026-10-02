package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3 (yelp-level-plan §5 — G19): the saved-listings surface's unit
 * guards — the gate order (the listing resolves FIRST, then the pair's
 * own uniqueness), the withdraw's honest 404, the list's grouped
 * composition carrying the listing's CURRENT status, and the
 * results-surface flag.
 */
class ListingFavoritesServiceTest {

    private ListingFavoriteRepository favoriteRepository;
    private ProviderListingRepository listingRepository;
    private ListingFavoritesService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        favoriteRepository = mock(ListingFavoriteRepository.class);
        listingRepository = mock(ProviderListingRepository.class);
        service = new ListingFavoritesService(favoriteRepository, listingRepository);
    }

    private ProviderListing listing() {
        ProviderListing listing = ProviderListing.create(
                UUID.randomUUID(), "شقة قدسيا", "وصف", "stay", 24000L, "SAR");
        listing.activate();
        return listing;
    }

    // -- the save gate order ------------------------------------------------

    @Test
    void save_unknownListing_is404BeforeAnyWrite() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(userId, listingId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void save_alreadySavedLivePair_is409() {
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing()));
        when(favoriteRepository.existsByUserIdAndListingId(userId, listingId)).thenReturn(true);

        assertThatThrownBy(() -> service.save(userId, listingId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already saved");
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void save_newPair_persistsAndEchoesTheListingTruth() {
        ProviderListing listing = listing();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(favoriteRepository.existsByUserIdAndListingId(userId, listingId)).thenReturn(false);
        when(favoriteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ListingFavoritesService.ListingFavoriteView view = service.save(userId, listingId);

        assertThat(view.listingId()).isEqualTo(listingId);
        assertThat(view.title()).isEqualTo("شقة قدسيا");
        assertThat(view.priceCents()).isEqualTo(24000L);
        assertThat(view.status()).isEqualTo("ACTIVE");
    }

    // -- the withdraw --------------------------------------------------------

    @Test
    void unsave_livePair_softDeletesThroughTheRepository() {
        ListingFavorite favorite = ListingFavorite.save(userId, listingId);
        when(favoriteRepository.findByUserIdAndListingId(userId, listingId))
                .thenReturn(Optional.of(favorite));

        service.unsave(userId, listingId);

        verify(favoriteRepository).delete(favorite);
    }

    @Test
    void unsave_noLivePair_isTheHonest404() {
        when(favoriteRepository.findByUserIdAndListingId(userId, listingId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unsave(userId, listingId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(favoriteRepository, never()).delete(any(ListingFavorite.class));
    }

    // -- the list and the flag ------------------------------------------------

    @Test
    void myFavorites_composesTheListingsCurrentStatusInOneBatchRead() {
        ListingFavorite favorite = ListingFavorite.save(userId, listingId);
        when(favoriteRepository.findByUserId(userId, PageRequest.of(0, 20,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")))))
                .thenReturn(new PageImpl<>(List.of(favorite)));
        // The saved listing has since been PAUSED by its provider — the
        // relation stays (b-5), the view carries the CURRENT truth. The
        // fixture's id must BE the favorite's listing id (the batch map
        // keys on it — the factory generates a random one, so the stamp
        // mirrors what a persisted row carries).
        ProviderListing paused = listing();
        stampId(paused, listingId);
        paused.pause();
        when(listingRepository.findAllById(List.of(listingId))).thenReturn(List.of(paused));

        var page = service.myFavorites(userId, PageRequest.of(0, 20,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).status()).isEqualTo("PAUSED");
        assertThat(page.getContent().get(0).listingId()).isEqualTo(listingId);
    }

    private static void stampId(ProviderListing listing, UUID id) {
        try {
            java.lang.reflect.Field idField = ProviderListing.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(listing, id);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException("fixture stamp failed", impossible);
        }
    }

    @Test
    void isFavorite_readsTheLivePairOnly() {
        when(favoriteRepository.existsByUserIdAndListingId(userId, listingId)).thenReturn(true);

        assertThat(service.isFavorite(userId, listingId)).isTrue();
    }
}
