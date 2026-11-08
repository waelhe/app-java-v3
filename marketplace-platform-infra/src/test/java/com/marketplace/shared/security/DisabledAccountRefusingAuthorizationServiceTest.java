package com.marketplace.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.JdbcUserDetailsManager;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The 2026-11-08 #810 round-5 review (Major · Security & Privacy): the
 * account-state wall at the authorization store — every user-principal
 * save re-verifies the login row's ENABLED truth (the same manager the
 * authentication chain reads), and a disabled or absent login row refuses
 * the persistence with the SAME DisabledException the password grant's
 * raced-row refusal carries. The client_credentials grant is exempt (its
 * principal is the CLIENT id — no auth_users row exists for it by design).
 */
class DisabledAccountRefusingAuthorizationServiceTest {

    private final OAuth2AuthorizationService delegate = mock(OAuth2AuthorizationService.class);
    private final JdbcUserDetailsManager userDetailsManager = mock(JdbcUserDetailsManager.class);
    private final DisabledAccountRefusingAuthorizationService service =
            new DisabledAccountRefusingAuthorizationService(delegate, userDetailsManager);

    private static OAuth2Authorization authorization(AuthorizationGrantType grantType,
                                                     String principal) {
        RegisteredClient client = RegisteredClient.withId("client-id")
                .clientId("client")
                .authorizationGrantType(grantType)
                .redirectUri("https://client.example.com/callback")
                .scope("openid")
                .build();
        return OAuth2Authorization.withRegisteredClient(client)
                .principalName(principal)
                .authorizationGrantType(grantType)
                .authorizedScopes(Set.of("openid"))
                .attributes(attrs -> attrs.put("state", "state"))
                .build();
    }

    private static UserDetails loginRow(boolean enabled) {
        org.springframework.security.core.userdetails.User.UserBuilder builder =
                User.withUsername("member@example.com")
                .password("{bcrypt}hash")
                .authorities(Collections.<GrantedAuthority>emptyList());
        return enabled ? builder.build() : builder.disabled(true).build();
    }

    @Test
    void saveDelegatesForAnEnabledLoginAccount() {
        when(userDetailsManager.loadUserByUsername("member@example.com"))
                .thenReturn(loginRow(true));
        OAuth2Authorization authorization =
                authorization(AuthorizationGrantType.AUTHORIZATION_CODE, "member@example.com");

        service.save(authorization);

        verify(delegate).save(authorization);
    }

    @Test
    void saveRefusesADisabledLoginAccountWithTheGrantVocabulary() {
        when(userDetailsManager.loadUserByUsername("member@example.com"))
                .thenReturn(loginRow(false));
        OAuth2Authorization authorization =
                authorization(AuthorizationGrantType.AUTHORIZATION_CODE, "member@example.com");

        assertThatThrownBy(() -> service.save(authorization))
                .isInstanceOf(DisabledException.class)
                .hasMessage("User is disabled");

        verify(delegate, never()).save(any(OAuth2Authorization.class));
    }

    @Test
    void saveRefusesAnAbsentLoginRowNothingMayBeGrantedForIt() {
        // loginAccountEnabled's own contract: a subject with no login row
        // answers false — nothing may be granted for it.
        when(userDetailsManager.loadUserByUsername("stranger@example.com"))
                .thenThrow(new org.springframework.security.core.userdetails
                        .UsernameNotFoundException("stranger@example.com"));
        OAuth2Authorization authorization =
                authorization(new AuthorizationGrantType("password"), "stranger@example.com");

        assertThatThrownBy(() -> service.save(authorization))
                .isInstanceOf(DisabledException.class);

        verify(delegate, never()).save(any(OAuth2Authorization.class));
    }

    @Test
    void saveDelegatesClientCredentialsWithoutTouchingTheLoginStore() {
        // The client-credentials principal is the CLIENT, not a user
        // subject — no auth_users row exists for it by design.
        OAuth2Authorization authorization =
                authorization(AuthorizationGrantType.CLIENT_CREDENTIALS, "the-client");

        service.save(authorization);

        verify(delegate).save(authorization);
        verifyNoInteractions(userDetailsManager);
    }

    @Test
    void theReadAndRemoveLegsPassThroughUntouched() {
        OAuth2Authorization authorization =
                authorization(AuthorizationGrantType.AUTHORIZATION_CODE, "member@example.com");

        service.remove(authorization);
        service.findById("id");
        service.findByToken("token", null);

        verify(delegate).remove(authorization);
        verify(delegate).findById("id");
        verify(delegate).findByToken("token", null);
        verifyNoInteractions(userDetailsManager);
    }
}
