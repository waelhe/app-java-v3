package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the CodeRabbit round on PR #696): the customer-type registry's
 * owning guards — the one-default clears-before-set flush ordering (B10),
 * the duplicate-ID rejection inside one request (the seen-set), and the
 * deterministic attribute reorder.
 */
@ExtendWith(MockitoExtension.class)
class CustomerTypeServiceTest {

    @Mock
    private CustomerTypeRepository typeRepository;
    @Mock
    private CustomerTypeAttributeRepository attributeRepository;

    private CustomerTypeService service;

    private Authentication admin;

    @BeforeEach
    void setUp() {
        service = new CustomerTypeService(typeRepository, attributeRepository);
        admin = new UsernamePasswordAuthenticationToken("admin@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void createWithDefaultFlushesTheClearedHolderFirst() {
        CustomerType previous = CustomerType.create("Old", "old", true);
        when(typeRepository.findByDefaultTrue()).thenReturn(Optional.of(previous));

        service.create(admin, "Fresh", "fresh", true);

        // B10: the clear writes NOW (saveAndFlush), the new default's
        // insert lands after it — the partial unique index never sees two
        // live defaults.
        InOrder order = inOrder(typeRepository);
        order.verify(typeRepository).findByDefaultTrue();
        order.verify(typeRepository).saveAndFlush(previous);
        order.verify(typeRepository).save(any(CustomerType.class));
        assertThat(previous.isDefault()).isFalse();
    }

    @Test
    void createWithoutDefaultLeavesTheHolderAlone() {
        service.create(admin, "Plain", "plain", null);

        verify(typeRepository, never()).saveAndFlush(any());
        verify(typeRepository).save(any(CustomerType.class));
    }

    @Test
    void updateSwapsTheDefaultWithTheFlushBetween() {
        UUID id = UUID.randomUUID();
        CustomerType row = CustomerType.create("Next", "next", false);
        CustomerType previous = CustomerType.create("Old", "old", true);
        when(typeRepository.findById(id)).thenReturn(Optional.of(row));
        when(typeRepository.findByDefaultTrue()).thenReturn(Optional.of(previous));

        service.update(admin, id, null, null, true);

        InOrder order = inOrder(typeRepository);
        order.verify(typeRepository).saveAndFlush(previous);
        assertThat(previous.isDefault()).isFalse();
        assertThat(row.isDefault()).isTrue();
    }

    @Test
    void updateRefusesToUnsetTheOnlyDefault() {
        UUID id = UUID.randomUUID();
        CustomerType row = CustomerType.create("Only", "only", true);
        when(typeRepository.findById(id)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.update(admin, id, null, null, false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("assign another default first");
    }

    @Test
    void deleteRefusesTheDefaultRow() {
        UUID id = UUID.randomUUID();
        when(typeRepository.findById(id))
                .thenReturn(Optional.of(CustomerType.create("Default", "default", true)));

        assertThatThrownBy(() -> service.delete(admin, id))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteCascadeClearsTheAssignments() {
        UUID id = UUID.randomUUID();
        when(typeRepository.findById(id))
                .thenReturn(Optional.of(CustomerType.create("Retired", "retired", false)));
        CustomerTypeAttribute pair = CustomerTypeAttribute.create(id, UUID.randomUUID(), 0);
        when(attributeRepository.findAllByCustomerTypeIdOrderByPositionAscIdAsc(id))
                .thenReturn(List.of(pair));

        service.delete(admin, id);

        verify(attributeRepository).delete(pair);
        verify(typeRepository).delete(any(CustomerType.class));
    }

    @Test
    void assignAttributesRejectsADuplicateInsideOneRequest() {
        UUID typeId = UUID.randomUUID();
        when(typeRepository.findById(typeId))
                .thenReturn(Optional.of(CustomerType.create("T", "t", false)));
        when(attributeRepository.findAllByCustomerTypeIdOrderByPositionAscIdAsc(typeId))
                .thenReturn(List.of());
        UUID attributeId = UUID.randomUUID();

        // The seen-set: [a, a] answers the classified conflict, never the
        // pair unique index's raw database error.
        assertThatThrownBy(() -> service.assignAttributes(admin, typeId,
                List.of(attributeId, attributeId)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void reorderAppliesDeterministicRemoveAndReinsert() {
        UUID typeId = UUID.randomUUID();
        when(typeRepository.findById(typeId))
                .thenReturn(Optional.of(CustomerType.create("T", "t", false)));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        CustomerTypeAttribute pa = CustomerTypeAttribute.create(typeId, a, 0);
        CustomerTypeAttribute pb = CustomerTypeAttribute.create(typeId, b, 1);
        CustomerTypeAttribute pc = CustomerTypeAttribute.create(typeId, c, 2);
        when(attributeRepository.findAllByCustomerTypeIdOrderByPositionAscIdAsc(typeId))
                .thenReturn(List.of(pa, pb, pc));

        // C moves up one slot: the expected dense order is A, C, B — a
        // SHIFTED position never ties with a sibling, so the UUID never
        // decides the order.
        service.reorderAttributes(admin, typeId, List.of(c), List.of(-1));

        assertThat(pa.getPosition()).isZero();
        assertThat(pc.getPosition()).isEqualTo(1);
        assertThat(pb.getPosition()).isEqualTo(2);
    }

    @Test
    void reorderRefusesAnUnassignedAttribute() {
        UUID typeId = UUID.randomUUID();
        when(typeRepository.findById(typeId))
                .thenReturn(Optional.of(CustomerType.create("T", "t", false)));
        when(attributeRepository.findAllByCustomerTypeIdOrderByPositionAscIdAsc(typeId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.reorderAttributes(admin, typeId,
                List.of(UUID.randomUUID()), List.of(1)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
