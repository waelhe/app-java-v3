package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * W3 (yelp-level-plan §5 — G19): the saved-listings surface — the
 * member's own «حفظ لاحقًا» relation on the catalog's listings.
 *
 * <p><b>One gate order (the market board's own discipline):</b> the
 * listing resolves FIRST (the FK's own law — an unknown listing answers
 * the honest 404 before any write), then the relation's own uniqueness
 * answers 409 (a live favorite for the same pair — the partial unique
 * key's read form), and only then the insert. The unsave is the house
 * soft delete: the row stays (the Envers trail keeps every save and
 * withdraw), the reads stop returning it — and a re-save is a FRESH row,
 * never a resurrection.
 *
 * <p><b>The list composes the listing's CURRENT truth</b> (one batch
 * read over the page's listing ids — the grouped-read shape): a saved
 * listing that later expires, pauses or is withdrawn by its provider
 * stays saved (the relation is the member's data, b-5's retention), and
 * the view carries the listing's live status so the member sees it.
 */
@Service
public class ListingFavoritesService {

    private final ListingFavoriteRepository favoriteRepository;
    private final ProviderListingRepository listingRepository;

    public ListingFavoritesService(ListingFavoriteRepository favoriteRepository,
                                   ProviderListingRepository listingRepository) {
        this.favoriteRepository = favoriteRepository;
        this.listingRepository = listingRepository;
    }

    /**
     * Saves a listing — the member's «حفظ لاحقًا». Unknown listing ⇒ 404
     * (before any write); an already-saved live pair ⇒ 409 (the unique
     * key's read form — the second save teaches the contract).
     */
    @Observed(name = "catalog.favorites.save")
    @Transactional
    public ListingFavoriteView save(UUID userId, UUID listingId) {
        listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing", listingId));
        if (favoriteRepository.existsByUserIdAndListingId(userId, listingId)) {
            throw new ConflictException(
                    "Listing already saved — DELETE /api/v1/me/favorites/{listingId} withdraws it");
        }
        ListingFavorite saved = favoriteRepository.save(ListingFavorite.save(userId, listingId));
        return viewOf(saved, listingRepository.findById(listingId).orElse(null));
    }

    /**
     * Withdraws the favorite — the house soft delete. A pair with no live
     * favorite answers the honest 404 (nothing to withdraw); the row stays
     * for the audit trail.
     */
    @Observed(name = "catalog.favorites.unsave")
    @Transactional
    public void unsave(UUID userId, UUID listingId) {
        ListingFavorite favorite = favoriteRepository.findByUserIdAndListingId(userId, listingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Favorite", listingId));
        favoriteRepository.delete(favorite);
    }

    /**
     * The member's own list — the live favorites, newest-saved first (the
     * complete {@code (created_at, id)} key keeps the page boundary
     * stable), each carrying the listing's CURRENT status (one grouped
     * read — never per-row queries).
     */
    @Transactional(readOnly = true)
    public Page<ListingFavoriteView> myFavorites(UUID userId, Pageable pageable) {
        Page<ListingFavorite> page = favoriteRepository.findByUserId(userId, pageable);
        Map<UUID, ProviderListing> listings = batchResolve(page.getContent());
        return page.map(favorite -> viewOf(favorite,
                listings.get(favorite.getListingId())));
    }

    /** The results-surface flag: is this listing in the caller's live favorites? */
    @Transactional(readOnly = true)
    public boolean isFavorite(UUID userId, UUID listingId) {
        return favoriteRepository.existsByUserIdAndListingId(userId, listingId);
    }

    private Map<UUID, ProviderListing> batchResolve(List<ListingFavorite> favorites) {
        List<UUID> ids = favorites.stream()
                .map(ListingFavorite::getListingId)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        // W3 (the review round's root fix): the read INCLUDES soft-deleted
        // listings — a saved listing its provider later withdrew stays saved
        // (the relation is the member's own data), and the view's contract
        // carries the listing's CURRENT truth. Hibernate's @SoftDelete
        // filter hides those rows from findAllById; the native read is the
        // channel that sees them (the export adapters' own reasoning).
        return listingRepository.findAllByIdIncludingDeleted(ids).stream()
                .collect(Collectors.toMap(ProviderListing::getId, Function.identity()));
    }

    private ListingFavoriteView viewOf(ListingFavorite favorite, ProviderListing listing) {
        // The listing's row is structurally present (the FK + the soft-delete
        // everywhere discipline); a null here would be a defect worth failing
        // on, so the view renders it as the honest unknown-status floor.
        return new ListingFavoriteView(
                favorite.getListingId(),
                favorite.getSavedAt(),
                listing == null ? null : listing.getTitle(),
                listing == null ? null : listing.getPriceCents(),
                listing == null ? null : listing.getCurrency(),
                listing == null ? null : listing.getStatus().name());
    }

    /** One saved row as the member's list renders it: the relation plus the listing's current truth. */
    public record ListingFavoriteView(
            UUID listingId,
            java.time.Instant savedAt,
            String title,
            Long priceCents,
            String currency,
            String status
    ) {
    }
}
