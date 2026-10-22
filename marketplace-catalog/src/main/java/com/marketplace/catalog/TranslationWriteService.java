package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeMutationResult;
import com.marketplace.catalog.spi.AttributeValueMutationResult;
import com.marketplace.catalog.spi.AttributeValueView;
import com.marketplace.catalog.spi.AttributeView;
import com.marketplace.catalog.spi.CategoryMutationError;
import com.marketplace.catalog.spi.CategoryMutationErrorCode;
import com.marketplace.catalog.spi.CategoryMutationResult;
import com.marketplace.catalog.spi.CategoryView;
import com.marketplace.catalog.spi.TranslationAmendCommand;
import com.marketplace.catalog.spi.TranslationWriteSpi;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code categoryTranslate}, {@code attributeTranslate} and
 * {@code attributeValueTranslate} mutation roots, the agent-2
 * follow-on packet): the owning use case for the catalog registries'
 * bilingual translation write surface. These are Saleor
 * MANAGE_TRANSLATIONS roots ("Requires one of the following
 * permissions: MANAGE_TRANSLATIONS") mapped through the platform's
 * ADMIN capability and enforced HERE at the owning use-case boundary
 * by Spring Security's method security — never only in a resolver.
 *
 * <p><b>The platform's own two-language law is the honest bounded
 * mapping</b> of Saleor's per-locale translation rows: the bilingual
 * columns ({@code name_en}/{@code name_ar}) ARE the platform's
 * translation storage — {@code AR} addresses the {@code name_ar}
 * dimension, {@code EN} the {@code name_en} dimension. The service
 * re-checks the registries' own contracts BEFORE the write (the
 * addressed language dimension's own validity, the 100-character
 * column ceilings, the blank-name refusal that keeps the
 * at-least-one-name law standing); domain outcomes answer as the
 * owning family's result error entries (the Saleor payload-error
 * ADAPT); transport concerns never reach this service.</p>
 *
 * <p><b>A null name preserves the column</b> (the Saleor input's
 * omit-to-preserve semantics — the no-op answers the current
 * projection without a write); a blank name answers the INVALID
 * domain error (a blank display name is never a valid translation —
 * the registries' own at-least-one-name law stays standing). The
 * amendment rides the owning entity's own translate/update/revise
 * seams (the entity's own validation laws ride inside them — the
 * backstop over the service's own precheck); the row's stable
 * identity facts (the code, the owning-attribute reference, the
 * position) are never moved by a translation.</p>
 */
@Service
public class TranslationWriteService implements TranslationWriteSpi {

    /** The registries' own column ceiling (the bilingual rows' own length contracts). */
    private static final int NAME_MAX_LENGTH = 100;

    private final CategoryRepository categoryRepository;
    private final CatalogAttributeDefinitionRepository attributeDefinitionRepository;
    private final CatalogAttributeOptionRepository attributeOptionRepository;

    public TranslationWriteService(CategoryRepository categoryRepository,
                                   CatalogAttributeDefinitionRepository attributeDefinitionRepository,
                                   CatalogAttributeOptionRepository attributeOptionRepository) {
        this.categoryRepository = categoryRepository;
        this.attributeDefinitionRepository = attributeDefinitionRepository;
        this.attributeOptionRepository = attributeOptionRepository;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CategoryMutationResult translateCategory(UUID id, TranslationAmendCommand command) {
        Optional<Category> addressed = id == null ? Optional.empty() : categoryRepository.findById(id);
        if (addressed.isEmpty()) {
            return CategoryMutationResult.failure(List.of(new CategoryMutationError(
                    "id", CategoryMutationErrorCode.NOT_FOUND, "No category found for the id")));
        }
        List<CategoryMutationError> errors = ladder(command,
                (field, message) -> new CategoryMutationError(field,
                        CategoryMutationErrorCode.INVALID, message));
        if (!errors.isEmpty()) {
            return CategoryMutationResult.failure(errors);
        }
        Category row = addressed.get();
        if (command.name() == null) {
            return CategoryMutationResult.success(toView(row));
        }
        row.translate(mergedEn(command, row.getNameEn()), mergedAr(command, row.getNameAr()));
        return CategoryMutationResult.success(toView(categoryRepository.save(row)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeMutationResult translateAttribute(UUID id, TranslationAmendCommand command) {
        Optional<CatalogAttributeDefinition> addressed =
                id == null ? Optional.empty() : attributeDefinitionRepository.findById(id);
        if (addressed.isEmpty()) {
            return AttributeMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute found for the id")));
        }
        List<AttributeMutationError> errors = ladder(command,
                (field, message) -> new AttributeMutationError(field,
                        AttributeMutationErrorCode.INVALID, message));
        if (!errors.isEmpty()) {
            return AttributeMutationResult.failure(errors);
        }
        CatalogAttributeDefinition row = addressed.get();
        if (command.name() == null) {
            return AttributeMutationResult.success(toView(row));
        }
        // The definition's own display-metadata amendment seam (the
        // entity's own at-least-one-name validation rides inside it —
        // the backstop over the service's own precheck).
        row.update(mergedEn(command, row.getNameEn()), mergedAr(command, row.getNameAr()),
                row.getDescription());
        return AttributeMutationResult.success(toView(attributeDefinitionRepository.save(row)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeValueMutationResult translateAttributeValue(UUID id, TranslationAmendCommand command) {
        Optional<CatalogAttributeOption> addressed =
                id == null ? Optional.empty() : attributeOptionRepository.findById(id);
        if (addressed.isEmpty()) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute value found for the id")));
        }
        List<AttributeMutationError> errors = ladder(command,
                (field, message) -> new AttributeMutationError(field,
                        AttributeMutationErrorCode.INVALID, message));
        if (!errors.isEmpty()) {
            return AttributeValueMutationResult.failure(errors);
        }
        CatalogAttributeOption row = addressed.get();
        CatalogAttributeDefinition parent =
                attributeDefinitionRepository.findById(row.getAttributeDefinitionId()).orElseThrow();
        if (command.name() == null) {
            return AttributeValueMutationResult.success(toView(parent), toValueView(row));
        }
        // The option's own display-metadata amendment seam (the
        // entity's own validateNames law rides inside it — the
        // backstop over the service's own precheck).
        row.revise(mergedEn(command, row.getNameEn()), mergedAr(command, row.getNameAr()));
        CatalogAttributeOption updated = attributeOptionRepository.save(row);
        return AttributeValueMutationResult.success(toView(parent), toValueView(updated));
    }

    // ------------------------------------------------------------------
    // the shared bilingual ladder — the three registries' own error
    // records are mapped through the shared (field, message) findings
    // (the INVALID member is the only classification the ladder emits)
    // ------------------------------------------------------------------

    /** One ladder finding — the input field and its refusal message. */
    private record LadderFinding(String field, String message) {
    }

    private interface InvalidEntry<E> {
        E apply(String field, String message);
    }

    private static <E> List<E> ladder(TranslationAmendCommand command, InvalidEntry<E> entry) {
        List<E> errors = new ArrayList<>();
        String language = command.languageCode();
        if (!"AR".equals(language) && !"EN".equals(language)) {
            errors.add(entry.apply("languageCode",
                    "A translation addresses the AR or EN dimension (the platform's own two-language law)"));
        }
        String name = command.name() == null ? null : command.name().strip();
        if (name != null && name.isBlank()) {
            errors.add(entry.apply("name",
                    "A translation name is a non-blank value (a blank display name never replaces a standing one)"));
        } else if (name != null && name.length() > NAME_MAX_LENGTH) {
            errors.add(entry.apply("name",
                    "A translation name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        return errors;
    }

    /** The merged EN dimension after the amendment — a null name preserves. */
    private static String mergedEn(TranslationAmendCommand command, String nameEn) {
        return "EN".equals(command.languageCode()) && command.name() != null
                ? command.name().strip() : nameEn;
    }

    /** The merged AR dimension after the amendment — a null name preserves. */
    private static String mergedAr(TranslationAmendCommand command, String nameAr) {
        return "AR".equals(command.languageCode()) && command.name() != null
                ? command.name().strip() : nameAr;
    }

    // ------------------------------------------------------------------
    // the bounded projections — the owning read sides' own rendering
    // rules (one projection, one vocabulary per family)
    // ------------------------------------------------------------------

    private static CategoryView toView(Category category) {
        String name = category.getNameEn() != null ? category.getNameEn() : category.getNameAr();
        return new CategoryView(category.getId(), name, category.getNameAr(),
                category.getCode(), category.getPosition());
    }

    private static AttributeView toView(CatalogAttributeDefinition definition) {
        String name = definition.getNameEn() != null ? definition.getNameEn() : definition.getNameAr();
        return new AttributeView(definition.getId(), name, definition.getCode(),
                definition.getValueType());
    }

    private static AttributeValueView toValueView(CatalogAttributeOption option) {
        String name = option.getNameEn() != null ? option.getNameEn() : option.getNameAr();
        return new AttributeValueView(option.getId(), name, option.getCode());
    }
}
