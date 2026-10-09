package com.marketplace.ai;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Component
public final class MarketplaceSearchTools {

    private static final int TOOL_PAGE_SIZE = 5;
    private static final int MAX_QUERY_CODE_POINTS = 200;
    private static final int MAX_LOCATION_CODE_POINTS = 100;
    private static final int MAX_LOCATION_OPTIONS = 10;

    private final MarketplaceSearchPort marketplaceSearchPort;
    private final GeoLookupPort geoLookupPort;

    public MarketplaceSearchTools(
            MarketplaceSearchPort marketplaceSearchPort,
            GeoLookupPort geoLookupPort) {
        this.marketplaceSearchPort = Objects.requireNonNull(
                marketplaceSearchPort, "marketplaceSearchPort must not be null");
        this.geoLookupPort = Objects.requireNonNull(
                geoLookupPort, "geoLookupPort must not be null");
    }

    @Tool(
            name = "search_marketplace_listings",
            description = """
                    Search public marketplace listings by text and explicit filters.
                    Use only criteria stated or clearly confirmed by the user; never invent
                    prices, guests, dates, coordinates, rooms, rating or property type.
                    For a named city/neighborhood, pass its name in locationName. If the
                    location cannot be resolved to one exact geographic node, this tool
                    returns locationOptions and clarification and performs NO listing search;
                    ask the user to choose or clarify, then call again. Do not omit an
                    unresolved requested location and silently search everywhere. For
                    distance search, supply latitude, longitude and radiusKm together ONLY
                    when the user explicitly gave coordinates and a radius. Dates require
                    ISO-8601 instants with explicit offset; do not infer a timezone or clock
                    time from date-only input.
                    """)
    public MarketplaceSearchResult searchListingsAdvanced(
            @ToolParam(description = "Free-text search query; leave blank for filter/browse searches.", required = false)
            String query,
            @ToolParam(description = "Exact marketplace category when explicitly known.", required = false)
            String category,
            @ToolParam(description = "Minimum price in the listing currency, when explicitly requested.", required = false)
            BigDecimal minPrice,
            @ToolParam(description = "Maximum price in the listing currency, when explicitly requested.", required = false)
            BigDecimal maxPrice,
            @ToolParam(description = "Minimum guest capacity, when explicitly requested.", required = false)
            Integer guests,
            @ToolParam(description = "Exact city or neighborhood name explicitly requested by the user; do not provide an inferred location.", required = false)
            String locationName,
            @ToolParam(description = "Real-estate transaction purpose: RENT or SALE, only when explicit.", required = false)
            PropertyPurpose purpose,
            @ToolParam(description = "Real-estate property kind: APARTMENT, VILLA, LAND, SHOP, OFFICE or GARAGE, only when explicit.", required = false)
            PropertyType propertyType,
            @ToolParam(description = "Minimum number of rooms, only when explicit.", required = false)
            Integer minRooms,
            @ToolParam(description = "Minimum number of bathrooms, only when explicit.", required = false)
            Integer minBathrooms,
            @ToolParam(description = "Minimum area in square meters, only when explicit.", required = false)
            Integer minAreaM2,
            @ToolParam(description = "Check-in instant in ISO-8601 with an explicit timezone/offset; only when an exact instant is known.", required = false)
            Instant checkIn,
            @ToolParam(description = "Check-out instant in ISO-8601 with an explicit timezone/offset; only when an exact instant is known.", required = false)
            Instant checkOut,
            @ToolParam(description = "Latitude explicitly supplied by the user; do not infer coordinates from a place name.", required = false)
            BigDecimal latitude,
            @ToolParam(description = "Longitude explicitly supplied by the user; do not infer coordinates from a place name.", required = false)
            BigDecimal longitude,
            @ToolParam(description = "Radius in kilometers; provide only with explicitly supplied latitude and longitude.", required = false)
            BigDecimal radiusKm,
            @ToolParam(description = "Minimum provider rating from 1 to 5, only when explicitly requested.", required = false)
            BigDecimal minRating,
            ToolContext toolContext) {

        requireUserContext(toolContext);

        String normalizedQuery = normalizeOptional(query);
        if (normalizedQuery != null
                && normalizedQuery.codePointCount(0, normalizedQuery.length()) > MAX_QUERY_CODE_POINTS) {
            throw new BadRequestException(
                    "q must not exceed " + MAX_QUERY_CODE_POINTS + " Unicode code points");
        }
        String normalizedCategory = normalizeOptional(category);

        LocationResolution location = resolveLocation(locationName);
        if (location.requested() && location.locationId() == null) {
            // Fail closed: an explicit but unresolved location must never be
            // silently dropped, which would broaden the search to other places.
            return new MarketplaceSearchResult(
                    List.of(), 0L, location.clarification(), location.options());
        }

        SearchCriteria criteria = new SearchCriteria(
                normalizedQuery,
                normalizedCategory,
                minPrice,
                maxPrice,
                checkIn,
                checkOut,
                guests,
                location.locationId(),
                purpose,
                propertyType,
                minRooms,
                minBathrooms,
                minAreaM2,
                latitude,
                longitude,
                radiusKm,
                minRating);

        PagedRequest request = PagedRequest.of(0, TOOL_PAGE_SIZE);
        // REST and AI now use one orchestration: facet dispatch, availability,
        // geographic qualification, sort validation, stable ordering and cache.
        PagedResponse<ListingSummary> page = marketplaceSearchPort.search(criteria, request);
        return new MarketplaceSearchResult(page.content(), page.totalElements(), null, List.of());
    }

    /**
     * Compatibility helper for simple callers/tests. Spring AI registers only
     * the annotated advanced method as the public tool schema.
     */
    public MarketplaceSearchResult searchListings(
            String query,
            String category,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Integer guests,
            ToolContext toolContext) {
        return searchListingsAdvanced(
                query, category, minPrice, maxPrice, guests,
                null, null, null, null, null, null,
                null, null, null, null, null, null, toolContext);
    }

    private LocationResolution resolveLocation(String locationName) {
        String normalized = normalizeOptional(locationName);
        if (normalized == null) {
            return new LocationResolution(false, null, null, List.of());
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_LOCATION_CODE_POINTS) {
            throw new BadRequestException(
                    "locationName must not exceed " + MAX_LOCATION_CODE_POINTS + " Unicode code points");
        }
        String prefix = normalizeLocation(normalized);
        if (prefix.codePointCount(0, prefix.length()) < 2) {
            throw new BadRequestException("locationName must contain at least 2 characters");
        }

        List<GeoLookupPort.GeoNode> suggestions = geoLookupPort.suggest(prefix);
        List<GeoLookupPort.GeoNode> exact = suggestions.stream()
                .filter(node -> sameLocation(prefix, node.nameAr())
                        || sameLocation(prefix, node.nameEn())
                        || sameLocation(prefix, node.slug()))
                .toList();

        if (exact.size() == 1) {
            return new LocationResolution(true, exact.getFirst().id(), null, List.of());
        }

        List<LocationSuggestion> options = (exact.isEmpty() ? suggestions : exact).stream()
                .limit(MAX_LOCATION_OPTIONS)
                .map(LocationSuggestion::from)
                .toList();
        String clarification = options.isEmpty()
                ? "The requested location could not be resolved. Ask the user for an exact city or neighborhood name; do not search without the location."
                : "The requested location is not unique. Ask the user to choose one of the returned locationOptions before searching.";
        return new LocationResolution(true, null, clarification, options);
    }

    private static boolean sameLocation(String expected, String candidate) {
        return candidate != null && normalizeLocation(candidate).equals(normalizeLocation(expected));
    }

    /**
     * Search-only Unicode normalization, not a persisted or identity form:
     * remove Arabic diacritics/tatweel and normalize common alef/ya variants
     * before invoking the geo prefix port, whose LIKE wildcards are escaped
     * by the geo module itself.
     */
    private static String normalizeLocation(String value) {
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace("\u0640", "")
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ى', 'ي');
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void requireUserContext(ToolContext toolContext) {
        // Match Spring AI's MethodToolCallback argument-validation contract:
        // missing context is an IllegalArgumentException, not an invented user.
        Assert.notNull(toolContext, "toolContext must not be null");
        Object userId = toolContext.getContext().get("userId");
        if (userId == null || userId.toString().isBlank()) {
            throw new IllegalArgumentException("toolContext userId must be present");
        }
    }

    private record LocationResolution(
            boolean requested,
            UUID locationId,
            String clarification,
            List<LocationSuggestion> options) {
    }

    public record LocationSuggestion(
            UUID id,
            String nameAr,
            String nameEn,
            String slug,
            int level) {

        static LocationSuggestion from(GeoLookupPort.GeoNode node) {
            return new LocationSuggestion(
                    node.id(), node.nameAr(), node.nameEn(), node.slug(), node.level());
        }
    }

    public record MarketplaceSearchResult(
            List<ListingSummary> listings,
            long totalMatches,
            String clarification,
            List<LocationSuggestion> locationOptions) {

        public MarketplaceSearchResult {
            listings = listings == null ? List.of() : List.copyOf(listings);
            locationOptions = locationOptions == null ? List.of() : List.copyOf(locationOptions);
        }
    }
}
