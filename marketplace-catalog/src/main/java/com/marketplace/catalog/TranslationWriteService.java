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
import com.marketplace.catalog.spi.CollectionMutationError;
import com.marketplace.catalog.spi.CollectionMutationErrorCode;
import com.marketplace.catalog.spi.CollectionMutationResult;
import com.marketplace.catalog.spi.CollectionView;
import com.marketplace.catalog.spi.TranslationAmendCommand;
import com.marketplace.catalog.spi.TranslationBulkItem;
import com.marketplace.catalog.spi.TranslationBulkOutcome;
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
    private final CollectionRepository collectionRepository;
    private final CollectionProductRepository collectionProductRepository;
    // The 2026-11-08 #808 round-21 review (Minor · Stability &
    // Availability): constructor injection (not @PersistenceContext) so
    // the unit tests can mock the refresh contract — Spring injects the
    // shared transactional EntityManager either way (the OrdersService
    // confirmFromPayment precedent).
    private final jakarta.persistence.EntityManager entityManager;

    public TranslationWriteService(CategoryRepository categoryRepository,
                                   CatalogAttributeDefinitionRepository attributeDefinitionRepository,
                                   CatalogAttributeOptionRepository attributeOptionRepository,
                                   CollectionRepository collectionRepository,
                                   CollectionProductRepository collectionProductRepository,
                                   jakarta.persistence.EntityManager entityManager) {
        this.categoryRepository = categoryRepository;
        this.attributeDefinitionRepository = attributeDefinitionRepository;
        this.attributeOptionRepository = attributeOptionRepository;
        this.collectionRepository = collectionRepository;
        this.collectionProductRepository = collectionProductRepository;
        this.entityManager = entityManager;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CategoryMutationResult translateCategory(UUID id, TranslationAmendCommand command) {
        // The 2026-11-08 #808 round-21 review (Minor · Data Integrity &
        // Integration): the lifecycle's locked load — the translation
        // serializes with the edit/delete pair on the row itself (the
        // updateCategory/deleteCategory law this round established for
        // the whole category lifecycle).
        Optional<Category> addressed = id == null ? Optional.empty()
                : categoryRepository.findByIdForUpdate(id);
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
        // The 2026-11-08 #808 round-23 review (Minor · Data Integrity &
        // Integration): the locked load — the category/collection twins
        // took the round-21 lock treatment and the attribute twin missed
        // it. An unlocked read let a concurrent deleteAttribute commit
        // between the read and the save, and the translation then wrote
        // over the soft-deleted row and answered a fabricated SUCCESS
        // for an attribute no read could resolve (the updateAttribute
        // round-21 law, this file's own shape).
        Optional<CatalogAttributeDefinition> addressed =
                id == null ? Optional.empty()
                        : attributeDefinitionRepository.findByIdForUpdate(id);
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
        // The 2026-11-08 #808 round-23 review (Minor · Data Integrity &
        // Integration): the option's family lock FIRST — both value
        // deletes (the SPI deleteAttributeValue and the REST
        // removeOption) serialize their writers on the parent
        // definition's FOR-UPDATE row, and the value's own translation
        // never joined that discipline: an unlocked read let a
        // concurrent value delete commit between the read and the save,
        // and the translation wrote over the soft-deleted option (the
        // fabricated SUCCESS) or the stale @Version answered the raw
        // OptimisticLockException. The definition lock plus the fresh
        // existence re-read answer the honest not-found.
        Optional<CatalogAttributeOption> addressed =
                id == null ? Optional.empty() : attributeOptionRepository.findById(id);
        if (addressed.isEmpty()) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute value found for the id")));
        }
        if (attributeDefinitionRepository
                .findByIdForUpdate(addressed.get().getAttributeDefinitionId()).isEmpty()) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute value found for the id")));
        }
        // The fresh re-read under the lock — the option may have died
        // while this call waited (the honest not-found, never a write
        // over a soft-deleted row).
        if (!attributeOptionRepository.existsById(id)) {
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
        // B20 (CAT-08 — the 2026-11-03 adversarial review, review-pr-808):
        // the concurrent parent soft-delete race answers the classified
        // NOT_FOUND — the ProductVariantWriteService precedent's own law
        // (never a raw NoSuchElementException 500 off the wire) — and the
        // orphan revision never persists.
        // The 2026-11-07 #808 round-20 review (Minor · Data Integrity &
        // Integration): the definition row loads FOR UPDATE — the
        // attribute delete holds the SAME row while its children cascade
        // soft-deletes THIS option, and the unlocked read let the delete
        // commit between the check and the save (the amend then answered
        // a fabricated success on a dead row). The option re-reads under
        // the lock; a dead option answers the honest NOT_FOUND.
        Optional<CatalogAttributeDefinition> parent =
                attributeDefinitionRepository.findByIdForUpdate(row.getAttributeDefinitionId());
        if (parent.isEmpty()) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND,
                    "No attribute definition found for the value's parent")));
        }
        if (!attributeOptionRepository.existsById(row.getId())) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND,
                    "No attribute value found for the id")));
        }
        // The 2026-11-08 #808 round-21 review (Minor · Stability &
        // Availability): the option RE-READS under the lock — the row
        // was loaded BEFORE the definition lock was taken, so a
        // concurrent reorder (the locked definition writer that
        // repositions every option) or a sibling translation that
        // committed while THIS transaction waited on the lock leaves the
        // managed row's @Version stale; the save would answer the raw
        // OptimisticLockException. The JPA-documented re-read-under-lock
        // is refresh(entity, PESSIMISTIC_WRITE) — the row's state is
        // reloaded from the authoritative row (the OrdersService
        // confirmFromPayment precedent verbatim).
        entityManager.refresh(row, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (command.name() == null) {
            return AttributeValueMutationResult.success(toView(parent.get()), toValueView(row));
        }
        // The option's own display-metadata amendment seam (the
        // entity's own validateNames law rides inside it — the
        // backstop over the service's own precheck).
        row.revise(mergedEn(command, row.getNameEn()), mergedAr(command, row.getNameAr()));
        CatalogAttributeOption updated = attributeOptionRepository.save(row);
        return AttributeValueMutationResult.success(toView(parent.get()), toValueView(updated));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CollectionMutationResult translateCollection(UUID id, TranslationAmendCommand command) {
        // The 2026-11-08 #808 round-21 review (Minor · Data Integrity &
        // Integration, additional comment): the translation rides the
        // SAME advisory lock every other collection writer carries —
        // the locked-first law (the updateCollection/deleteCollection
        // discipline). An unlocked load let a concurrent delete commit
        // between the read and the save, and the translation then wrote
        // over the soft-deleted row and answered a fabricated SUCCESS
        // for a collection no read could resolve, or the stale
        // @Version answered the raw OptimisticLockException.
        if (id != null) {
            collectionProductRepository.lockCollectionPositionAllocation(id.toString());
        }
        Optional<Collection> addressed = id == null ? Optional.empty() : collectionRepository.findById(id);
        if (addressed.isEmpty()) {
            return CollectionMutationResult.failure(List.of(new CollectionMutationError(
                    "id", CollectionMutationErrorCode.NOT_FOUND, "No collection found for the id")));
        }
        List<CollectionMutationError> errors = ladder(command,
                (field, message) -> new CollectionMutationError(field,
                        CollectionMutationErrorCode.INVALID, message));
        if (!errors.isEmpty()) {
            return CollectionMutationResult.failure(errors);
        }
        Collection row = addressed.get();
        if (command.name() == null) {
            return CollectionMutationResult.success(toView(row));
        }
        row.translate(mergedEn(command, row.getNameEn()), mergedAr(command, row.getNameAr()));
        return CollectionMutationResult.success(toView(collectionRepository.save(row)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<TranslationBulkOutcome> bulkTranslateAttributes(List<TranslationBulkItem> items,
                                                                String errorPolicy) {
        List<TranslationBulkOutcome> outcomes = new ArrayList<>(items.size());
        // The wave-19 #808 review (Minor): an unknown policy name is the
        // classified INVALID refusal for every item — never the silent
        // non-atomic persistence path.
        AttributeMutationError policyError = unknownPolicyError(errorPolicy);
        if (policyError != null) {
            for (TranslationBulkItem item : items) {
                outcomes.add(new TranslationBulkOutcome(item.rowId(), item.command().languageCode(),
                        null, List.of(policyError)));
            }
            return outcomes;
        }
        // The 2026-11-08 #808 round-23 review (Minor · Stability &
        // Availability): the definition locks come in ONE sorted pass
        // BEFORE the per-item loop — the per-item translateAttribute
        // locks inside the CALLER's list order, so two concurrent bulk
        // translations naming overlapping definitions in opposite orders
        // could deadlock (the bulkUpdateAttributes round-22 law). The
        // per-item re-lock is free (this transaction already holds the
        // rows).
        for (UUID id : items.stream()
                .map(TranslationBulkItem::rowId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList()) {
            attributeDefinitionRepository.findByIdForUpdate(id);
        }
        for (TranslationBulkItem item : items) {
            // Self-invocation joins THIS transaction (the bulk boundary
            // owns the authorization and the policy); the single-item
            // ladder's own validation answers per item.
            AttributeMutationResult result = translateAttribute(item.rowId(), item.command());
            String name = result.attribute() == null ? null
                    : attributeDefinitionRepository.findById(item.rowId())
                            .map(row -> nameIn(row, item.command().languageCode()))
                            .orElse(null);
            outcomes.add(toOutcome(item, name, result.errors()));
        }
        if (atomic(errorPolicy) && outcomes.stream().anyMatch(outcome -> !outcome.success())) {
            // The pinned default policy: one refused item refuses the
            // whole batch — the official rollback-marking mechanism.
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            // The wave-19 #808 review (Major): the rollback erased every
            // translation the passing items claim — no phantom name
            // survives the batch's own refusal.
            outcomes = rewriteRolledBackOutcomes(outcomes);
        }
        return outcomes;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<TranslationBulkOutcome> bulkTranslateAttributeValues(List<TranslationBulkItem> items,
                                                                     String errorPolicy) {
        List<TranslationBulkOutcome> outcomes = new ArrayList<>(items.size());
        AttributeMutationError policyError = unknownPolicyError(errorPolicy);
        if (policyError != null) {
            for (TranslationBulkItem item : items) {
                outcomes.add(new TranslationBulkOutcome(item.rowId(), item.command().languageCode(),
                        null, List.of(policyError)));
            }
            return outcomes;
        }
        // The 2026-11-08 #808 round-23 review (Minor · Stability &
        // Availability): the definitions behind the batch's options lock
        // in ONE sorted pass BEFORE the per-item loop — the per-item
        // translateAttributeValue takes the family lock inside the
        // CALLER's list order, so two concurrent batches naming
        // overlapping families in opposite orders could deadlock (the
        // bulkUpdateAttributes round-22 law). The pass reads each
        // option's owning definition id only to ORDER the locks — the
        // per-item ladder re-validates everything under the lock it
        // already holds (the round-11 drop-id/lock/load discipline, the
        // per-item re-reads).
        for (UUID definitionId : items.stream()
                .map(TranslationBulkItem::rowId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(id -> attributeOptionRepository.findById(id)
                        .map(CatalogAttributeOption::getAttributeDefinitionId)
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList()) {
            attributeDefinitionRepository.findByIdForUpdate(definitionId);
        }
        for (TranslationBulkItem item : items) {
            AttributeValueMutationResult result = translateAttributeValue(item.rowId(), item.command());
            String name = result.value() == null ? null
                    : attributeOptionRepository.findById(item.rowId())
                            .map(row -> nameIn(row, item.command().languageCode()))
                            .orElse(null);
            outcomes.add(toOutcome(item, name, result.errors()));
        }
        if (atomic(errorPolicy) && outcomes.stream().anyMatch(outcome -> !outcome.success())) {
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            outcomes = rewriteRolledBackOutcomes(outcomes);
        }
        return outcomes;
    }

    /** The pinned default policy's own verdict — REJECT_EVERYTHING unless another member is sent. */
    private static boolean atomic(String errorPolicy) {
        return errorPolicy == null || "REJECT_EVERYTHING".equals(errorPolicy);
    }

    /** The unknown-policy refusal — the classified INVALID entry, null for a known member. */
    private static AttributeMutationError unknownPolicyError(String errorPolicy) {
        if (errorPolicy == null || "REJECT_EVERYTHING".equals(errorPolicy)
                || "IGNORE_FAILED".equals(errorPolicy)
                || "REJECT_FAILED_ROWS".equals(errorPolicy)) {
            return null;
        }
        return new AttributeMutationError("errorPolicy", AttributeMutationErrorCode.INVALID,
                "The error policy is REJECT_EVERYTHING, IGNORE_FAILED or REJECT_FAILED_ROWS");
    }

    /** The rollback's outcome rewrite — no phantom translation name survives the batch's refusal. */
    private static List<TranslationBulkOutcome> rewriteRolledBackOutcomes(
            List<TranslationBulkOutcome> outcomes) {
        List<TranslationBulkOutcome> rewritten = new ArrayList<>(outcomes.size());
        for (TranslationBulkOutcome outcome : outcomes) {
            if (outcome.success()) {
                rewritten.add(new TranslationBulkOutcome(outcome.rowId(), outcome.languageCode(), null,
                        List.of(new AttributeMutationError("errorPolicy", AttributeMutationErrorCode.INVALID,
                                "The batch was rolled back by the REJECT_EVERYTHING policy; this item was not persisted"))));
            } else {
                rewritten.add(outcome);
            }
        }
        return rewritten;
    }

    /** The per-item outcome — the amended name in the addressed dimension, or the item's errors. */
    private static TranslationBulkOutcome toOutcome(TranslationBulkItem item, String name,
                                                     List<AttributeMutationError> errors) {
        return new TranslationBulkOutcome(item.rowId(), item.command().languageCode(), name, errors);
    }

    /** The attribute definition's amended name in the addressed language dimension (the merged pair's own value). */
    private static String nameIn(CatalogAttributeDefinition row, String languageCode) {
        return "AR".equals(languageCode) ? row.getNameAr() : row.getNameEn();
    }

    /** The CHOICE option's amended name in the addressed language dimension. */
    private static String nameIn(CatalogAttributeOption row, String languageCode) {
        return "AR".equals(languageCode) ? row.getNameAr() : row.getNameEn();
    }

    // ------------------------------------------------------------------
    // the shared bilingual ladder — the four registries' own error
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

    private static CollectionView toView(Collection collection) {
        String name = collection.getNameEn() != null ? collection.getNameEn() : collection.getNameAr();
        return new CollectionView(collection.getId(), name, collection.getNameAr(),
                collection.getSlug(), collection.isPublished());
    }
}
