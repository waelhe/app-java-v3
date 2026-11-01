package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the Saleor 458-root campaign, PR #696): the staff notification
 * recipient registry's owning guards — the V249 oneof pair's validation on
 * every write path, the live-row uniqueness conflicts, and the ADMIN wall.
 */
@ExtendWith(MockitoExtension.class)
class StaffNotificationRecipientServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "ops@example.com";

    @Mock
    private StaffNotificationRecipientRepository repository;
    @Mock
    private UserRepository userRepository;

    private StaffNotificationRecipientService service;

    private Authentication admin;
    private Authentication consumer;

    @BeforeEach
    void setUp() {
        service = new StaffNotificationRecipientService(repository, userRepository);
        admin = new UsernamePasswordAuthenticationToken("admin@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        consumer = new UsernamePasswordAuthenticationToken("member@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
    }

    @Test
    void createForAUserRequiresTheAccountAndAFreeRow() {
        when(userRepository.existsById(USER_ID)).thenReturn(true);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        var view = service.create(admin, USER_ID, null, true);

        assertThat(view.userId()).isEqualTo(USER_ID);
        assertThat(view.active()).isTrue();
        verify(repository).save(any(StaffNotificationRecipient.class));
    }

    @Test
    void createForAUserRefusesTheDuplicateRow() {
        when(userRepository.existsById(USER_ID)).thenReturn(true);
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(StaffNotificationRecipient.forUser(USER_ID, true)));

        assertThatThrownBy(() -> service.create(admin, USER_ID, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has a notification recipient row");
    }

    @Test
    void createForAnEmailValidatesTheShapeAndUniqueness() {
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        var view = service.create(admin, null, "  ops@example.com ", null);

        assertThat(view.email()).isEqualTo(EMAIL);
        assertThat(view.active()).isTrue();
    }

    @Test
    void createForAnEmailRefusesAMalformedAddress() {
        assertThatThrownBy(() -> service.create(admin, null, "not-an-email", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid email address");
    }

    @Test
    void createRefusesTheEmptyOneofPair() {
        assertThatThrownBy(() -> service.create(admin, null, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one of");
    }

    @Test
    void createForAnUnknownUserRefuses() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.create(admin, USER_ID, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateMovesThePairWithTheSelfExclusion() {
        UUID id = UUID.randomUUID();
        StaffNotificationRecipient row = StaffNotificationRecipient.forEmail(EMAIL, true);
        when(repository.findById(id)).thenReturn(Optional.of(row));
        when(userRepository.existsById(USER_ID)).thenReturn(true);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        var view = service.update(admin, id, USER_ID, null, false);

        assertThat(view.userId()).isEqualTo(USER_ID);
        assertThat(view.active()).isFalse();
    }

    @Test
    void updateRefusesTheBothFilledPair() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id))
                .thenReturn(Optional.of(StaffNotificationRecipient.forEmail(EMAIL, true)));

        assertThatThrownBy(() -> service.update(admin, id, USER_ID, EMAIL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void updateRefusesAnotherRowsAddress() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id))
                .thenReturn(Optional.of(StaffNotificationRecipient.forUser(USER_ID, true)));
        when(repository.findByEmail(EMAIL))
                .thenReturn(Optional.of(StaffNotificationRecipient.forEmail(EMAIL, true)));

        assertThatThrownBy(() -> service.update(admin, id, null, EMAIL, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteRemovesTheRowAndAnswersTheView() {
        UUID id = UUID.randomUUID();
        StaffNotificationRecipient row = StaffNotificationRecipient.forEmail(EMAIL, true);
        when(repository.findById(id)).thenReturn(Optional.of(row));

        var view = service.delete(admin, id);

        verify(repository).delete(row);
        assertThat(view.email()).isEqualTo(EMAIL);
    }

    @Test
    void theReadLegsAnswerTheRegistry() {
        UUID id = UUID.randomUUID();
        StaffNotificationRecipient row = StaffNotificationRecipient.forEmail(EMAIL, false);
        when(repository.findById(id)).thenReturn(Optional.of(row));
        when(repository.findAllByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(row));

        assertThat(service.find(admin, id)).isPresent();
        assertThat(service.list(admin)).hasSize(1);
    }

    @Test
    void everyLegIsAdminGated() {
        assertThatThrownBy(() -> service.create(consumer, USER_ID, null, null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(consumer))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
