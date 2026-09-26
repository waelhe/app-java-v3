package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminUserInitializerTest {

    private final UserDetailsManager userDetailsManager = mock(UserDetailsManager.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    @Test
    void failsFastWhenPasswordBlankInProductionProfile() {
        AdminUserInitializer initializer =
                new AdminUserInitializer(properties(""), userDetailsManager, passwordEncoder, environment(true));

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin-seed.password must be configured in production")
                .hasMessageContaining("ADMIN_SEED_PASSWORD")
                .hasMessageContaining("N1");
    }

    @Test
    void isADesignedNoOpWhenPasswordBlankOutsideProduction() {
        // The client initializer's contract: blank outside prod = deliberate
        // no-op — never a query. Test-profile contexts (Flyway disabled,
        // JPA-only create-drop schemas) have no auth_* tables at all.
        new AdminUserInitializer(properties(""), userDetailsManager, passwordEncoder, environment(false))
                .run(null);

        verify(userDetailsManager, never()).userExists(any());
        verify(userDetailsManager, never()).createUser(any());
        verify(userDetailsManager, never()).updateUser(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void bootstrapsAdminFromEnvironmentWhenAbsent() {
        when(userDetailsManager.userExists("admin")).thenReturn(false);
        when(passwordEncoder.encode("raw-secret")).thenReturn("encoded");

        new AdminUserInitializer(properties("raw-secret"), userDetailsManager, passwordEncoder, environment(false))
                .run(null);

        assertThat(createdUserArgument().getPassword()).isEqualTo("encoded");
        verify(userDetailsManager, never()).updateUser(any());
    }

    @Test
    void convergesRotatedPasswordThroughTheOfficialUpdatePath() {
        UserDetails existing = User.withUsername("admin")
                .password("old-encoded")
                .roles("ADMIN")
                .build();
        when(userDetailsManager.userExists("admin")).thenReturn(true);
        when(userDetailsManager.loadUserByUsername("admin")).thenReturn(existing);
        when(passwordEncoder.matches("raw-secret", "old-encoded")).thenReturn(false);
        when(passwordEncoder.encode("raw-secret")).thenReturn("new-encoded");

        new AdminUserInitializer(properties("raw-secret"), userDetailsManager, passwordEncoder, environment(false))
                .run(null);

        UserDetails updated = updatedUserArgument();
        assertThat(updated.getPassword()).isEqualTo("new-encoded");
        assertThat(updated.getAuthorities()).map(Object::toString).containsExactly("ROLE_ADMIN");
        assertThat(updated.isEnabled()).isTrue();
    }

    @Test
    void isIdempotentWhenRowAlreadyMatchesTheDerivedDefinition() {
        UserDetails existing = User.withUsername("admin")
                .password("encoded")
                .roles("ADMIN")
                .build();
        when(userDetailsManager.userExists("admin")).thenReturn(true);
        when(userDetailsManager.loadUserByUsername("admin")).thenReturn(existing);
        when(passwordEncoder.matches("raw-secret", "encoded")).thenReturn(true);

        new AdminUserInitializer(properties("raw-secret"), userDetailsManager, passwordEncoder, environment(true))
                .run(null);

        verify(userDetailsManager, never()).createUser(any());
        verify(userDetailsManager, never()).updateUser(any());
    }

    @Test
    void convergesWhenExtraAuthoritiesDriftedOntoTheBreakGlassRow() {
        // The break-glass row is environment-owned: exactly ROLE_ADMIN. A drifted
        // authority set (e.g. hand-edited) is converged back at the next boot.
        UserDetails drifted = new User("admin", "encoded", List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
        when(userDetailsManager.userExists("admin")).thenReturn(true);
        when(userDetailsManager.loadUserByUsername("admin")).thenReturn(drifted);
        when(passwordEncoder.matches("raw-secret", "encoded")).thenReturn(true);

        new AdminUserInitializer(properties("raw-secret"), userDetailsManager, passwordEncoder, environment(false))
                .run(null);

        assertThat(updatedUserArgument().getAuthorities())
                .map(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void convergesAfterConcurrentReplicaWonTheBootstrapInsert() {
        // CodeRabbit #241 pattern (the client initializer's concurrent-replica race):
        // both replicas see no row, the loser's createUser violates the unique
        // username index, and the loser converges onto the winner's row.
        when(userDetailsManager.userExists("admin"))
                .thenReturn(false)
                .thenReturn(true);
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(userDetailsManager).createUser(any());
        UserDetails winnerRow = User.withUsername("admin")
                .password("winner-encoded")
                .roles("ADMIN")
                .build();
        when(userDetailsManager.loadUserByUsername("admin")).thenReturn(winnerRow);
        when(passwordEncoder.matches("raw-secret", "winner-encoded")).thenReturn(true);

        new AdminUserInitializer(properties("raw-secret"), userDetailsManager, passwordEncoder, environment(false))
                .run(null);

        verify(userDetailsManager, never()).updateUser(any());
    }

    @Test
    void rethrowsIntegrityFailureThatIsNotTheConcurrentBootstrapRace() {
        when(userDetailsManager.userExists("admin"))
                .thenReturn(false)
                .thenReturn(false);
        doThrow(new DataIntegrityViolationException("constraint violation: something else"))
                .when(userDetailsManager).createUser(any());

        AdminUserInitializer initializer = new AdminUserInitializer(
                properties("raw-secret"), userDetailsManager, passwordEncoder, environment(false));

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("something else");
    }

    private UserDetails createdUserArgument() {
        org.mockito.ArgumentCaptor<UserDetails> captor =
                org.mockito.ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).createUser(captor.capture());
        return captor.getValue();
    }

    private UserDetails updatedUserArgument() {
        org.mockito.ArgumentCaptor<UserDetails> captor =
                org.mockito.ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).updateUser(captor.capture());
        return captor.getValue();
    }

    private static MarketplaceProperties properties(String adminSeedPassword) {
        return new MarketplaceProperties(
                null,
                new MarketplaceProperties.Security(
                        null,
                        null,
                        null,
                        null,
                        new MarketplaceProperties.Security.AdminSeed(adminSeedPassword)));
    }

    private static Environment environment(boolean prod) {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(Profiles.of("prod"))).thenReturn(prod);
        return environment;
    }
}
