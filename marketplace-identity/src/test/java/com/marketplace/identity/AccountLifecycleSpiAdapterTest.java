package com.marketplace.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the CodeRabbit round on PR #696): the lifecycle adapter's
 * delegation guards — most importantly the {@code accountUpdate} leg's
 * transactional hand-off (B9: the adapter delegates to the SERVICE's own
 * write method, never the cached/detached read's mutated copy).
 */
@ExtendWith(MockitoExtension.class)
class AccountLifecycleSpiAdapterTest {

    @Mock
    private PasswordResetService passwordResetService;
    @Mock
    private EmailVerificationService emailVerificationService;
    @Mock
    private AccountEmailChangeService emailChangeService;
    @Mock
    private AccountSelfServiceTokenService selfServiceTokenService;
    @Mock
    private UserService userService;
    @Mock
    private TokenRevocationService tokenRevocationService;

    private AccountLifecycleSpiAdapter adapter;

    private Authentication caller;

    @BeforeEach
    void setUp() {
        adapter = new AccountLifecycleSpiAdapter(passwordResetService, emailVerificationService,
                emailChangeService, selfServiceTokenService, userService, tokenRevocationService);
        caller = new UsernamePasswordAuthenticationToken("member@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
    }

    @Test
    void updateOwnProfileDelegatesToTheTransactionalWrite() {
        // B9: the adapter hands the subject + display name to UserService's
        // OWN write method — the previous one-liner mutated a cached,
        // detached copy whose changes never reached the store.
        adapter.updateOwnProfile(caller, "New Display");

        verify(userService).updateOwnProfileBySubject("member@example.com", "New Display");
    }

    @Test
    void registerDelegatesToTheStandingFlow() {
        when(userService.register("new@example.com", "password123", "Newbie"))
                .thenReturn(User.create("new@example.com", "new@example.com", "Newbie",
                        UserRole.CONSUMER));

        adapter.register("new@example.com", "password123", "Newbie");

        verify(userService).register("new@example.com", "password123", "Newbie");
    }

    @Test
    void theRedemptionLegsDelegateToTheirOwners() {
        adapter.confirmEmailChange("change-token");
        verify(emailChangeService).confirmChange("change-token");

        adapter.requestPasswordReset("member@example.com");
        verify(passwordResetService).requestReset("member@example.com");

        adapter.confirmAccount("member@example.com", "verify-token");
        verify(emailVerificationService).completeVerification("verify-token");

        adapter.completePasswordReset("member@example.com", "reset-token", "new-password");
        verify(passwordResetService).completeReset("reset-token", "new-password");
    }

    @Test
    void theTokenLegsDelegateToTheirOwners() {
        adapter.deactivateAllTokens(caller);
        verify(tokenRevocationService).deactivateAllFor(caller);

        adapter.deleteAccountByToken("deletion-token");
        verify(selfServiceTokenService).deleteByToken("deletion-token");

        adapter.requestAccountDeletion(caller);
        verify(selfServiceTokenService).requestDeletion(caller);
    }
}
