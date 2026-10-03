package com.marketplace.catalog;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3 (G19) merge round (2026-10-03): the favorites controller's own
 * module-local unit guards — the {@code CatalogControllerTest} house
 * pattern (plain Mockito, no Spring context; the web wiring stays with
 * the app-side slice). The wave's own branch closed its service-level
 * gate; the merged tree rides the uncovered controller against the
 * module's BUNDLE budget, so the composition contracts pin HERE:
 * <ul>
 *   <li><b>the complete ordering key</b> — the review round's root fix:
 *       the sort is FORCED to (createdAt DESC, id DESC) whatever the
 *       caller's page request carries, while the page number and size
 *       ride through unchanged (the D-N5 total-order law);</li>
 *   <li>save answers 201 with the composed view and the caller is the
 *       scope — the current user resolves through the provider, never a
 *       request parameter;</li>
 *   <li>unsave delegates the soft delete and answers 204.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ListingFavoritesControllerTest {

    @Mock
    private ListingFavoritesService favoritesService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private ListingFavoritesController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();

    @Test
    void myFavorites_forcesTheCompleteOrderingKey_andRidesTheRequestedPage() {
        Pageable requested = PageRequest.of(2, 15,
                Sort.by(Sort.Direction.ASC, "title")); // a caller may send any sort
        Pageable expected = PageRequest.of(2, 15,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        var row = new ListingFavoritesService.ListingFavoriteView(
                listingId, Instant.parse("2026-10-02T10:15:00Z"),
                "Sunny flat", 1_500_000_00L, "SAR", "ACTIVE");
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(favoritesService.myFavorites(userId, expected))
                .thenReturn(new PageImpl<>(List.of(row), expected, 31));

        var response = controller.myFavorites(requested, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalElements()).isEqualTo(31);
        assertThat(response.getBody().getContent().getFirst().listingId()).isEqualTo(listingId);
        assertThat(response.getBody().getContent().getFirst().savedAt())
                .isEqualTo(Instant.parse("2026-10-02T10:15:00Z"));
        verify(favoritesService).myFavorites(userId, expected);
    }

    @Test
    void save_answersCreatedWithTheComposedView_theCallerIsTheScope() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        var saved = new ListingFavoritesService.ListingFavoriteView(
                listingId, Instant.parse("2026-10-02T10:15:00Z"),
                "Sunny flat", 1_500_000_00L, "SAR", "ACTIVE");
        when(favoritesService.save(userId, listingId)).thenReturn(saved);

        var response = controller.save(listingId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(saved);
        verify(favoritesService).save(userId, listingId);
    }

    @Test
    void unsave_delegatesTheSoftDelete_andAnswersNoContent() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);

        var response = controller.unsave(listingId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(favoritesService).unsave(userId, listingId);
    }
}
