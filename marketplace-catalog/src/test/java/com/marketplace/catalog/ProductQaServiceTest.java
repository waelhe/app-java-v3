package com.marketplace.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-16 (compliance plan C.8 — the M2 store wave): the Q&amp;A pair's and
 * the seller summary's contracts — the gate ladder the plan's own
 * wording demands («اختبارات عقد»): the ask gate (404 before any write,
 * the caller-as-asker rule), the answer ladder (404 unknown question →
 * 403 non-owner seller → 409 the one-answer invariant → the owner's
 * save), the feed's assembly (the bulk read, never a per-question
 * lookup), and the summary's honest aggregation (the zero summary and
 * the closed projections' carrier).
 */
@ExtendWith(MockitoExtension.class)
class ProductQaServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductQuestionRepository questionRepository;
    @Mock
    private ProductAnswerRepository answerRepository;
    @Mock
    private StorefrontProductRepository storefrontRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private Authentication authentication;

    private ProductQaService service;
    private final UUID caller = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private Product product;

    @BeforeEach
    void setUp() {
        service = new ProductQaService(productRepository, questionRepository,
                answerRepository, storefrontRepository, currentUserProvider);
        product = Product.register("home-appliances", "Espresso machine, 2-cup",
                "Pump-driven", 149900L, "SAR", caller);
    }

    // --- The ask contract -------------------------------------------------

    @Test
    void askAnswers404ForUnknownProduct_BeforeAnyWrite() {
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ask(productId, "Does it ship with a filter basket?", authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(productId.toString());
        verify(questionRepository, never()).save(any(ProductQuestion.class));
    }

    @Test
    void askSavesWithTheCallerAsAsker() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(questionRepository.save(any(ProductQuestion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductQuestion question = service.ask(productId, "Does it ship with a filter basket?", authentication);

        assertThat(question.getProductId()).isEqualTo(productId);
        assertThat(question.getAskerId()).as("the asker IS the caller").isEqualTo(caller);
        assertThat(question.getBody()).isEqualTo("Does it ship with a filter basket?");
    }

    // --- The answer ladder (404 → 403 → 409 → save) -----------------------

    @Test
    void answerAnswers404ForUnknownQuestion() {
        UUID questionId = UUID.randomUUID();
        when(questionRepository.findById(questionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.answer(questionId, "Yes.", authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void answerAnswers403WhenTheCallerIsNotTheOwningSeller() {
        ProductQuestion question = ProductQuestion.ask(productId, UUID.randomUUID(), "Warranty?");
        when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication))
                .thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.answer(question.getId(), "Yes.", authentication))
                .as("the question is public — existence is not hidden, only the act is denied")
                .isInstanceOf(AccessDeniedException.class);
        verify(answerRepository, never()).save(any(ProductAnswer.class));
    }

    @Test
    void answerAnswers409WhenTheQuestionAlreadyHoldsItsAnswer() {
        ProductQuestion question = ProductQuestion.ask(productId, UUID.randomUUID(), "Warranty?");
        when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(answerRepository.existsByQuestionId(question.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.answer(question.getId(), "Yes.", authentication))
                .as("one LIVE answer per question — the schema's partial unique held as the gate first")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(question.getId().toString());
        verify(answerRepository, never()).save(any(ProductAnswer.class));
    }

    @Test
    void answerSavesForTheOwningSeller_WithTheCallerAsProvider() {
        ProductQuestion question = ProductQuestion.ask(productId, UUID.randomUUID(), "Warranty?");
        when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(caller);
        when(answerRepository.existsByQuestionId(question.getId())).thenReturn(false);
        when(answerRepository.save(any(ProductAnswer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductAnswer answer = service.answer(question.getId(),
                "Yes — 12 months, serviceable in-store.", authentication);

        assertThat(answer.getQuestionId()).isEqualTo(question.getId());
        assertThat(answer.getProviderId()).as("the answering seller IS the caller").isEqualTo(caller);
    }

    // --- The feed's assembly contract --------------------------------------

    @Test
    void feedAssemblesEachQuestionWithItsAnswer_ThroughOneBulkRead() {
        ProductQuestion answered = ProductQuestion.ask(productId, UUID.randomUUID(), "Warranty?");
        ProductQuestion unanswered = ProductQuestion.ask(productId, UUID.randomUUID(), "Colors?");
        ProductAnswer official = ProductAnswer.publish(answered.getId(), caller, "Yes — 12 months.");
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        when(questionRepository.findByProductIdOrderByCreatedAtDescIdDesc(productId, pageable))
                .thenReturn(new PageImpl<>(List.of(answered, unanswered), pageable, 2));
        when(answerRepository.findByQuestionIdIn(List.of(answered.getId(), unanswered.getId())))
                .thenReturn(List.of(official));

        var page = service.getQuestions(productId, pageable);

        assertThat(page.getTotalElements()).isEqualTo(2);
        var byId = page.getContent().stream()
                .collect(Collectors.toMap(ProductQaService.ProductQuestionWithAnswer::questionId,
                        Function.identity()));
        assertThat(byId.get(answered.getId()).answer().body()).isEqualTo("Yes — 12 months.");
        assertThat(byId.get(unanswered.getId()).answer()).as("an unanswered question carries null, not a placeholder").isNull();
        // The bulk read carries the assembly — never a per-question lookup.
        verify(answerRepository).findByQuestionIdIn(anyCollection());
        verify(answerRepository, never()).findByQuestionId(any(UUID.class));
    }

    // --- The seller summary's honest aggregation ---------------------------

    @Test
    void summaryAnswersTheHonestZeroForASellerWithNoLiveProducts() {
        when(storefrontRepository.summarizeSeller(caller)).thenReturn(Optional.empty());

        ProductQaService.SellerSummary summary = service.getSellerSummary(caller);

        assertThat(summary.sellerId()).isEqualTo(caller);
        assertThat(summary.productCount()).isZero();
        assertThat(summary.lastActivityAt()).isNull();
        assertThat(summary.currencyBands()).isEmpty();
        assertThat(summary.categoryCounts()).isEmpty();
    }

    @Test
    void summaryCarriesTheClosedProjectionsAssembled() {
        when(storefrontRepository.summarizeSeller(caller)).thenReturn(Optional.of(new SellerSummaryView() {
            @Override
            public UUID getSellerId() {
                return caller;
            }

            @Override
            public long getProductCount() {
                return 7;
            }

            @Override
            public Instant getLastActivityAt() {
                return Instant.parse("2026-10-10T00:00:00Z");
            }
        }));
        when(storefrontRepository.sellerCurrencyBands(caller)).thenReturn(List.of(
                band("SAR", 5, 1000, 900000),
                band("USD", 2, 250, 1800)));
        when(storefrontRepository.sellerCategoryCounts(caller)).thenReturn(List.of(
                categoryCount("home-appliances", 4),
                categoryCount("kitchen", 3)));

        ProductQaService.SellerSummary summary = service.getSellerSummary(caller);

        assertThat(summary.productCount()).isEqualTo(7);
        assertThat(summary.lastActivityAt()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
        assertThat(summary.currencyBands())
                .as("a range is only honest within one currency — two currencies, two bands")
                .hasSize(2);
        assertThat(summary.currencyBands().get(0).getCurrency()).isEqualTo("SAR");
        assertThat(summary.currencyBands().get(0).getMinPriceMinor()).isEqualTo(1000);
        assertThat(summary.currencyBands().get(1).getCurrency()).isEqualTo("USD");
        assertThat(summary.categoryCounts())
                .extracting(SellerCategoryCount::getCategoryCode, SellerCategoryCount::getProductCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("home-appliances", 4L),
                        org.assertj.core.groups.Tuple.tuple("kitchen", 3L));
    }

    private SellerCurrencyBand band(String currency, long count, long min, long max) {
        return new SellerCurrencyBand() {
            @Override
            public String getCurrency() {
                return currency;
            }

            @Override
            public long getProductCount() {
                return count;
            }

            @Override
            public long getMinPriceMinor() {
                return min;
            }

            @Override
            public long getMaxPriceMinor() {
                return max;
            }
        };
    }

    private SellerCategoryCount categoryCount(String code, long count) {
        return new SellerCategoryCount() {
            @Override
            public String getCategoryCode() {
                return code;
            }

            @Override
            public long getProductCount() {
                return count;
            }
        };
    }
}
