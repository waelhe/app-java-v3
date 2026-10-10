package com.marketplace.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B-16 (compliance plan C.8 — the M2 store wave, «جذر المتجر ٢/٢»):
 * the Q&amp;A pair's two writes and the wave's two public reads —
 * the surfaces A-17's javadoc itself deferred here («The public
 * storefront (browsing, search faces, the Q&amp;A and seller-summary
 * surfaces) arrives with the M2 wave (C.8)»).
 *
 * <p><b>The gate ladder (the community delete pattern's exact order):</b>
 * the ask gate is the product's existence-and-liveness (404 — the L31
 * discipline: never a silent accept); the answer gate is
 * question-existence (404) → caller-is-the-product's-owner (403 — the
 * question is public, so existence is not hidden; only the ACT is
 * denied) → one-LIVE-answer (409 — the ConflictException the schema's
 * partial unique holds as its own defense in depth).
 *
 * <p><b>The commands-not-reads observation policy</b> (the
 * {@code ObservationCoverageFilesTest} pin discipline): the two writes
 * are the observed commands ({@code catalog.product.question.ask},
 * {@code catalog.product.answer.publish}); the two public reads stay
 * unobserved — same policy as every house entry, A-17's own line
 * included.
 */
@Service
public class ProductQaService {

    private final ProductRepository productRepository;
    private final ProductQuestionRepository questionRepository;
    private final ProductAnswerRepository answerRepository;
    private final StorefrontProductRepository storefrontRepository;
    private final com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    public ProductQaService(ProductRepository productRepository,
                            ProductQuestionRepository questionRepository,
                            ProductAnswerRepository answerRepository,
                            StorefrontProductRepository storefrontRepository,
                            com.marketplace.shared.security.CurrentUserProvider currentUserProvider) {
        this.productRepository = productRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.storefrontRepository = storefrontRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Asks one question on a live product — the member's M2 write path.
     * The gate: the product must exist in the live set (the honest 404
     * on an unknown or deleted product — its questions are absent
     * exactly as the product itself is, the community feed's posture).
     */
    @Observed(name = "catalog.product.question.ask")
    @Transactional
    public ProductQuestion ask(UUID productId, String body, Authentication authentication) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        UUID askerId = currentUserProvider.getCurrentUserId(authentication);
        return questionRepository.save(ProductQuestion.ask(productId, askerId, body));
    }

    /**
     * Publishes the official answer — the owning seller's M2 write
     * path, PROVIDER-scoped method security with the caller's own id
     * (the {@code CurrentUserProvider} seam — the ownership argument
     * cannot be forged). The ladder: 404 unknown question → 403 the
     * caller is not the product's owner → 409 the question already
     * holds a LIVE answer.
     */
    @Observed(name = "catalog.product.answer.publish")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public ProductAnswer answer(UUID questionId, String body, Authentication authentication) {
        ProductQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductQuestion", questionId));
        Product product = productRepository.findById(question.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", question.getProductId()));
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!product.getProviderId().equals(caller)) {
            // The question is public — existence is not hidden; only the
            // act is denied (the community delete pattern's 403).
            throw new AccessDeniedException("Only the product's owning seller can answer its questions");
        }
        if (answerRepository.existsByQuestionId(questionId)) {
            throw new ConflictException(
                    "ProductQuestion " + questionId + " already holds its official answer");
        }
        return answerRepository.save(ProductAnswer.publish(questionId, caller, body));
    }

    /**
     * The public single-question read — the answer path's response
     * assembly (the created answer rides its question's own view). The
     * honest 404 on an unknown question; a deleted question's answer
     * cannot exist (the one-answer gate and the soft-delete filter hold
     * the pair's liveness together).
     */
    @Transactional(readOnly = true)
    public ProductQuestion getQuestion(UUID questionId) {
        return questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductQuestion", questionId));
    }

    /**
     * The public Q&amp;A feed — one product's questions, newest first on
     * the complete sort key, each assembled with its answer (if any)
     * through the derived {@code In} bulk read: one page query + one
     * answer query, never a per-question lookup.
     */
    @Transactional(readOnly = true)
    public Page<ProductQuestionWithAnswer> getQuestions(UUID productId, Pageable pageable) {
        Page<ProductQuestion> questions =
                questionRepository.findByProductIdOrderByCreatedAtDescIdDesc(productId, pageable);
        if (questions.isEmpty()) {
            // The empty page short-circuits the bulk read — no IN query
            // over an empty key set (the honest empty page, never a
            // fiction query).
            return questions.map(question -> ProductQuestionWithAnswer.of(question, null));
        }
        Map<UUID, ProductAnswer> answersByQuestion = answerRepository
                .findByQuestionIdIn(questions.getContent().stream()
                        .map(ProductQuestion::getId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(ProductAnswer::getQuestionId, Function.identity()));
        return questions.map(question -> ProductQuestionWithAnswer.of(question,
                answersByQuestion.get(question.getId())));
    }

    /**
     * The public seller summary — «ملخص البائع العام»: the three closed
     * projections assembled (scalar + currency bands + category lines).
     * A seller with no live products gets the honest zero summary (the
     * user's EXISTENCE is not catalog's truth to tell — the identity
     * line owns that; this surface only reports what the store's own
     * data says).
     */
    @Transactional(readOnly = true)
    public SellerSummary getSellerSummary(UUID sellerId) {
        Optional<SellerSummaryView> scalar = storefrontRepository.summarizeSeller(sellerId);
        if (scalar.isEmpty()) {
            // The honest zero summary: no live products. The user's
            // existence is the identity line's truth, never this
            // surface's — the store only reports what its own data says.
            return new SellerSummary(sellerId, 0L, null, List.of(), List.of());
        }
        SellerSummaryView view = scalar.get();
        return new SellerSummary(
                view.getSellerId(),
                view.getProductCount(),
                view.getLastActivityAt(),
                storefrontRepository.sellerCurrencyBands(sellerId),
                storefrontRepository.sellerCategoryCounts(sellerId));
    }

    /**
     * The assembled seller summary — the closed projections' carrier.
     */
    public record SellerSummary(
            UUID sellerId,
            long productCount,
            Instant lastActivityAt,
            List<SellerCurrencyBand> currencyBands,
            List<SellerCategoryCount> categoryCounts
    ) {
    }

    /**
     * The public Q&amp;A view — the question with its answer (nullable):
     * the storefront's own shape, data-minimized to what the surface
     * renders (bodies and timestamps; the asker and the answering
     * seller resolve through their own public-profile surfaces).
     */
    public record ProductQuestionWithAnswer(
            UUID questionId,
            UUID productId,
            String body,
            Instant askedAt,
            AnswerView answer
    ) {

        public record AnswerView(
                UUID answerId,
                String body,
                Instant answeredAt
        ) {
        }

        static ProductQuestionWithAnswer of(ProductQuestion question, ProductAnswer answer) {
            return new ProductQuestionWithAnswer(
                    question.getId(),
                    question.getProductId(),
                    question.getBody(),
                    question.getCreatedAt(),
                    answer == null ? null : new AnswerView(
                            answer.getId(), answer.getBody(), answer.getCreatedAt()));
        }
    }
}
