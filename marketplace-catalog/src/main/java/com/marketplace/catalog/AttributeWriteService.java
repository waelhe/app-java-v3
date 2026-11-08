package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributeBulkOutcome;
import com.marketplace.catalog.spi.AttributeBulkUpdateItem;
import com.marketplace.catalog.spi.AttributeCreateCommand;
import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeMutationResult;
import com.marketplace.catalog.spi.AttributeReorderMove;
import com.marketplace.catalog.spi.AttributeUpdateCommand;
import com.marketplace.catalog.spi.AttributeView;
import com.marketplace.catalog.spi.AttributeWriteSpi;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeCreate}, {@code attributeUpdate},
 * {@code attributeDelete} and {@code attributeBulkDelete} mutation
 * roots, the agent-2 packet): the owning use case for the reusable
 * typed attribute collection's ADMIN write surface. These are Saleor
 * MANAGEMENT roots ("Requires one of the following permissions,
 * depending on the attribute type: MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES
 * for PRODUCT_TYPE attributes...") mapped through the platform's
 * ADMIN capability (the same {@code CatalogAttributeService}
 * registerDefinition/updateDefinition precedent the module itself
 * established — the attribute read seam's own RBAC row) and enforced
 * HERE at the owning use-case boundary by Spring Security's method
 * security — never only in a resolver.
 *
 * <p><b>The validation rules are the row's own contracts</b> — the
 * entity factory's own documented shapes: the {@code code} is a
 * lowercase latin slug (the entity's own {@code ^[a-z][a-z0-9-]*$}
 * shape, at most 50 characters), at least one display name must exist
 * (the bilingual row's own at-least-one contract — the create's Saleor
 * {@code name} binds the name_en dimension), and the closed value
 * shape is required (the {@code value_type} NOT NULL column's own
 * law). The service re-checks all three BEFORE the write (the honest
 * precheck over the derived {@code findByCode} lookup) and lets the
 * column's own uniqueness remain the concurrent-write backstop — the
 * same no-TOCTOU stance the category and product-type registries'
 * write services established. Domain validation outcomes answer as
 * result error entries (the Saleor payload-error ADAPT); transport
 * concerns never reach this service.</p>
 *
 * <p><b>Update is partial</b> — {@code null} command fields preserve
 * the column, the Saleor input's omit-to-preserve semantics; an
 * explicit blank name answers the INVALID domain error (the entity's
 * own at-least-one-name law, re-checked over the MERGED display pair).
 * <b>The row's stable public code and value shape are immutable</b>
 * (the entity's own law — "Type and code are stable identity; only
 * display metadata can be amended", no mutating method exists for
 * either): a slug command equal to the row's own code is the
 * idempotent re-send; any DIFFERENT slug answers the classified
 * INVALID domain error — never a silent drop, never an unauthorized
 * move (the Saleor input's sent-field law).
 * <b>Soft delete</b> rides the {@code BaseEntity} {@code @SoftDelete}
 * discipline: {@code repository.delete} converts to the
 * {@code is_deleted} flag, and the deleted row's last-known projection
 * answers in the payload (Saleor's own delete-payload semantics).</p>
 */
@Service
public class AttributeWriteService implements AttributeWriteSpi {

    /**
     * The code shape the entity factory itself pins — a lowercase latin
     * slug (mirrored at the boundary so an invalid shape answers the
     * classified INVALID domain error, never an entity throw).
     */
    static final Pattern CODE_SHAPE = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** The row's own column ceilings (the entity's own length contracts). */
    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 100;

    static final String POLICY_REJECT_EVERYTHING = "REJECT_EVERYTHING";
    static final String POLICY_IGNORE_FAILED = "IGNORE_FAILED";
    static final String POLICY_REJECT_FAILED_ROWS = "REJECT_FAILED_ROWS";

    private final CatalogAttributeDefinitionRepository attributeDefinitionRepository;
    private final CatalogAttributeOptionRepository attributeOptionRepository;
    private final ProductTypeAttributeAssignmentRepository assignmentRepository;
    /** The live-variant reference probe the delete guards read (the 2026-11-06 #808 round). */
    private final ProductVariantAttributeValueRepository variantAttributeValues;
    /** The live-PRODUCT references the children guard reads (the #808 round-3). */
    private final ProductAttributeValueRepository productAttributeValues;
    /** The insert's own transaction door (the 2026-11-07 #808 round-19). */
    private final AttributeCreationExecutor creationExecutor;

    public AttributeWriteService(CatalogAttributeDefinitionRepository attributeDefinitionRepository,
                                 CatalogAttributeOptionRepository attributeOptionRepository,
                                 ProductTypeAttributeAssignmentRepository assignmentRepository,
                                 ProductVariantAttributeValueRepository variantAttributeValues,
                                 ProductAttributeValueRepository productAttributeValues,
                                 AttributeCreationExecutor creationExecutor) {
        this.attributeDefinitionRepository = attributeDefinitionRepository;
        this.attributeOptionRepository = attributeOptionRepository;
        this.assignmentRepository = assignmentRepository;
        this.variantAttributeValues = variantAttributeValues;
        this.productAttributeValues = productAttributeValues;
        this.creationExecutor = creationExecutor;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public AttributeMutationResult createAttribute(AttributeCreateCommand command) {
        NewAttribute candidate = validateNewAttribute(command);
        if (!candidate.valid()) {
            return AttributeMutationResult.failure(candidate.errors());
        }
        // The 2026-11-07 #808 round-19 review (Minor · Data Integrity &
        // Integration): the insert crossed the executor's REQUIRES_NEW
        // boundary — the reserved-code precheck is honest but not atomic,
        // and a catch INSIDE the flushing transaction returns into a
        // commit that throws UnexpectedRollbackException instead of the
        // classified ALREADY_EXISTS result (the CategoryCreationExecutor
        // precedent verbatim — the loser arrives fully rolled back, the
        // catch below answers the payload error).
        CatalogAttributeDefinition created;
        try {
            created = creationExecutor.insert(candidate.code(), candidate.name(), candidate.valueType());
        } catch (DataIntegrityViolationException violation) {
            if (isAttributeCodeConflict(violation)) {
                return AttributeMutationResult.failure(List.of(new AttributeMutationError(
                        "slug", AttributeMutationErrorCode.ALREADY_EXISTS,
                        "An attribute with the slug already exists")));
            }
            throw violation;
        }
        return AttributeMutationResult.success(toView(created));
    }

    /** The create's own validated payload (empty errors = valid). */
    private record NewAttribute(String code, String name, CatalogAttributeType valueType,
                                List<AttributeMutationError> errors) {
        boolean valid() { return errors.isEmpty(); }
    }

    /** The create's shared validation ladder — the single and bulk paths own the same law. */
    private NewAttribute validateNewAttribute(AttributeCreateCommand command) {
        List<AttributeMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name == null || name.isBlank()) {
            errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.REQUIRED,
                    "An attribute name is required"));
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.INVALID,
                    "An attribute name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        if (code == null || code.isBlank()) {
            errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.REQUIRED,
                    "An attribute slug is required"));
        } else if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
            errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.INVALID,
                    "An attribute slug is a lowercase latin slug (at most "
                            + CODE_MAX_LENGTH + " characters)"));
        } else if (attributeDefinitionRepository.findByCode(code).isPresent()) {
            errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.ALREADY_EXISTS,
                    "An attribute with the slug already exists"));
        }
        if (command.valueType() == null) {
            errors.add(new AttributeMutationError("inputType", AttributeMutationErrorCode.REQUIRED,
                    "An attribute input type is required"));
        }
        return new NewAttribute(code, name, command.valueType(), errors);
    }

    /**
     * The concurrent-create backstop's own discrimination (the
     * CategoryWriteService {@code uq_categories_code} precedent): only
     * the violation naming THIS index answers the payload error — a
     * DIVE carrying more than the code collision rethrows untouched.
     */
    private static boolean isAttributeCodeConflict(DataIntegrityViolationException violation) {
        String message = violation.getMessage();
        return message != null && message.contains("uq_catalog_attribute_definitions_code_live");
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeMutationResult updateAttribute(UUID id, AttributeUpdateCommand command) {
        // The 2026-11-08 #808 round-21 review (Minor · Data Integrity &
        // Integration): the lifecycle's locked load — the edit serializes
        // with the delete (and every other definition writer) on the row
        // itself. An unlocked read let a concurrent deleteAttribute
        // commit between the load and the save, and the update then
        // wrote over the soft-deleted row and answered a fabricated
        // SUCCESS for an attribute no read could resolve (the soft
        // delete never bumps the version, and Hibernate's plain UPDATE
        // carries no is_deleted=false guard), or the stale @Version
        // answered the raw OptimisticLockException.
        Optional<CatalogAttributeDefinition> addressed = id == null
                ? Optional.empty()
                : attributeDefinitionRepository.findByIdForUpdate(id);
        if (addressed.isEmpty()) {
            return AttributeMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute found for the id")));
        }
        if (command.isEmpty()) {
            return AttributeMutationResult.success(toView(addressed.get()));
        }
        List<AttributeMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name != null) {
            if (name.isBlank()) {
                errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.INVALID,
                        "An attribute name cannot be blank"));
            } else if (name.length() > NAME_MAX_LENGTH) {
                errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.INVALID,
                        "An attribute name is at most " + NAME_MAX_LENGTH + " characters"));
            }
        }
        if (code != null) {
            // The entity's own law: "Type and code are stable identity;
            // only display metadata can be amended" (no code-mutating
            // method exists on CatalogAttributeDefinition). A slug equal
            // to the row's own code is the idempotent re-send; any
            // DIFFERENT slug answers the classified INVALID domain
            // error — a sent field is never a silent no-op.
            if (!code.equals(addressed.get().getCode())) {
                errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.INVALID,
                        "An attribute slug is immutable; it cannot change after creation"));
            } else if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
                errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.INVALID,
                        "An attribute slug is a lowercase latin slug (at most "
                                + CODE_MAX_LENGTH + " characters)"));
            } else {
                attributeDefinitionRepository.findByCode(code)
                        .filter(existing -> !existing.getId().equals(id))
                        .ifPresent(existing -> errors.add(new AttributeMutationError(
                                "slug", AttributeMutationErrorCode.ALREADY_EXISTS,
                                "An attribute with the slug already exists")));
            }
        }
        if (!errors.isEmpty()) {
            return AttributeMutationResult.failure(errors);
        }
        CatalogAttributeDefinition row = addressed.get();
        // The merged display pair rides the entity's own partial-update
        // law (its at-least-one-name validation runs over the MERGED
        // names, never the command's slice alone).
        String mergedNameEn = name == null ? row.getNameEn() : name;
        row.update(mergedNameEn, row.getNameAr(), row.getDescription());
        CatalogAttributeDefinition updated = attributeDefinitionRepository.save(row);
        return AttributeMutationResult.success(toView(updated));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeMutationResult deleteAttribute(UUID id) {
        // The 2026-11-06 #808 round review (Major · Data Integrity &
        // Integration): the FOR-UPDATE load — the assignment-creating
        // paths read this row FOR SHARE (the ProductTypeAttributeService
        // .assign / assignAttributes pairing), so the delete's
        // children-guard verdict is COMMITTED-stable against an
        // in-flight assignment save (never a live assignment left
        // pointing at a soft-deleted attribute).
        Optional<CatalogAttributeDefinition> addressed = id == null
                ? Optional.empty()
                : attributeDefinitionRepository.findByIdForUpdate(id);
        if (addressed.isEmpty()) {
            return AttributeMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute found for the id")));
        }
        CatalogAttributeDefinition row = addressed.get();
        AttributeView lastKnown = toView(row);
        // The 2026-11-06 #808 round review (Major · Data Integrity &
        // Integration): the live-variant-values guard — the attribute's
        // assignments and options ARE the variant schema; deleting them
        // while a live variant carries their values leaves the value
        // rows bound to soft-deleted assignments and the variant keys
        // computed on a dead schema (a fresh registration under the
        // shrunk schema could then duplicate the combination). The
        // refuse is the honest verdict: the variants migrate first,
        // then the attribute goes.
        if (variantAttributeValuesLiveFor(row.getId())) {
            return AttributeMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.INVALID,
                    "An attribute whose assignments or options are referenced by a live"
                            + " variant cannot be deleted — migrate the variants first")));
        }
        // The wave-19 #808 review (Major · Data Integrity): the soft delete
        // updates only the definition's own is_deleted flag — the live
        // option and assignment rows would keep referencing a deleted
        // attribute (the FK never fires on a soft delete), and a later
        // option translation would hit the parent-absent throw. The delete
        // cascades to the attribute's own children (the Saleor
        // attributeDelete semantics — values and type assignments go with
        // the attribute), inside the same transaction.
        deleteAttributeChildren(row.getId());
        attributeDefinitionRepository.delete(row);
        return AttributeMutationResult.success(lastKnown);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public int deleteAttributes(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // The 2026-11-07 #808 round-4 review (Minor · Stability &amp;
        // Availability): the definition locks come in ONE sorted pass —
        // the previous per-id loop took them in the CALLER'S list order,
        // so two concurrent bulk deletes naming two definitions in
        // OPPOSITE orders could deadlock (each holding one row and
        // waiting for the other). The same UUID order the bulk OPTION
        // delete (deleteAttributeValues) established: every id is locked
        // FOR-UPDATE in SORTED order, the unknown ones answer empty and
        // are skipped, and the guards and deletes walk the locked rows.
        java.util.Map<UUID, CatalogAttributeDefinition> addressedById = new java.util.LinkedHashMap<>();
        // Unknown or null ids are skipped — the honest affected count
        // answers what the registry itself deleted (never a fabricated
        // count, never an error entry for an id the registry never knew).
        for (UUID id : ids.stream().filter(java.util.Objects::nonNull).sorted().toList()) {
            attributeDefinitionRepository.findByIdForUpdate(id)
                    .ifPresent(row -> addressedById.put(id, row));
        }
        int deleted = 0;
        for (CatalogAttributeDefinition row : addressedById.values()) {
            // The 2026-11-06 #808 round review (Major · Data Integrity):
            // the bulk leg rides the SAME FOR-UPDATE load and the SAME
            // live-variant-values guard — the single-item leg's own
            // refusal, in the bulk's honest-skip form.
            if (!variantAttributeValuesLiveFor(row.getId())) {
                // The single delete's cascade (the same wave-19 law): the
                // children go with every deleted attribute row.
                deleteAttributeChildren(row.getId());
                attributeDefinitionRepository.delete(row);
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeMutationResult reorderValues(UUID attributeId, List<AttributeReorderMove> moves) {
        // The 2026-11-06 #808 round review (Minor · Stability &
        // Availability): the two-phase rewrite serializes on the locked
        // definition row — a concurrent create's append and this
        // reorder's park range never interleave their position writes
        // (the ProductTypeRepository.findByIdForUpdate precedent).
        Optional<CatalogAttributeDefinition> addressed =
                attributeId == null ? Optional.empty()
                        : attributeDefinitionRepository.findByIdForUpdate(attributeId);
        if (addressed.isEmpty()) {
            return AttributeMutationResult.failure(List.of(new AttributeMutationError(
                    "attributeId", AttributeMutationErrorCode.NOT_FOUND, "No attribute found for the id")));
        }
        List<CatalogAttributeOption> ordered = new ArrayList<>(
                attributeOptionRepository.findAllByAttributeDefinitionIdOrderByPositionAsc(attributeId));
        List<AttributeMutationError> errors = new ArrayList<>();
        for (AttributeReorderMove move : moves) {
            int currentIndex = indexOfValue(ordered, move.itemId());
            if (currentIndex < 0) {
                errors.add(new AttributeMutationError("id", AttributeMutationErrorCode.NOT_FOUND,
                        "No attribute value found for the id under the attribute"));
                continue;
            }
            int shift = move.sortOrder() == null ? 0 : move.sortOrder();
            int targetIndex = currentIndex + shift;
            if (targetIndex < 0 || targetIndex >= ordered.size()) {
                errors.add(new AttributeMutationError("sortOrder", AttributeMutationErrorCode.INVALID,
                        "The relative move leaves the value order"));
                continue;
            }
            CatalogAttributeOption moved = ordered.remove(currentIndex);
            ordered.add(targetIndex, moved);
        }
        if (!errors.isEmpty()) {
            return AttributeMutationResult.failure(errors);
        }
        // The live-rows unique index (attribute_definition_id, position)
        // makes a direct 1..n rewrite collide TRANSIENTLY: a row moving to
        // position 1 still shares it with the row currently holding 1
        // until that row's own UPDATE executes — the partial unique index
        // sees the intermediate state. The two-phase rewrite keeps EVERY
        // intermediate state unique: phase 1 parks the WHOLE bucket beyond
        // its current maximum (mutually unique, above every live position),
        // flushes (the 1..max range is now empty), then phase 2 writes the
        // final dense order into the empty range — all inside the same
        // transaction, all-or-nothing with the reorder itself.
        boolean changed = false;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getPosition() != i + 1) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            // The zero-shift idempotent no-op: the resolved order is the
            // stored order — nothing changes, nothing writes.
            return AttributeMutationResult.success(toView(addressed.get()));
        }
        // The 2026-11-06 #808 round review (Minor · Stability &amp;
        // Availability): the park range starts beyond BOTH the stored
        // maximum AND the bucket's own size — a position-0 option pair
        // (0 and 1, both legal through registerOption and the DB check)
        // parks at [2, 3] under the max-only rule, and phase 2's dense
        // rewrite [1, 2] can land a row ON position 2 BEFORE Hibernate
        // issues the row-at-2's own vacating update (the flush order is
        // not guaranteed across managed entities) — the unique index
        // throws mid-rewrite. Parking at max(maxPosition, size) + 1
        // keeps the final dense range strictly below the parked range,
        // so no phase-2 write can collide with a not-yet-vacated seat.
        // The 2026-11-07 #808 round-19 review (Minor · Functional
        // Correctness, the overflow family): the park base rides the
        // shared PositionSequences law — a stored Integer.MAX_VALUE
        // position (the REST registerOption surface accepts one) wraps
        // the previous max+1 arithmetic to a negative base, and the DB's
        // own CHECK answered an unclassified failure. The shared law
        // parks above the stored maximum when the range fits, else in
        // the lowest free window at or above the dense range's own top
        // — the reorder IS the compaction path, it must succeed WITH a
        // ceiling-high position present.
        int park = PositionSequences.parkBase(
                ordered.stream().map(CatalogAttributeOption::getPosition).toList(), ordered.size());
        if (park < 0) {
            return AttributeMutationResult.failure(List.of(new AttributeMutationError(
                    "attributeId", AttributeMutationErrorCode.INVALID,
                    "The attribute's option positions leave no legal parking range for the rewrite")));
        }
        for (CatalogAttributeOption option : ordered) {
            option.reposition(park++);
        }
        attributeOptionRepository.saveAll(ordered);
        attributeOptionRepository.flush();
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).reposition(i + 1);
        }
        attributeOptionRepository.saveAll(ordered);
        return AttributeMutationResult.success(toView(addressed.get()));
    }

    private static int indexOfValue(List<CatalogAttributeOption> ordered, UUID valueId) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(valueId)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // the bulk roots (the agent-2 follow-on packet) — the single-item
    // ladder's own answers at their bulk positions, riding ONE
    // transaction with the pinned error policy's own semantics
    // ------------------------------------------------------------------

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<AttributeBulkOutcome> bulkCreateAttributes(List<AttributeCreateCommand> commands,
                                                           String errorPolicy) {
        List<AttributeBulkOutcome> outcomes;
        // The wave-19 #808 review (Minor): an unknown policy name is the
        // classified INVALID refusal for every item — never the silent
        // non-atomic persistence path the old atomic() predicate implied.
        AttributeMutationError policyError = unknownPolicyError(errorPolicy);
        if (policyError != null) {
            outcomes = new ArrayList<>(commands.size());
            for (AttributeCreateCommand command : commands) {
                outcomes.add(new AttributeBulkOutcome(null, List.of(policyError)));
            }
            return outcomes;
        }
        // The 2026-11-08 #808 round-24 review (Minor · Stability &
        // Availability): the VALIDATION pass and the INSERT pass are
        // separated, and the claims ride ONE unified order — the
        // normalized slug's own sorted order. The previous shape saved
        // inside the per-item loop in the CALLER's list order, so two
        // concurrent batches creating overlapping slugs in opposite
        // orders could meet the database's deadlock detector on the
        // unique index claims (each transaction holding one claim and
        // waiting for the other's — the TLA+ shape the review modeled:
        // the claim is taken at flush, and a LATER item's findByCode
        // precheck auto-flushes the EARLIER item's pending insert, so
        // the claims interleave with the prechecks in the caller's
        // order). The unified claim order means a rival only ever WAITS.
        // The validation pass owns pure reads (no pending insert exists
        // while it runs, so no precheck auto-flushes anything), the
        // saves are bare persists (no query between them), and ONE
        // flush at the boundary lands the claims in the sorted order.
        // The outcomes still answer in the CALLER's own order.
        List<NewAttribute> candidates = new ArrayList<>(commands.size());
        for (AttributeCreateCommand command : commands) {
            candidates.add(validateNewAttribute(command));
        }
        List<Integer> claims = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            if (candidates.get(i).valid()) {
                claims.add(i);
            }
        }
        claims.sort(java.util.Comparator.comparing(position -> candidates.get(position).code()));
        CatalogAttributeDefinition[] created = new CatalogAttributeDefinition[commands.size()];
        for (int position : claims) {
            NewAttribute candidate = candidates.get(position);
            created[position] = attributeDefinitionRepository.save(
                    CatalogAttributeDefinition.create(candidate.code(), candidate.name(), null,
                            candidate.valueType(), null));
        }
        try {
            // The flush lands the pending inserts INSIDE the boundary — a
            // concurrent rival's committed code surfaces its violation
            // HERE (never at the interceptor's commit, past the catch),
            // and a WITHIN-batch duplicate slug surfaces here too (the
            // second claim hits the first's own uncommitted index entry).
            attributeDefinitionRepository.flush();
        } catch (DataIntegrityViolationException violation) {
            // The 2026-11-07 #808 round-19 review (Minor · Data Integrity
            // & Integration): the concurrent-collision backstop — the
            // precheck is honest but not atomic, and PostgreSQL aborts
            // the whole transaction on the statement error (per-item
            // isolation inside one transaction is the engine's own law,
            // not a design choice): every outcome answers the collision
            // honestly, the batch rolls back whole (the same
            // setRollbackOnly + rewrite discipline the atomic policy
            // owns). The 2026-11-08 #808 round-22 review's in-loop twin
            // is subsumed here: with validation and inserts separated no
            // precheck query runs after a save, so the flush is the ONE
            // point where a claim can fail.
            if (!isAttributeCodeConflict(violation)) {
                throw violation;
            }
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            AttributeMutationError collision = new AttributeMutationError(
                    "slug", AttributeMutationErrorCode.ALREADY_EXISTS,
                    "An attribute with the slug was committed concurrently — the batch rolled back whole");
            List<AttributeBulkOutcome> rewritten = new ArrayList<>(candidates.size());
            for (NewAttribute candidate : candidates) {
                rewritten.add(candidate.valid()
                        ? new AttributeBulkOutcome(null, List.of(collision))
                        : new AttributeBulkOutcome(null, appendError(candidate.errors(), collision)));
            }
            return rewritten;
        }
        outcomes = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            outcomes.add(candidates.get(i).valid()
                    ? new AttributeBulkOutcome(toView(created[i]), List.of())
                    : new AttributeBulkOutcome(null, candidates.get(i).errors()));
        }
        if (atomic(errorPolicy) && outcomes.stream().anyMatch(outcome -> !outcome.success())) {
            // The pinned default policy: one refused item refuses the
            // whole batch — the official rollback-marking mechanism.
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            // The wave-19 #808 review (Major): the rollback erased every
            // row the passing items claim — a success outcome would hand
            // the client ids that do not exist (the Saleor REJECT_EVERYTHING
            // contract returns no saved entity for any item). The successful
            // outcomes are rewritten to the batch's own classified refusal.
            outcomes = rewriteRolledBackOutcomes(outcomes);
        }
        return outcomes;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<AttributeBulkOutcome> bulkUpdateAttributes(List<AttributeBulkUpdateItem> items,
                                                            String errorPolicy) {
        List<AttributeBulkOutcome> outcomes = new ArrayList<>(items.size());
        AttributeMutationError policyError = unknownPolicyError(errorPolicy);
        if (policyError != null) {
            for (AttributeBulkUpdateItem item : items) {
                outcomes.add(new AttributeBulkOutcome(null, List.of(policyError)));
            }
            return outcomes;
        }
        // The 2026-11-08 #808 round-22 review (Minor · Stability &
        // Availability): the definition locks come in ONE sorted pass
        // BEFORE the per-item loop — the previous shape took each item's
        // findByIdForUpdate inside the CALLER's list order, so two
        // concurrent bulk updates naming overlapping definitions in
        // opposite orders could deadlock (each holding one row and
        // waiting for the other's). The same natural-UUID order the
        // module's definition writers already own (deleteAttributes and
        // deleteAttributeValues' shared pass); the per-item re-lock
        // inside updateAttribute is free (this transaction already holds
        // the rows), and the items still process and answer in the
        // caller's own order.
        for (UUID id : items.stream()
                .map(AttributeBulkUpdateItem::id)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList()) {
            attributeDefinitionRepository.findByIdForUpdate(id);
        }
        for (AttributeBulkUpdateItem item : items) {
            AttributeMutationResult result = updateAttribute(item.id(), item.fields());
            outcomes.add(new AttributeBulkOutcome(result.attribute(), result.errors()));
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
        return errorPolicy == null || POLICY_REJECT_EVERYTHING.equals(errorPolicy);
    }

    /**
     * The unknown-policy refusal — null when the policy is a known member
     * (or the default), the classified INVALID entry otherwise (the
     * wave-19 #808 review: an unknown policy name never falls through to
     * the non-atomic path).
     */
    private static AttributeMutationError unknownPolicyError(String errorPolicy) {
        if (errorPolicy == null || POLICY_REJECT_EVERYTHING.equals(errorPolicy)
                || POLICY_IGNORE_FAILED.equals(errorPolicy)
                || POLICY_REJECT_FAILED_ROWS.equals(errorPolicy)) {
            return null;
        }
        return new AttributeMutationError("errorPolicy", AttributeMutationErrorCode.INVALID,
                "The error policy is REJECT_EVERYTHING, IGNORE_FAILED or REJECT_FAILED_ROWS");
    }

    /**
     * The rollback's outcome rewrite — every success outcome loses its
     * phantom projection (the row it names was never persisted) and
     * carries the batch's own classified refusal instead; the already-
     * failed outcomes stay exactly as the per-item ladder answered them.
     */
    private static List<AttributeBulkOutcome> rewriteRolledBackOutcomes(List<AttributeBulkOutcome> outcomes) {
        List<AttributeBulkOutcome> rewritten = new ArrayList<>(outcomes.size());
        for (AttributeBulkOutcome outcome : outcomes) {
            if (outcome.success()) {
                rewritten.add(new AttributeBulkOutcome(null, List.of(
                        new AttributeMutationError("errorPolicy", AttributeMutationErrorCode.INVALID,
                                "The batch was rolled back by the REJECT_EVERYTHING policy; this item was not persisted"))));
            } else {
                rewritten.add(outcome);
            }
        }
        return rewritten;
    }

    /** The collision rewrite's own append — the already-refused item keeps its own verdict plus the batch's. */
    private static List<AttributeMutationError> appendError(List<AttributeMutationError> errors,
                                                            AttributeMutationError collision) {
        List<AttributeMutationError> combined = new ArrayList<>(errors);
        combined.add(collision);
        return List.copyOf(combined);
    }

    /**
     * The attribute's own child rows — the live options and the live
     * product-type assignments. Soft-deleted WITH the attribute (the
     * wave-19 #808 review's cascade; never left referencing a deleted
     * parent).
     */
    private void deleteAttributeChildren(UUID attributeId) {
        List<CatalogAttributeOption> options = attributeOptionRepository
                .findAllByAttributeDefinitionIdOrderByPositionAsc(attributeId);
        attributeOptionRepository.deleteAll(options);
        List<ProductTypeAttributeAssignment> assignments = assignmentRepository
                .findAllByAttributeDefinitionId(attributeId);
        assignmentRepository.deleteAll(assignments);
    }

    /**
     * The 2026-11-06 #808 round review (Major · Data Integrity &amp;
     * Integration): whether ANY live variant carries a value bound to the
     * attribute's own children (its assignments or its options) — the
     * delete's refusal probe (the single-item leg's classified INVALID,
     * the bulk leg's honest skip; the values-and-keys migration demand
     * the finding names).
     *
     * <p>The 2026-11-06 #808 round-3 review (Major · Data Integrity &amp;
     * Integration): the PRODUCT-scope population joins the probe — a
     * live product's own value bound to the attribute's assignments or
     * options is the same orphan the variant probe refuses (the publish
     * path's re-validation reads the assignments and would strand the
     * product on a dead schema). The product write path pairs with this
     * probe through the definition row's FOR-SHARE read
     * (ProductService.validateTypedValue), so the probe's zero answer is
     * COMMITTED-stable against an in-flight product value save.</p>
     */
    private boolean variantAttributeValuesLiveFor(UUID attributeId) {
        List<UUID> optionIds = attributeOptionRepository
                .findAllByAttributeDefinitionIdOrderByPositionAsc(attributeId).stream()
                .map(CatalogAttributeOption::getId)
                .toList();
        List<UUID> assignmentIds = assignmentRepository
                .findAllByAttributeDefinitionId(attributeId).stream()
                .map(ProductTypeAttributeAssignment::getId)
                .toList();
        return variantAttributeValues.existsLiveVariantValueForAssignmentsOrOptions(
                assignmentIds, optionIds)
                || productAttributeValues.existsLiveProductValueForAssignmentsOrOptions(
                        assignmentIds, optionIds);
    }

    /** The bounded projection — the read service's own rendering rule. */
    private static AttributeView toView(CatalogAttributeDefinition definition) {
        String name = definition.getNameEn() != null ? definition.getNameEn() : definition.getNameAr();
        return new AttributeView(definition.getId(), name, definition.getCode(),
                definition.getValueType());
    }
}