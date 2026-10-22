package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeValueMutationResult;
import com.marketplace.catalog.spi.CategoryMutationError;
import com.marketplace.catalog.spi.CategoryMutationErrorCode;
import com.marketplace.catalog.spi.CategoryMutationResult;
import com.marketplace.catalog.spi.CategoryView;
import com.marketplace.catalog.spi.TranslationAmendCommand;
import com.marketplace.catalog.spi.TranslationWriteSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code categoryTranslate}, {@code attributeTranslate} and
 * {@code attributeValueTranslate} mutation roots, the agent-2
 * follow-on packet): the owning translation use case's unit proof —
 * the JUnit 5 + Mockito style the module's own
 * {@code CategoryWriteServiceTest} established (no Spring context:
 * the {@code @PreAuthorize} proxy is an integration concern proven by
 * the app module's real-stack tests; the business decisions the
 * boundary owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link TranslationWriteService} reachable through its typed seam —
 * the NOT_FOUND absences, the shared bilingual ladder (the unknown
 * language dimension, the blank name, the oversized name), the
 * null-name no-op that answers the current projection without a
 * write, the AR/EN dimension addressing over each registry's bilingual
 * columns, and the amendment's identity preservation (the code, the
 * owning-attribute reference and the position never move).</p>
 */
class TranslationWriteServiceTest {

    private CategoryRepository categoryRepository;
    private CatalogAttributeDefinitionRepository attributeDefinitionRepository;
    private CatalogAttributeOptionRepository attributeOptionRepository;
    private TranslationWriteSpi service;
    private final Authentication caller = new TestingAuthenticationToken("admin-subject", "n/a", "ROLE_ADMIN");

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        attributeDefinitionRepository = mock(CatalogAttributeDefinitionRepository.class);
        attributeOptionRepository = mock(CatalogAttributeOptionRepository.class);
        service = new TranslationWriteService(categoryRepository, attributeDefinitionRepository,
                attributeOptionRepository);
    }

    // ------------------------------------------------------------------
    // translateCategory — the ladder and the AR/EN dimension addressing
    // ------------------------------------------------------------------

    @Test
    void anUnknownCategoryAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(categoryRepository.findById(unknown)).thenReturn(Optional.empty());

        CategoryMutationResult result = service.translateCategory(
                unknown, new TranslationAmendCommand("ترجمة", "AR"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void anUnknownLanguageDimensionAnswersTheInvalidDomainError() {
        Category row = Category.create("widgets", "Widgets", null, 1);
        when(categoryRepository.findById(row.getId())).thenReturn(Optional.of(row));

        CategoryMutationResult result = service.translateCategory(
                row.getId(), new TranslationAmendCommand("اسم", "FR"));

        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.INVALID, "languageCode");
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void aBlankNameAnswersTheInvalidDomainErrorNeverTheNamelessRow() {
        Category row = Category.create("widgets", "Widgets", "ودجات", 1);
        when(categoryRepository.findById(row.getId())).thenReturn(Optional.of(row));

        CategoryMutationResult result = service.translateCategory(
                row.getId(), new TranslationAmendCommand("   ", "AR"));

        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.INVALID, "name");
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void anOversizedNameAnswersTheInvalidDomainError() {
        Category row = Category.create("widgets", "Widgets", null, 1);
        when(categoryRepository.findById(row.getId())).thenReturn(Optional.of(row));

        CategoryMutationResult result = service.translateCategory(
                row.getId(), new TranslationAmendCommand("ا".repeat(101), "AR"));

        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.INVALID, "name");
    }

    @Test
    void aNullNameAnswersTheCurrentProjectionWithoutAWrite() {
        Category row = Category.create("widgets", "Widgets", "ودجات", 1);
        when(categoryRepository.findById(row.getId())).thenReturn(Optional.of(row));

        CategoryMutationResult result = service.translateCategory(
                row.getId(), new TranslationAmendCommand(null, "AR"));

        assertThat(result.success()).isTrue();
        assertThat(result.category().name()).isEqualTo("Widgets");
        assertThat(result.category().nameAr()).isEqualTo("ودجات");
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void theArabicDimensionAmendmentMovesOnlyTheArColumn() {
        Category row = Category.create("widgets", "Widgets", null, 3);
        when(categoryRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(categoryRepository.save(any(Category.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CategoryMutationResult result = service.translateCategory(
                row.getId(), new TranslationAmendCommand("الأدوات", "AR"));

        assertThat(result.success()).isTrue();
        verify(categoryRepository).save(org.mockito.ArgumentMatchers.argThat((Category amended) ->
                "الأدوات".equals(amended.getNameAr())
                        && "Widgets".equals(amended.getNameEn())
                        && "widgets".equals(amended.getCode())
                        && amended.getPosition() == 3));
        CategoryView view = result.category();
        assertThat(view.nameAr()).isEqualTo("الأدوات");
        assertThat(view.name()).isEqualTo("Widgets");
    }

    @Test
    void theEnglishDimensionAmendmentMovesOnlyTheEnColumn() {
        Category row = Category.create("widgets", "Widgets", "ودجات", 1);
        when(categoryRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(categoryRepository.save(any(Category.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CategoryMutationResult result = service.translateCategory(
                row.getId(), new TranslationAmendCommand("Widgetry", "EN"));

        assertThat(result.success()).isTrue();
        verify(categoryRepository).save(org.mockito.ArgumentMatchers.argThat((Category amended) ->
                "Widgetry".equals(amended.getNameEn())
                        && "ودجات".equals(amended.getNameAr())));
        assertThat(result.category().name()).isEqualTo("Widgetry");
        assertThat(result.category().nameAr()).isEqualTo("ودجات");
    }

    // ------------------------------------------------------------------
    // translateAttribute — the same ladder over the typed registry
    // ------------------------------------------------------------------

    @Test
    void anUnknownAttributeAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(attributeDefinitionRepository.findById(unknown)).thenReturn(Optional.empty());

        var result = service.translateAttribute(unknown, new TranslationAmendCommand("ترجمة", "AR"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void theArabicDimensionAmendmentMovesOnlyTheDefinitionArColumn() {
        CatalogAttributeDefinition row = CatalogAttributeDefinition.create(
                "color", "Color", null, CatalogAttributeType.CHOICE, null);
        when(attributeDefinitionRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(attributeDefinitionRepository.save(any(CatalogAttributeDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.translateAttribute(row.getId(),
                new TranslationAmendCommand("اللون", "AR"));

        assertThat(result.success()).isTrue();
        assertThat(result.attribute().name()).isEqualTo("Color");
        verify(attributeDefinitionRepository).save(
                org.mockito.ArgumentMatchers.argThat((CatalogAttributeDefinition amended) ->
                        "اللون".equals(amended.getNameAr())
                                && "Color".equals(amended.getNameEn())
                                && "color".equals(amended.getCode())));
    }

    @Test
    void theEnglishDimensionAmendmentMovesOnlyTheDefinitionEnColumn() {
        CatalogAttributeDefinition row = CatalogAttributeDefinition.create(
                "color", "Color", "اللون", CatalogAttributeType.TEXT, "the hue");
        when(attributeDefinitionRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(attributeDefinitionRepository.save(any(CatalogAttributeDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.translateAttribute(row.getId(),
                new TranslationAmendCommand("Colour", "EN"));

        assertThat(result.success()).isTrue();
        verify(attributeDefinitionRepository).save(
                org.mockito.ArgumentMatchers.argThat((CatalogAttributeDefinition amended) ->
                        "Colour".equals(amended.getNameEn())
                                && "اللون".equals(amended.getNameAr())
                                && "the hue".equals(amended.getDescription())));
    }

    // ------------------------------------------------------------------
    // translateAttributeValue — the same ladder over the option registry
    // ------------------------------------------------------------------

    @Test
    void anUnknownValueAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(attributeOptionRepository.findById(unknown)).thenReturn(Optional.empty());

        AttributeValueMutationResult result = service.translateAttributeValue(
                unknown, new TranslationAmendCommand("ترجمة", "AR"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void theArabicDimensionAmendmentMovesOnlyTheOptionArColumnAndKeepsTheDualProjection() {
        CatalogAttributeDefinition parent = CatalogAttributeDefinition.create(
                "color", "Color", null, CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption row = CatalogAttributeOption.create(
                parent.getId(), "red", "Red", null, 1);
        when(attributeOptionRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(attributeDefinitionRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(attributeOptionRepository.save(any(CatalogAttributeOption.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AttributeValueMutationResult result = service.translateAttributeValue(
                row.getId(), new TranslationAmendCommand("أحمر", "AR"));

        assertThat(result.success()).isTrue();
        assertThat(result.value().name()).isEqualTo("Red");
        assertThat(result.value().slug()).isEqualTo("red");
        assertThat(result.attribute().name()).isEqualTo("Color");
        verify(attributeOptionRepository).save(
                org.mockito.ArgumentMatchers.argThat((CatalogAttributeOption amended) ->
                        "أحمر".equals(amended.getNameAr())
                                && "Red".equals(amended.getNameEn())
                                && "red".equals(amended.getCode())
                                && amended.getPosition() == 1));
    }

    @Test
    void theEnglishDimensionAmendmentMovesOnlyTheOptionEnColumn() {
        CatalogAttributeDefinition parent = CatalogAttributeDefinition.create(
                "color", "Color", null, CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption row = CatalogAttributeOption.create(
                parent.getId(), "red", "Red", "أحمر", 2);
        when(attributeOptionRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(attributeDefinitionRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(attributeOptionRepository.save(any(CatalogAttributeOption.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AttributeValueMutationResult result = service.translateAttributeValue(
                row.getId(), new TranslationAmendCommand("Crimson", "EN"));

        assertThat(result.success()).isTrue();
        verify(attributeOptionRepository).save(
                org.mockito.ArgumentMatchers.argThat((CatalogAttributeOption amended) ->
                        "Crimson".equals(amended.getNameEn())
                                && "أحمر".equals(amended.getNameAr())));
    }

    @Test
    void aBlankValueNameAnswersTheInvalidDomainError() {
        CatalogAttributeOption row = CatalogAttributeOption.create(
                UUID.randomUUID(), "red", "Red", "أحمر", 1);
        when(attributeOptionRepository.findById(row.getId())).thenReturn(Optional.of(row));

        AttributeValueMutationResult result = service.translateAttributeValue(
                row.getId(), new TranslationAmendCommand("  ", "EN"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(AttributeMutationError::code, AttributeMutationError::field)
                .containsExactly(AttributeMutationErrorCode.INVALID, "name");
        verify(attributeOptionRepository, never()).save(any(CatalogAttributeOption.class));
    }
}
