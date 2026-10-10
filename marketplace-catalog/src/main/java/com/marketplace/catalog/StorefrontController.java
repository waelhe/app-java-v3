package com.marketplace.catalog;

import java.util.UUID;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-16 (compliance plan C.8 — the M2 store wave, «جذر المتجر ٢/٢»): the
 * wave's two surfaces — the public Q&amp;A pair («أسئلة/أجوبة المنتج»)
 * and the seller summary («ملخص البائع العام»). A NEW controller
 * beside the M1 {@code ProductController} (the wave's boundary: ملفات
 * جديدة فقط — the M1 root's files stay untouched, including its
 * owner-private {@code GET /products/{id}} mapping, which stays
 * exactly as A-17 shipped it; the product browsing page is a later
 * wave's surface — C.8's own list is the Q&A and the summary).
 *
 * <p><b>Security shapes (the SecurityConfig lines' own precedent):</b>
 * the two GETs are the precise {@code permitAll} lines (the B-12/B-13
 * mirror — public reads the plan documents as public); the two POSTs
 * fall through to {@code anyRequest().authenticated()}, with the
 * answer's PROVIDER scope + ownership gate living on the service (the
 * house method-security shape).
 *
 * <p><b>Pagination (the frozen contract):</b> the Q&amp;A feed rides the
 * platform's unified Spring Data Web support — {@code Pageable} resolved
 * from the standard request parameters, the {@code max-page-size: 100}
 * platform bound, no new pagination pattern.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/store", version = "1.0")
public class StorefrontController {

    private final ProductQaService productQaService;

    public StorefrontController(ProductQaService productQaService) {
        this.productQaService = productQaService;
    }

    /**
     * The public Q&amp;A feed — one product's questions, newest first on
     * the complete sort key, each carrying the seller's official answer
     * when it exists.
     */
    @GetMapping("/products/{id}/questions")
    @Operation(summary = "Read a product's Q&A (public, paginated)",
            description = "The product's questions newest-first (createdAt DESC, id DESC — "
                    + "the complete sort key keeps page boundaries stable), each assembled "
                    + "with its single official answer in one bulk read (never a "
                    + "per-question lookup). Reached through the product gate first: an "
                    + "unknown or deleted product answers the honest 404 — its questions "
                    + "are absent exactly as the product itself is. The platform's "
                    + "standard pagination parameters apply (max page size 100).")
    public ResponseEntity<com.marketplace.shared.api.PagedResponse<ProductQaService.ProductQuestionWithAnswer>> getQuestions(
            @PathVariable UUID id, Pageable pageable) {
        return ResponseEntity.ok(com.marketplace.shared.api.PagedResponse.of(
                productQaService.getQuestions(id, pageable)));
    }

    /**
     * Asks one question — the member's write path (the public read's
     * complement: anyone browsing may ask the seller).
     */
    @PostMapping("/products/{id}/questions")
    @Operation(summary = "Ask a product question",
            description = "Writes one question on a live product — the asker is the "
                    + "caller. The gate order (before any write): the product must "
                    + "exist in the live set (404 unknown or deleted). The body is "
                    + "bounded at 2000 characters — the house content-body bound; "
                    + "a blank body answers 400 before any write.")
    public ResponseEntity<ProductQaService.ProductQuestionWithAnswer> ask(
            @PathVariable UUID id,
            @Valid @RequestBody AskQuestionRequest request,
            Authentication authentication) {
        ProductQuestion question = productQaService.ask(id, request.body(), authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProductQaService.ProductQuestionWithAnswer.of(question, null));
    }

    /**
     * Publishes the official answer — the owning seller's write path
     * (PROVIDER scope + ownership on the service).
     */
    @PostMapping("/questions/{id}/answers")
    @Operation(summary = "Answer a product question (the owning seller)",
            description = "Publishes the question's single official answer. The gate "
                    + "ladder (the community delete pattern's exact order): 404 when "
                    + "the question is unknown; 403 when the caller is not the "
                    + "product's owning seller (the question is public — existence is "
                    + "not hidden, only the act is denied); 409 when the question "
                    + "already holds a live answer (the schema's partial unique holds "
                    + "the same invariant as defense in depth). The body is bounded "
                    + "at 2000 characters.")
    public ResponseEntity<ProductQaService.ProductQuestionWithAnswer> answer(
            @PathVariable UUID id,
            @Valid @RequestBody AnswerQuestionRequest request,
            Authentication authentication) {
        ProductAnswer answer = productQaService.answer(id, request.body(), authentication);
        ProductQuestion question = productQaService.getQuestion(answer.getQuestionId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProductQaService.ProductQuestionWithAnswer.of(question, answer));
    }

    /**
     * The public seller summary — «ملخص البائع العام»: the three closed
     * projections assembled (scalar + currency bands + category lines).
     */
    @GetMapping("/sellers/{providerId}/summary")
    @Operation(summary = "Read a seller's public summary (closed projections)",
            description = "The seller's storefront summary from the store's own data: "
                    + "the live product count, the last product touch, one price band "
                    + "per currency (a range is only honest within one currency — a "
                    + "two-currency seller gets two bands, never one merged fiction), "
                    + "and the product count per store-category code (the shelf "
                    + "layout). A seller with no live products gets the honest zero "
                    + "summary — this surface reports what the store's data says, "
                    + "never the user's existence (the identity line owns that).")
    public ResponseEntity<ProductQaService.SellerSummary> getSellerSummary(
            @PathVariable UUID providerId) {
        return ResponseEntity.ok(productQaService.getSellerSummary(providerId));
    }

    /** The ask contract — the house content-body bound (2000). */
    public record AskQuestionRequest(
            @Schema(description = "The question's text", example = "Does it come with a warranty?")
            @NotBlank @Size(min = 1, max = 2000) String body
    ) {
    }

    /** The answer contract — the house content-body bound (2000). */
    public record AnswerQuestionRequest(
            @Schema(description = "The answer's text",
                    example = "Yes — a 12-month manufacturer warranty, serviceable in-store.")
            @NotBlank @Size(min = 1, max = 2000) String body
    ) {
    }
}
