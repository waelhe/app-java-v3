package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import com.marketplace.identity.spi.CustomerTypeView;

import java.util.regex.Pattern;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * customer-type registry's owning use case — the
 * {@code customerTypeCreate/Update/Delete} mutations, the attribute
 * assign/unassign/reorder legs, and the {@code customerType(s)} reads.
 *
 * <p><b>ADMIN enforcement</b> at this boundary (the RBAC mapping row).
 * The assigned attribute IDs reference the CATALOG registry — their
 * EXISTENCE is validated at the composition boundary (the app layer's
 * declared {@code AttributeReadSpi}) before any write reaches this owner;
 * this service enforces the registry's own invariants (pair uniqueness,
 * position monotonicity, the one-default swap).</p>
 */
@Service
@Transactional
public class CustomerTypeService implements com.marketplace.identity.spi.CustomerTypeSpi {

    private static final Logger log = LoggerFactory.getLogger(CustomerTypeService.class);
    private static final Pattern SLUG_SHAPE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final CustomerTypeRepository typeRepository;
    private final CustomerTypeAttributeRepository attributeRepository;

    public CustomerTypeService(CustomerTypeRepository typeRepository,
                               CustomerTypeAttributeRepository attributeRepository) {
        this.typeRepository = typeRepository;
        this.attributeRepository = attributeRepository;
    }


    // ---------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------

    /** {@code customerTypeCreate}: the registry row (slug derived from the name when absent). */
    @Observed(name = "customer_type.create")
    public CustomerTypeView create(Authentication caller, String name, String slug,
                                   Boolean isDefault) {
        requireAdmin(caller);
        String trimmed = requireName(name);
        String resolvedSlug = resolveSlug(slug == null || slug.isBlank()
                ? slugify(trimmed) : slug.trim());
        boolean makeDefault = Boolean.TRUE.equals(isDefault);
        CustomerType row = CustomerType.create(trimmed, resolvedSlug, makeDefault);
        if (makeDefault) {
            // B10 (the CodeRabbit integrity round): the cleared default
            // FLUSHES before the new default INSERTS. Hibernate's
            // ActionQueue executes inserts before updates, so the pending
            // clear + pending insert previously hit V250's
            // customer_types_one_default_live_uq with the old row still
            // TRUE — the unique violation killed every default swap on
            // create. The saveAndFlush writes the clear NOW; the insert
            // lands after it.
            typeRepository.findByDefaultTrue().ifPresent(previous -> {
                previous.markDefault(false);
                typeRepository.saveAndFlush(previous);
            });
        }
        typeRepository.save(row);
        log.info("Customer type created: id={}, slug={}, actor={}", row.getId(), resolvedSlug,
                caller.getName());
        return toView(row);
    }

    /** {@code customerTypeUpdate}: the field set + the one-default swap. */
    @Observed(name = "customer_type.update")
    public CustomerTypeView update(Authentication caller, UUID id, String name, String slug,
                                   Boolean isDefault) {
        requireAdmin(caller);
        CustomerType row = requireType(id);
        if (name != null) {
            row.apply(requireName(name), null);
        }
        if (slug != null && !slug.isBlank()) {
            String resolved = resolveSlug(slug.trim());
            if (!resolved.equals(row.getSlug())
                    && typeRepository.existsBySlug(resolved)) {
                throw new ConflictException("The slug already exists: " + resolved);
            }
            row.apply(null, resolved);
        }
        if (isDefault != null) {
            if (isDefault && !row.isDefault()) {
                // B10 (the CodeRabbit integrity round): the same
                // clears-before-set flush ordering as create — two dirty
                // UPDATEs have no guaranteed execution order, so the clear
                // of the previous default writes NOW and the new default's
                // update lands after it, never against V250's partial
                // unique index.
                typeRepository.findByDefaultTrue()
                        .filter(previous -> !previous.getId().equals(id))
                        .ifPresent(previous -> {
                            previous.markDefault(false);
                            typeRepository.saveAndFlush(previous);
                        });
                row.markDefault(true);
            } else if (!isDefault && row.isDefault()) {
                // Saleor keeps exactly one default: unsetting the only
                // default refuses with the honest CANNOT_DELETE_DEFAULT-class error.
                throw new ConflictException(
                        "The default customer type cannot be unset — assign another default first");
            }
        }
        log.info("Customer type updated: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    /** {@code customerTypeDelete}: refuses the default row; cascade-clears assignments. */
    @Observed(name = "customer_type.delete")
    public CustomerTypeView delete(Authentication caller, UUID id) {
        requireAdmin(caller);
        CustomerType row = requireType(id);
        if (row.isDefault()) {
            throw new ConflictException("The default customer type cannot be deleted");
        }
        attributeRepository.findAllByCustomerTypeIdOrderByPositionAscIdAsc(id)
                .forEach(attributeRepository::delete);
        typeRepository.delete(row);
        log.info("Customer type deleted: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    /**
     * {@code customerTypeAssignAttributes}: appends the missing pairs
     * (idempotent re-assign refused). The seen-set carries both the
     * pre-existing assignments and every ID this request already
     * accepted (the CodeRabbit integrity round): a request such as
     * [a, a] previously compared each entry only against the
     * pre-existing list, saved two pairs, and answered the pair unique
     * index's raw database error instead of the classified
     * ConflictException.
     */
    @Observed(name = "customer_type.assign_attributes")
    public CustomerTypeView assignAttributes(Authentication caller, UUID customerTypeId,
                                             List<UUID> attributeIds) {
        requireAdmin(caller);
        CustomerType row = requireType(customerTypeId);
        requireAttributeList(attributeIds);
        List<CustomerTypeAttribute> existing =
                attributeRepository.findAllByCustomerTypeIdOrderByPositionAscIdAsc(customerTypeId);
        int nextPosition = existing.isEmpty() ? 0
                : existing.get(existing.size() - 1).getPosition() + 1;
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (CustomerTypeAttribute pair : existing) {
            seen.add(pair.getAttributeId());
        }
        for (UUID attributeId : attributeIds) {
            if (!seen.add(attributeId)) {
                throw new ConflictException(
                        "The attribute is already assigned to the customer type: " + attributeId);
            }
            attributeRepository.save(
                    CustomerTypeAttribute.create(customerTypeId, attributeId, nextPosition++));
        }
        log.info("Customer type attributes assigned: typeId={}, count={}, actor={}",
                customerTypeId, attributeIds.size(), caller.getName());
        return toView(row);
    }

    /** {@code customerTypeUnassignAttributes}: removes the pairs (absent pairs refuse). */
    @Observed(name = "customer_type.unassign_attributes")
    public CustomerTypeView unassignAttributes(Authentication caller, UUID customerTypeId,
                                               List<UUID> attributeIds) {
        requireAdmin(caller);
        CustomerType row = requireType(customerTypeId);
        requireAttributeList(attributeIds);
        for (UUID attributeId : attributeIds) {
            CustomerTypeAttribute pair = attributeRepository
                    .findByCustomerTypeIdAndAttributeId(customerTypeId, attributeId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "The attribute is not assigned to the customer type: " + attributeId));
            attributeRepository.delete(pair);
        }
        resequence(customerTypeId);
        log.info("Customer type attributes unassigned: typeId={}, count={}, actor={}",
                customerTypeId, attributeIds.size(), caller.getName());
        return toView(row);
    }

    /**
     * {@code customerTypeReorderAttributes}: Saleor's relative-move
     * semantics ({@code moves: [{id, sortOrder}]}). The CodeRabbit
     * correctness round made the move deterministic: each moved pair is
     * REMOVED from the ordered list and reinserted at
     * clamp(index + delta) — a shifted position never ties with a
     * sibling, so the UUID tie-breaker never decides the order — then
     * dense positions follow. The list is a mutable copy: repository
     * queries may return immutable lists.
     */
    @Observed(name = "customer_type.reorder_attributes")
    public CustomerTypeView reorderAttributes(Authentication caller, UUID customerTypeId,
                                              List<UUID> attributeIds, List<Integer> deltas) {
        requireAdmin(caller);
        CustomerType row = requireType(customerTypeId);
        List<CustomerTypeAttribute> pairs = new java.util.ArrayList<>(
                attributeRepository.findAllByCustomerTypeIdOrderByPositionAscIdAsc(customerTypeId));
        for (int i = 0; i < attributeIds.size(); i++) {
            UUID attributeId = attributeIds.get(i);
            int from = indexByAttributeId(pairs, attributeId);
            if (from < 0) {
                throw new ResourceNotFoundException(
                        "The attribute is not assigned to the customer type: " + attributeId);
            }
            int delta = deltas.get(i) == null ? 0 : deltas.get(i);
            CustomerTypeAttribute pair = pairs.remove(from);
            int to = Math.max(0, Math.min(from + delta, pairs.size()));
            pairs.add(to, pair);
        }
        for (int i = 0; i < pairs.size(); i++) {
            pairs.get(i).moveTo(i);
        }
        log.info("Customer type attributes reordered: typeId={}, moves={}, actor={}",
                customerTypeId, attributeIds.size(), caller.getName());
        return toView(row);
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    /** The {@code customerType} read root — ADMIN-gated. */
    @Transactional(readOnly = true)
    public Optional<CustomerTypeView> find(Authentication caller, UUID id) {
        requireAdmin(caller);
        return typeRepository.findById(id).map(this::toView);
    }

    /** The {@code customerTypes} read root — the forward-only keyset scroll. */
    @Transactional(readOnly = true)
    public com.marketplace.identity.spi.CustomerTypeSpi.CustomerTypePage scroll(Authentication caller, com.marketplace.identity.spi.CustomerTypeSpi.CustomerTypeSort sort,
                                   KeysetScrollPosition position, Limit limit,
                                   String search) {
        requireAdmin(caller);
        if (search != null && !search.isBlank()) {
            String needle = search.trim();
            // The search leg (the CodeRabbit pagination round): the SAME
            // real keyset windows as the unfiltered leg — the derived
            // Containing methods return Window results (hasNext comes from
            // the window itself, and the cursors are KeysetScrollPositions
            // the fetcher's forward gate consumes), the method selection
            // honors the requested sort field, and the descending slug
            // method completes the vocabulary.
            Window<CustomerType> window = switch (sort.field()) {
                case NAME -> sort.ascending()
                        ? typeRepository.findByNameContainingIgnoreCaseOrderByNameAscIdAsc(
                                needle, position, limit)
                        : typeRepository.findByNameContainingIgnoreCaseOrderByNameDescIdDesc(
                                needle, position, limit);
                case SLUG -> sort.ascending()
                        ? typeRepository.findByNameContainingIgnoreCaseOrderBySlugAscIdAsc(
                                needle, position, limit)
                        : typeRepository.findByNameContainingIgnoreCaseOrderBySlugDescIdDesc(
                                needle, position, limit);
            };
            return new com.marketplace.identity.spi.CustomerTypeSpi.CustomerTypePage(window.map(this::toView),
                    typeRepository.countByNameContainingIgnoreCase(needle));
        }
        Window<CustomerType> window = switch (sort.field()) {
            case NAME -> sort.ascending()
                    ? typeRepository.findAllByOrderByNameAscIdAsc(position, limit)
                    : typeRepository.findAllByOrderByNameDescIdDesc(position, limit);
            case SLUG -> sort.ascending()
                    ? typeRepository.findAllByOrderBySlugAscIdAsc(position, limit)
                    : typeRepository.findAllByOrderBySlugDescIdDesc(position, limit);
        };
        return new com.marketplace.identity.spi.CustomerTypeSpi.CustomerTypePage(window.map(this::toView), typeRepository.count());
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private CustomerTypeView toView(CustomerType row) {
        return new CustomerTypeView(row.getId(), row.getName(), row.getSlug(), row.isDefault(),
                attributeRepository
                        .findAllByCustomerTypeIdOrderByPositionAscIdAsc(row.getId())
                        .stream().map(CustomerTypeAttribute::getAttributeId).toList());
    }

    private void resequence(UUID customerTypeId) {
        List<CustomerTypeAttribute> pairs =
                attributeRepository.findAllByCustomerTypeIdOrderByPositionAscIdAsc(customerTypeId);
        for (int i = 0; i < pairs.size(); i++) {
            pairs.get(i).moveTo(i);
        }
    }

    private static int indexByAttributeId(List<CustomerTypeAttribute> pairs, UUID attributeId) {
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i).getAttributeId().equals(attributeId)) {
                return i;
            }
        }
        return -1;
    }

    private CustomerType requireType(UUID id) {
        return typeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No customer type " + id));
    }

    private static void requireAttributeList(List<UUID> attributeIds) {
        if (attributeIds == null || attributeIds.isEmpty() || attributeIds.size() > 100) {
            throw new IllegalArgumentException(
                    "Between one and a hundred attribute IDs are required");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A customer type name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("The customer type name exceeds 255 characters");
        }
        return trimmed;
    }

    private String resolveSlug(String slug) {
        if (!SLUG_SHAPE.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "The slug must be lowercase kebab-case: " + slug);
        }
        if (typeRepository.existsBySlug(slug)) {
            throw new ConflictException("The slug already exists: " + slug);
        }
        return slug;
    }

    private static String slugify(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static void requireAdmin(Authentication caller) {
        boolean admin = caller.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException(
                    "Customer type administration requires the ADMIN capability");
        }
    }
}
