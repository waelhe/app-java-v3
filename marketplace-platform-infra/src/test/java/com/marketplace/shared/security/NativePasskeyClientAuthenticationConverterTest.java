package com.marketplace.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativePasskeyClientAuthenticationConverterTest {

    private final NativePasskeyClientAuthenticationConverter converter =
            new NativePasskeyClientAuthenticationConverter(
                    AuthorizationServerSettings.builder().tokenEndpoint("/oauth2/token").build());

    @Test
    void leavesBuiltInOAuthGrantsForSpringSecurityConverters() {
        MockHttpServletRequest request = request("authorization_code");
        request.setParameter(OAuth2ParameterNames.CLIENT_ID, "android-community-native");

        assertThat(this.converter.convert(request)).isNull();
    }

    @Test
    void convertsOnlyTheSecretlessPasskeyClientIdentity() {
        MockHttpServletRequest request = request(NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue());
        request.setParameter(OAuth2ParameterNames.CLIENT_ID, "android-community-native");

        OAuth2ClientAuthenticationToken authentication =
                (OAuth2ClientAuthenticationToken) this.converter.convert(request);

        assertThat(authentication.getPrincipal()).isEqualTo("android-community-native");
        assertThat(authentication.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getAdditionalParameters())
                .containsEntry(NativePasskeyClientAuthenticationConverter.CLIENT_AUTHENTICATION_MARKER, true);
        assertThat(authentication.isAuthenticated()).isFalse();
    }

    @Test
    void rejectsMissingAndRepeatedClientIdsForThePasskeyGrant() {
        MockHttpServletRequest missing = request(NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue());
        assertThatThrownBy(() -> this.converter.convert(missing))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_request"));

        MockHttpServletRequest duplicate = request(NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue());
        duplicate.setParameter(OAuth2ParameterNames.CLIENT_ID, "android-community-native");
        duplicate.addParameter(OAuth2ParameterNames.CLIENT_ID, "other-client");
        assertThatThrownBy(() -> this.converter.convert(duplicate))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_request"));
    }

    @Test
    void rejectsSecretsForThePublicPasskeyClient() {
        MockHttpServletRequest request = request(NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue());
        request.setParameter(OAuth2ParameterNames.CLIENT_ID, "android-community-native");
        request.setParameter(OAuth2ParameterNames.CLIENT_SECRET, "must-not-be-sent");

        assertThatThrownBy(() -> this.converter.convert(request))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_client"));
    }

    @Test
    void doesNotAuthenticateOutsideTheConfiguredTokenEndpoint() {
        MockHttpServletRequest request = request(NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue());
        request.setServletPath("/oauth2/revoke");
        request.setParameter(OAuth2ParameterNames.CLIENT_ID, "android-community-native");

        assertThat(this.converter.convert(request)).isNull();
    }

    private static MockHttpServletRequest request(String grantType) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setServletPath("/oauth2/token");
        request.setParameter(OAuth2ParameterNames.GRANT_TYPE, grantType);
        return request;
    }
}
