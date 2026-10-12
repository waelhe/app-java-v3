package com.marketplace.shared.security;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativePasskeyGrantAuthenticationConverterTest {

    private final NativePasskeyGrantAuthenticationConverter converter =
            new NativePasskeyGrantAuthenticationConverter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void leavesOtherGrantTypesForSpringAuthorizationServerConverters() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(OAuth2ParameterNames.GRANT_TYPE, OAuth2ParameterNames.AUTHORIZATION_CODE);
        assertThat(this.converter.convert(request)).isNull();
    }

    @Test
    void convertsOnlyTheSingleChallengeAndAssertionParameters() {
        Authentication clientPrincipal =
                new TestingAuthenticationToken("native-client", "not-a-secret", "ROLE_CLIENT");
        SecurityContextHolder.getContext().setAuthentication(clientPrincipal);

        MockHttpServletRequest request = validRequest();
        Authentication result = this.converter.convert(request);

        assertThat(result).isInstanceOf(NativePasskeyGrantAuthenticationToken.class);
        NativePasskeyGrantAuthenticationToken grant = (NativePasskeyGrantAuthenticationToken) result;
        assertThat(grant.getGrantType()).isEqualTo(NativePasskeyGrantAuthenticationToken.GRANT_TYPE);
        assertThat(grant.getChallengeId()).isEqualTo("47fc1bd0-4b40-4b0b-a35b-eebc05b6f920");
        assertThat(grant.getCredentialJson()).contains("\"type\":\"public-key\"");
        assertThat(grant.getPrincipal()).isSameAs(clientPrincipal);
    }

    @Test
    void rejectsMissingAndRepeatedSecurityParameters() {
        MockHttpServletRequest missing = new MockHttpServletRequest();
        missing.setParameter(OAuth2ParameterNames.GRANT_TYPE,
                NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue());
        missing.setParameter("challenge_id", "id");
        assertThatThrownBy(() -> this.converter.convert(missing))
                .isInstanceOf(OAuth2AuthenticationException.class);

        MockHttpServletRequest duplicate = validRequest();
        duplicate.addParameter("challenge_id", "second-value");
        assertThatThrownBy(() -> this.converter.convert(duplicate))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void rejectsOversizedAssertionBeforeWebAuthnParsing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(OAuth2ParameterNames.GRANT_TYPE,
                NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue());
        request.setParameter("challenge_id", "47fc1bd0-4b40-4b0b-a35b-eebc05b6f920");
        request.setParameter("credential", "x".repeat(32_769));

        assertThatThrownBy(() -> this.converter.convert(request))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    private static MockHttpServletRequest validRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setParameter(OAuth2ParameterNames.GRANT_TYPE,
                NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue());
        request.setParameter("challenge_id", "47fc1bd0-4b40-4b0b-a35b-eebc05b6f920");
        request.setParameter("credential",
                "{\"id\":\"abc\",\"type\":\"public-key\",\"response\":{\"clientDataJSON\":\"x\"}}");
        return request;
    }
}
