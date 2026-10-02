package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * L50 (the Nextdoor-2026 completeness wave — gap #5, the market board):
 * the neighborhood market surface «سوق الحي والحراج». Every endpoint
 * sits behind the resource-server chain's
 * {@code anyRequest().authenticated()} and the service's
 * active-membership gate (403 — G-N3's default) — no security-config
 * change, the same zero-config line every layer since L20 has ridden.
 *
 * <p><b>The two URL families</b> (the posts/events controllers' own
 * contract shape): the board pair under
 * {@code /neighborhood/market} (the member's own neighborhood — the
 * membership IS the scope, there is no location parameter to read
 * anyone else's board) and the item-scoped withdraw under
 * {@code /neighborhood/market/{itemId}}.
 *
 * <p><b>The one write limiter</b> (the plan's own «حدود معدل مسمّاة
 * محافظة» discipline): {@code marketCreate}, the L29 model — a named
 * Resilience4j instance, fail fast with 429 RL-001, at the
 * postCreate/eventCreate budget (a market item is the board's unit, a
 * full authored record like a post). The withdraw rides no limiter of
 * its own (the postDelete/eventDelete stance verbatim: an author
 * managing their own rows is not an abuse surface worth a budget).
 *
 * <p><b>The type gates</b> (criterion 3, the posts' own discipline):
 * the category and the condition arrive as Strings and parse through
 * {@link #parseCategory(String)} / {@link #parseCondition(String)}
 * BEFORE any service call — an invalid value answers the house 400
 * with the valid vocabulary listed, never an enum-binding 500.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class NeighborhoodMarketController {

    /**
     * The label bounds — the house {@code provider_listings.title}
     * limit (V2's own documented bound): the title and the pickup-spot
     * label are one-line display strings, the post title's own shape.
     */
    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_LABEL_LENGTH = 200;

    private final NeighborhoodMarketItemService marketService;
    private final CurrentUserProvider currentUserProvider;

    public NeighborhoodMarketController(NeighborhoodMarketItemService marketService,
                                        CurrentUserProvider currentUserProvider) {
        this.marketService = marketService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/neighborhood/market")
    @Operation(summary = "Read my neighborhood's market board",
            description = "The caller's OWN neighborhood's offered items, newest first — "
                    + "the membership is the scope (there is no location parameter: one "
                    + "membership, one board — G-N1/G-N3). No active membership answers 403. "
                    + "The optional filter axes are the product's own: category (the five "
                    + "chips), q (the search box — a case-insensitive substring over the "
                    + "title and the pickup-spot label), and mine (the member's own-items "
                    + "view). An invalid category answers 400 before any read. Every row "
                    + "carries the two caller-scoped facts: sellerVerified (the author's "
                    + "earned membership state, one grouped read over the page) and mine "
                    + "(the caller's own authorship — the withdraw button's honest gate). "
                    + "Deterministic pagination on the complete sort key (createdAt DESC, "
                    + "id DESC) — no shaky page boundaries.")
    public ResponseEntity<PagedResponse<NeighborhoodMarketItemView>> board(
            @Parameter(description = "Optional category filter — FREE, FURNITURE, ELECTRONICS, TOOLS or OTHER")
            @RequestParam(required = false) String category,
            @Parameter(description = "Optional search text — a case-insensitive substring over the title and the pickup-spot label")
            @RequestParam(required = false) String q,
            @Parameter(description = "Optional my-items view — true narrows the board to the caller's own items")
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            Pageable pageable,
            Authentication authentication) {
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                marketService.getBoard(callerId, parseCategory(category), q, mine, pageable)));
    }

    @PostMapping("/neighborhood/market")
    @RateLimiter(name = "marketCreate")
    @Operation(summary = "Publish an item to my neighborhood's market",
            description = "Writes an item into the caller's active neighborhood. The gate "
                    + "order (before any write): the location resolves through the geo port "
                    + "(404 unknown), must be a level-3 neighborhood node (400 otherwise), "
                    + "and the caller must hold an active membership in exactly that location "
                    + "(403 otherwise — a REJECTED verification cannot publish). The item's "
                    + "own ONE pricing rule follows (400 — «مجاني ⇔ بلا سعر»: a FREE item "
                    + "carries no price at all, the four sale categories carry a strictly "
                    + "positive integer-cents amount in a 3-letter ISO 4217 code). Title and "
                    + "locationLabel are bounded at 200 characters.")
    public ResponseEntity<NeighborhoodMarketItemView> create(
            @Valid @RequestBody CreateMarketItemRequest request,
            Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        NeighborhoodMarketItemView view = marketService.createItem(
                authorId,
                request.locationId(),
                parseCategory(request.category()),
                request.title(),
                parseCondition(request.condition()),
                request.priceCents(),
                request.priceCurrency(),
                request.locationLabel());
        return ResponseEntity.status(201).body(view);
    }

    @DeleteMapping("/neighborhood/market/{itemId}")
    @Operation(summary = "Withdraw my market item",
            description = "The author's own soft delete: the row stays (the audit trail "
                    + "keeps every revision — b-5's retention), the reads stop returning "
                    + "it. Only the author — anyone else answers 403; an unknown item "
                    + "answers the honest 404.")
    public ResponseEntity<Void> delete(
            @PathVariable UUID itemId,
            Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        marketService.deleteByAuthor(authorId, itemId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The category type gate (criterion 3): a String in, the enum out —
     * an invalid value answers the house 400 listing the valid
     * vocabulary, BEFORE any service call (and therefore before any
     * read or write).
     */
    private static MarketCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MarketCategory.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid category '" + raw + "' — valid values: FREE, FURNITURE, ELECTRONICS, TOOLS, OTHER");
        }
    }

    /**
     * The condition type gate (the same discipline): a String in, the
     * enum out — an invalid value answers the house 400 listing the
     * design's own two states, BEFORE any service call.
     */
    private static MarketCondition parseCondition(String raw) {
        try {
            return MarketCondition.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid condition '" + raw + "' — valid values: LIKE_NEW, GOOD");
        }
    }

    /**
     * The publish body: the target neighborhood (the seller's own —
     * the service gates the match), the product's own fields, and the
     * ONE price pair (absent for a gift).
     */
    public record CreateMarketItemRequest(
            @NotNull
            @Schema(description = "The geo tree node id of the seller's neighborhood — must be "
                    + "the caller's active membership location (a level-3 node).",
                    example = "11111111-1111-4111-8111-111111111104")
            UUID locationId,

            @NotBlank
            @Schema(description = "The item's category — the product's own five filter chips.",
                    allowableValues = {"FREE", "FURNITURE", "ELECTRONICS", "TOOLS", "OTHER"},
                    example = "FURNITURE")
            String category,

            @NotBlank
            @Size(max = MAX_TITLE_LENGTH)
            @Schema(description = "The item's headline — its whole authored text (max 200 "
                    + "characters; the card carries no separate body).",
                    maxLength = MAX_TITLE_LENGTH,
                    example = "أريكة جلسة عائلية 7 مقاعد — قماش قابل للغسل")
            String title,

            @NotBlank
            @Schema(description = "The item's condition — the design's own two states.",
                    allowableValues = {"LIKE_NEW", "GOOD"},
                    example = "GOOD")
            String condition,

            @Schema(description = "The price in integer cents — REQUIRED and strictly "
                    + "positive for the four sale categories, must be absent for FREE "
                    + "(مجاني ⇔ بلا سعر).",
                    example = "48000")
            Integer priceCents,

            @Schema(description = "The ISO 4217 currency code — present exactly when "
                    + "priceCents is (e.g. SAR). The service is the single validation "
                    + "authority (Currencies.normalize over the JDK's ISO 4217 table — "
                    + "lowercase and surrounding whitespace are normalized, an unknown "
                    + "code answers 400); no bean-side pattern rides ahead of it (the "
                    + "L50 review round's own ISO-authority adoption).",
                    example = "SAR")
            String priceCurrency,

            @NotBlank
            @Size(max = MAX_LABEL_LENGTH)
            @Schema(description = "The pickup spot's display label inside the neighborhood "
                    + "(max 200 characters).", maxLength = MAX_LABEL_LENGTH,
                    example = "قرب جامع النور — مربع 2")
            String locationLabel
    ) {
    }
}
