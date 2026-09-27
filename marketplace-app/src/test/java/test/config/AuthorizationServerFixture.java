package test.config;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.UUID;

/**
 * The login-gate OAuth2 client registration shared by the authorization
 * server's integration tests — one owner of the registration constants.
 *
 * <p>Five integration-test classes previously declared the same five
 * constants and the same {@code RegisteredClient} builder (measured
 * 2026-09-28 on {@code 0a950ca}: the five bodies byte-identical except the
 * descriptive {@code clientName} string). Spring Authorization Server's
 * testing guidance keeps the client-registration constants in one place —
 * five drifting copies is exactly the risk the drift measured ("authorize
 * path 7, token path 8" mentions across five files): a class that quietly
 * diverges (a path, a secret, a setting) passes its own flows while the
 * suite silently stops testing the same client.
 *
 * <p>The registration is idempotent: re-registering the same client id is
 * a no-op, exactly like the five inlined copies were (each class calls it
 * from its own setup against its own isolated database container — the
 * #382 invariant; the guard is {@code findByClientId} first).
 *
 * <p>Users are NOT part of this fixture: the five classes register
 * different user fixtures (only the login-gate client is the shared
 * contract), so user seeding stays in each class.
 */
public final class AuthorizationServerFixture {

    /** The login-gate client id every gate integration test registers. */
    public static final String CLIENT_ID = "it-login-gate-client";

    /** The login-gate client secret (test-only, {noop} encoded). */
    public static final String CLIENT_SECRET = "it-login-gate-secret";

    /** The login-gate redirect URI (RFC 2606 {@code .test.example} domain). */
    public static final String REDIRECT_URI = "https://login-gate.test.example/callback";

    /** The authorization endpoint path (Spring Authorization Server default). */
    public static final String AUTHORIZE_PATH = "/oauth2/authorize";

    /** The token endpoint path (Spring Authorization Server default). */
    public static final String TOKEN_PATH = "/oauth2/token";

    private AuthorizationServerFixture() {
    }

    /**
     * Registers the login-gate client if the repository does not already
     * carry it — the same idempotent builder the five classes duplicated:
     * basic authentication, authorization-code + refresh-token grants,
     * PKCE required, consent not required (the consent flows opt in from
     * the bootstrap client), {@code openid} scope.
     *
     * @param repository the authorization server's client registry (each
     *                   test class's own isolated database)
     * @param clientName the caller's descriptive registration name — the
     *                   one string the five copies legitimately differed
     *                   on, preserved per class
     */
    public static void registerLoginGateClient(RegisteredClientRepository repository, String clientName) {
        if (repository.findByClientId(CLIENT_ID) != null) {
            return;
        }
        RegisteredClient loginGateClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID)
                .clientSecret("{noop}" + CLIENT_SECRET)
                .clientName(clientName)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .build();
        repository.save(loginGateClient);
    }
}
