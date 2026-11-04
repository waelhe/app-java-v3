package com.marketplace.app.graphql;

import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.identity.spi.UserAccountView;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * platform's OWN password grant behind the Saleor {@code tokenCreate}/
 * {@code tokenRefresh}/{@code tokenVerify} mutations — the dashboard's
 * native sign-in, implemented over the platform's real primitives with
 * ZERO second issuer:
 *
 * <ul>
 *   <li><b>Credential verification:</b> the global {@link AuthenticationManager}
 *       (the framework-assembled DaoAuthenticationProvider the form-login
 *       chain itself uses — every account-state flag honored: disabled,
 *       locked and expired accounts all refuse exactly as the login gate
 *       does).</li>
 *   <li><b>Access-token minting:</b> the OFFICIAL {@link NimbusJwtEncoder}
 *       over the SAME {@code JWKSource} bean the authorization server signs
 *       with, carrying the SAME claim contract the {@code jwtTokenCustomizer}
 *       mints for OIDC access tokens (sub = login username, roles with the
 *       ROLE_ prefix stripped, the platform audience, the issuer from the
 *       authorization server's own settings) — the resource server's
 *       decoder validates them without any special case.</li>
 *   <li><b>Refresh tokens + persistence:</b> opaque cryptographically
 *       random strings persisted (with the access token and the roles
 *       attribute) as {@code oauth2_authorization} rows through the
 *       authorization server's own {@link OAuth2AuthorizationService} —
 *       so {@code tokenRefresh} rotates through the official
 *       {@code findByToken} API with ONE-TIME-USE semantics whose atomic
 *       boundary is the STORE ITSELF: the {@link RefreshTokenRotationStore}
 *       seam consumes the presented value with one conditional UPDATE on
 *       the shared row, arbitrated by the database's own row lock —
 *       valid across every application instance sharing the store (the
 *       multi-instance round's demand that no JVM lock ever stand in
 *       for a distributed one). {@code tokensDeactivateAll} (the identity owner's JDBC kill
 *       by principal) revokes them exactly like any OIDC-issued
 *       session — the rotated successor included, because the row the
 *       rotation kept is the row the kill deletes.</li>
 *   <li><b>Verification:</b> the resource server's own {@link JwtDecoder}
 *       — the same validator every API request runs.</li>
 * </ul>
 *
 * <p>The client of record for the grant's authorization rows is the
 * platform's existing public (PKCE) client — the dashboard's own client —
 * resolved through the {@code RegisteredClientRepository} (the grant rides
 * the client registry's own row, never an invented client).</p>
 */
@Component
public class PlatformTokenGrantService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTokenGrantService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** The platform's standing access-token TTL (the L23-documented 900s). */
    static final Duration ACCESS_TOKEN_TTL = Duration.ofSeconds(900);

    /** The refresh-token TTL — 14 days, the platform's refresh posture. */
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);

    /** The roles attribute's key on the authorization rows. */
    private static final String ROLES_ATTRIBUTE = "platform.roles";

    /**
     * The fixed input of the timing-equalization dummy comparison (the
     * OWASP Authentication Cheat Sheet pattern — a constant, never
     * caller data). Package-visible for the grant suite's verification.
     */
    static final String DUMMY_COMPARISON_SECRET = "platform.timing-equalization.dummy-secret";

    /**
     * The shard review (Major · Security & Privacy, 2026-11-01 wave 2;
     * representation hardened in wave 3, CORRECTED in wave 4): the
     * family's OWN birth time on the authorization row — every rotation
     * re-issues the refresh token (now + TTL), so the token's issuedAt
     * can never bound the family's age; the attribute does.
     *
     * <p><b>Representation — String of epoch millis (the wave-4 CI
     * evidence):</b> wave 3 recorded the value as a {@link Long} and
     * asserted the scalar "unconditionally safe across every
     * Jackson/PTV configuration". The platform's own CI falsified that
     * claim: {@code JdbcOAuth2AuthorizationService}'s Jackson 3 row
     * mapper deserializes the attributes map through a
     * {@code BasicPolymorphicTypeValidator} that DENIES the
     * {@code java.lang.Long} type id the write side embeds, so every
     * read of such a row failed with
     * {@code Could not resolve type id 'java.lang.Long'} (7 red tests
     * across the shard matrix and the Saleor Backend Compatibility
     * Gate, 2026-11-01). The official contract for the authorization
     * store's JSON metadata is Jackson's own: only the PTV-allowlisted
     * subtypes round-trip, and plain {@link String} values are stored
     * WITHOUT any type id at all — the representation every
     * configuration accepts. The read side honors all three recorded
     * shapes (String, Long, Instant) so no live row ever breaks.</p>
     *
     * <p>A legacy row (pre-attribute) is seeded from the presented
     * token's issuedAt at its next rotation — the conservative floor
     * (the family is at least as old as the presented token).</p>
     */
    private static final String FAMILY_CREATED_ATTRIBUTE = "platform.family.created";

    // The #392 module-slice shape (the UserService/PasswordResetService
    // precedent verbatim): the security-infrastructure beans live outside
    // the GraphQL adapter slice — resolved lazily so every package-slice
    // test loads the adapter without the full authorization-server stack,
    // and the full application (and every integration test) resolves them.
    private final ObjectProvider<AuthenticationConfiguration> authenticationConfiguration;
    private final ObjectProvider<OAuth2AuthorizationService> authorizationService;
    private final ObjectProvider<RefreshTokenRotationStore> refreshTokenRotationStore;
    private final ObjectProvider<RegisteredClientRepository> registeredClientRepository;
    private final ObjectProvider<JWKSource<SecurityContext>> jwkSource;
    private final ObjectProvider<AuthorizationServerSettings> authorizationServerSettings;
    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final ObjectProvider<PasswordEncoder> passwordEncoder;
    private final String dummyBcrypt;
    private final IdentitySpi identitySpi;
    private final String audience;
    private final String publicClientId;
    private final java.time.Duration refreshFamilyMaxAge;

    public PlatformTokenGrantService(
            ObjectProvider<AuthenticationConfiguration> authenticationConfiguration,
            ObjectProvider<OAuth2AuthorizationService> authorizationService,
            ObjectProvider<RefreshTokenRotationStore> refreshTokenRotationStore,
            ObjectProvider<RegisteredClientRepository> registeredClientRepository,
            ObjectProvider<JWKSource<SecurityContext>> jwkSource,
            ObjectProvider<AuthorizationServerSettings> authorizationServerSettings,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<PasswordEncoder> passwordEncoder,
            IdentitySpi identitySpi,
            @Value("${marketplace.security.jwt.audience:marketplace-api}") String audience,
            @Value("${marketplace.security.oauth2.public-client.client-id:"
                    + "10b588c6-4e85-43ec-9ecf-c588676774d7}") String publicClientId,
            @Value("${marketplace.security.token.refresh-family-max-age-seconds:2592000}")
            long refreshFamilyMaxAgeSeconds) {
        this.authenticationConfiguration = authenticationConfiguration;
        this.authorizationService = authorizationService;
        this.refreshTokenRotationStore = refreshTokenRotationStore;
        this.registeredClientRepository = registeredClientRepository;
        this.jwkSource = jwkSource;
        this.authorizationServerSettings = authorizationServerSettings;
        this.jwtDecoder = jwtDecoder;
        this.passwordEncoder = passwordEncoder;
        // The 2026-11-04 review's timing equalization (see grant()): ONE
        // bcrypt hash over the FIXED constant, computed once here at bean
        // init — never per-request, never from caller input. Null only in
        // package-slice contexts without the security stack, where no
        // grant ever runs.
        PasswordEncoder encoder = passwordEncoder.getIfAvailable();
        this.dummyBcrypt = encoder == null ? null : encoder.encode(DUMMY_COMPARISON_SECRET);
        this.identitySpi = identitySpi;
        this.audience = audience;
        this.publicClientId = publicClientId;
        // The absolute session bound (default 30d = 2× the 14d refresh
        // TTL): a family kept alive by rotation forever is the reviewer's
        // exact finding — the cap forces a fresh password grant.
        this.refreshFamilyMaxAge = java.time.Duration.ofSeconds(refreshFamilyMaxAgeSeconds);
    }

    /** The issued pair + the caller's own account projection. */
    public record IssuedTokens(String accessToken, String refreshToken, String csrfToken,
                               UserAccountView user) {
    }

    /** The verification outcome (the VerifyToken payload's own shape). */
    public record VerifiedToken(boolean valid, Map<String, Object> claims, String subject) {
    }

    /**
     * The password grant: authenticate, mint the pair, persist the
     * authorization row against the public (dashboard) client.
     *
     * @throws AuthenticationException the provider's own semantics (bad
     *                                 credentials, disabled/locked account)
     */
    @Observed(name = "graphql.token.create")
    public IssuedTokens grant(String email, String password) {
        Authentication authentication;
        try {
            authentication = authenticationManager().authenticate(
                    new UsernamePasswordAuthenticationToken(email, password));
        } catch (DisabledException | LockedException | AccountExpiredException
                | CredentialsExpiredException accountState) {
            // The 2026-11-04 adversarial delta review (MAJOR — timing
            // oracle): the account-state family fails the provider's
            // PRE-authentication checks, so the disabled leg costs ~0
            // bcrypt while unknown-email/wrong-password cost ~1 — a
            // measurable account-status oracle on the sign-in surface.
            // The OWASP mitigation (Authentication Cheat Sheet): burn ONE
            // dummy comparison against the constructor-cached bcrypt hash
            // so every rejection costs the same, then rethrow the SAME
            // failure (the grant flow itself is untouched).
            burnDummyPasswordComparison();
            throw accountState;
        }
        String principal = authentication.getName();
        Set<String> roles = stripRolePrefix(authentication);
        Instant now = Instant.now();
        String accessToken = mintAccessToken(principal, roles, now);
        String refreshToken = opaqueToken();
        authorizationService().save(authorizationRow(principal, roles, accessToken,
                refreshToken, now));
        // m7 (the CodeRabbit correctness round): the account projection read
        // needs the SAME subject-shaped authentication the refresh leg
        // passes — the DaoAuthenticationProvider's own Authentication
        // carries a UserDetails principal, and UserService.jwtSubjectOf
        // only resolves a JwtAuthenticationToken or a String principal, so
        // the provider-shaped token answered an empty account and
        // CreateToken.user was always null.
        UserAccountView user = identitySpi.findCurrentUserAccount(
                        new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()))
                .orElse(null);
        // The shard review (Minor · Security & Privacy, 2026-11-01): the
        // principal is the caller-supplied email — never INFO-log it (PII
        // leakage into logs); the internal account id identifies the grant.
        log.info("Platform password grant issued: userId={}", user == null ? null : user.id());
        // The shard review (Minor · Functional Correctness, 2026-11-01
        // wave 2): the csrfToken was a random value NO path verifies —
        // tokenRefresh REJECTS any non-blank csrfToken (the platform's
        // own classified message), so a Saleor-protocol client returning
        // it was punished for following the protocol. The honest answer
        // is null: this surface does not implement CSRF proof (the
        // nullable SDL field).
        return new IssuedTokens(accessToken, refreshToken, null, user);
    }

    /**
     * The refresh rotation — the official one-time-use semantics, the
     * {@code OAuth2RefreshTokenAuthenticationProvider} pattern on the
     * platform's own primitives: finds the authorization row by the
     * refresh token (the official {@code findByToken} API), requires
     * the token to be ACTIVE (the official {@code Token#isActive()} —
     * invalidated, expired and before-use all refuse), mints the FRESH
     * pair with the SAME principal/roles claims, and saves the SAME
     * row riding the NEW pair. The new refresh token value REPLACES
     * the old one on save (the Jdbc service's update-by-id), which IS
     * the one-time-use invalidation: {@code findByToken(old)} answers
     * {@code null} ever after — a replayed or concurrently double-spent
     * token is rejected exactly like an unknown one, and
     * {@code tokensDeactivateAll} kills the successor because the row
     * it deletes is the row the rotation kept.
     *
     * <p>No JVM lock participates in the guarantee — the store itself
     * arbitrates (the {@link RefreshTokenRotationStore} seam), so the
     * one-time-use contract holds across any number of application
     * instances sharing the authorization store; the instance-local
     * stripe locks this method used to carry were removed with it.</p>
     */
    @Observed(name = "graphql.token.refresh")
    public IssuedTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "A refresh token is required");
        }
        OAuth2Authorization authorization = authorizationService().findByToken(
                refreshToken, new OAuth2TokenType(OAuth2TokenType.REFRESH_TOKEN.getValue()));
        if (authorization == null
                || authorization.getRefreshToken() == null
                || !authorization.getRefreshToken().isActive()) {
            // A LATE replay of a consumed value dies HERE, never reaching
            // the rotation's consumption boundary: the completed rotation
            // already REPLACED the presented value in the shared row, so
            // the replayed value cannot resolve (findByToken answers
            // null). That is exactly the theft shape the OAuth 2.0
            // Security BCP's refresh-token rotation (RFC 9700 §2.4.2)
            // reuse detection exists for — an attacker who rotated first
            // leaves the legitimate client failing silently while the
            // attacker's successor stayed valid. Before the plain
            // rejection, the family ledger is consulted: a value
            // recognized as consumed BEYOND the grace window revokes the
            // ENTIRE family. The rejection message is IDENTICAL either
            // way — the response never reveals whether the ledger
            // recognized the value (the in-window concurrent
            // double-spend and the unknown token keep their recorded
            // plain rejection; the store's grace window arbitrates).
            if (authorization == null) {
                refreshTokenRotationStore().revokeFamilyIfConsumed(refreshToken);
            }
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "The refresh token is invalid, expired or revoked");
        }
        // Row 25 (agent findings 2026-10-26 14:33 export, CWE-287): the
        // authorization must belong to THIS surface's own public client.
        // The pre-fix flow accepted ANY active refresh token in the shared
        // store — a token issued to a confidential client through the
        // OAuth token endpoint could be replayed here to mint a platform
        // access token carrying the roles attribute without that client's
        // own client-authentication wall. The official partition: each
        // client refreshes through ITS channel — this GraphQL surface is
        // the configured public client's channel alone (the
        // authorization-code + refresh grant origins it issues); every
        // other client's tokens answer at the OAuth token endpoint, where
        // the client's own credentials are verified.
        // The SAS storage contract: the authorization's registeredClientId
        // is the RegisteredClient's INTERNAL id — resolved through the
        // repository's own findById (the official path), then compared by
        // the client id THIS surface was configured with.
        String authorizationClient = authorization.getRegisteredClientId();
        boolean ownedByThisClient = false;
        if (authorizationClient != null) {
            RegisteredClient owner = registeredClientRepository().findById(authorizationClient);
            ownedByThisClient = owner != null && publicClientId.equals(owner.getClientId());
        }
        if (!ownedByThisClient) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "The refresh token does not belong to this surface's client");
        }
        String principal = authorization.getPrincipalName();
        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) authorization.getAttributes()
                .getOrDefault(ROLES_ATTRIBUTE, Set.of());
        Instant now = Instant.now();
        // The shard review (Major · Security & Privacy, 2026-11-01 wave 2):
        // the ABSOLUTE family-age cap. Each rotation re-issues the refresh
        // token (now + TTL) so the token's own issuedAt can never bound
        // the family — without this cap a session kept alive by rotation
        // NEVER expires. The family's birth time rides the authorization
        // row's attributes (written at grant); a legacy row predating the
        // attribute is bounded from the presented token's issuedAt (the
        // conservative floor) and seeded onto the row by THIS rotation,
        // so every legacy family converges under the cap within one
        // refresh. (The account-state side of the finding is the identity
        // owner's own recorded contract: account disable/email change/
        // self-deletion and role revoke DELETE the principal's
        // oauth2_authorization rows in the same transaction — UserService
        // and RoleAssignmentService's own DELETE statements — so a
        // disabled account cannot refresh; NO parallel per-refresh
        // account read is added here, per the reviewer's own guidance.
        // B26/ORD-07 — the 2026-11-03 adversarial review, review-pr-811 —
        // the CONCURRENT interleaving rides the same arbitration: a
        // deactivation committing between this method's findByToken leg
        // and the rotation's atomic conditional UPDATE leaves the UPDATE
        // matching NO row — the refusal precedes the mint and the fresh
        // pair never reaches the caller (the sequencing proof:
        // aDeactivationCommittingAtTheRotationBoundaryRefusesTheInFlightRefresh).
        // The residual window is the stateless access token's own bounded
        // lifetime — a pair minted BEFORE the deactivation's commit stays
        // valid until its natural expiry, the documented 900s contract,
        // never extended by the race.)
        Instant familyCreated = familyCreatedAt(authorization);
        Instant familyStart = familyCreated != null ? familyCreated
                : authorization.getRefreshToken().getToken().getIssuedAt();
        if (familyStart != null
                && Duration.between(familyStart, now).compareTo(refreshFamilyMaxAge) > 0) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "The session exceeded its maximum age — sign in again");
        }
        String accessToken = mintAccessToken(principal, roles, now);
        OAuth2AccessToken accessTokenToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, accessToken, now, now.plus(ACCESS_TOKEN_TTL));
        // The rotation itself: the successor replaces the presented
        // token's value in the SAME row — the official SAS refresh
        // rotation (OAuth2Authorization.from(...).refreshToken(next)
        // over the row the findByToken leg resolved).
        String successor = opaqueToken();
        OAuth2RefreshToken refreshTokenToken = new OAuth2RefreshToken(successor, now,
                now.plus(REFRESH_TOKEN_TTL));
        OAuth2Authorization.Builder rotation = OAuth2Authorization.from(authorization)
                .token(accessTokenToken, metadata ->
                        metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, false))
                .token(refreshTokenToken, metadata ->
                        metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, false));
        if (familyCreated == null && familyStart != null) {
            // The legacy row's convergence: the family birth attribute is
            // seeded from the presented token's issuedAt (the conservative
            // floor — the family is at least this old), so the NEXT refresh
            // enforces the true family age from the same attribute every
            // new grant carries.
            // String-of-millis on the authorization store (the wave-4 CI
            // evidence: the row mapper's PTV denies the Long type id —
            // see the constant's javadoc).
            rotation.attribute(FAMILY_CREATED_ATTRIBUTE, String.valueOf(familyStart.toEpochMilli()));
        }
        OAuth2Authorization refreshed = rotation.build();
        // THE atomic boundary: the store arbitrates the consumption —
        // one conditional UPDATE on the shared row, in the database's
        // row lock, serialized across every instance sharing the store.
        // The loser (a replay, or a concurrent rotation that won on
        // ANOTHER instance) is rejected exactly like an unknown token.
        if (!refreshTokenRotationStore().rotate(refreshed, refreshToken)) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "The refresh token is invalid, expired or revoked");
        }
        UserAccountView user = identitySpi.findCurrentUserAccount(
                        new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()))
                .orElse(null);
        log.info("Platform token refresh rotated: userId={}", user == null ? null : user.id());
        // The shard review (2026-11-01 wave 2): the csrfToken honesty fix —
        // the rotation's answer carries null, exactly like the grant.
        return new IssuedTokens(accessToken, successor, null, user);
    }

    /**
     * The verification: the resource server's own decoder — the same
     * validator every API request runs (issuer, audience, expiry). An
     * invalid or expired token answers the honest {@code isValid: false},
     * never an error.
     */
    @Observed(name = "graphql.token.verify")
    public VerifiedToken verify(String token) {
        try {
            Jwt jwt = jwtDecoder().decode(token);
            return new VerifiedToken(true, jwt.getClaims(), jwt.getSubject());
        } catch (JwtException failure) {
            return new VerifiedToken(false, Map.of(), null);
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private String mintAccessToken(String principal, Set<String> roles, Instant issuedAt) {
        AuthorizationServerSettings settings = authorizationServerSettings();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(settings.getIssuer())
                .subject(principal)
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ACCESS_TOKEN_TTL))
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .build();
        return jwtEncoder().encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * Reads the family's birth time from the authorization attributes —
     * the recorded representation is the String-of-epoch-millis the
     * write side stores (the wave-4 CI evidence: the Long embedding
     * never survives the row mapper's PTV); a Number or an Instant
     * value is still honored (interim-build rows would carry one, and
     * the honest read never breaks a live session over a
     * representation difference). An unparseable String answers null —
     * the conservative floor — never a thrown parse failure.
     */
    private static Instant familyCreatedAt(OAuth2Authorization authorization) {
        Object raw = authorization.getAttributes().get(FAMILY_CREATED_ATTRIBUTE);
        if (raw instanceof Number epochMillis) {
            return Instant.ofEpochMilli(epochMillis.longValue());
        }
        if (raw instanceof Instant recorded) {
            return recorded;
        }
        if (raw instanceof String text) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(text.trim()));
            } catch (NumberFormatException tolerated) {
                return null;
            }
        }
        return null;
    }

    private OAuth2Authorization authorizationRow(String principal, Set<String> roles,
                                                  String accessToken, String refreshToken,
                                                  Instant issuedAt) {
        RegisteredClient client = registeredClientRepository().findByClientId(publicClientId);
        if (client == null) {
            throw new IllegalStateException(
                    "The public client is not registered — the password grant requires it");
        }
        OAuth2AccessToken accessTokenToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, accessToken, issuedAt,
                issuedAt.plus(ACCESS_TOKEN_TTL));
        OAuth2RefreshToken refreshTokenToken = new OAuth2RefreshToken(refreshToken, issuedAt,
                issuedAt.plus(REFRESH_TOKEN_TTL));
        return OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(principal)
                .authorizationGrantType(new AuthorizationGrantType("password"))
                .token(accessTokenToken)
                .refreshToken(refreshTokenToken)
                .attribute(ROLES_ATTRIBUTE, roles)
                // The family's birth time (the wave-2 review's absolute
                // session cap) — written once at grant as the String of
                // epoch millis (the wave-4 CI correction: the row mapper's
                // PTV denies the Long type id — see the constant's javadoc);
                // rotations preserve it (OAuth2Authorization.from copies
                // the attributes).
                .attribute(FAMILY_CREATED_ATTRIBUTE, String.valueOf(issuedAt.toEpochMilli()))
                .build();
    }

    private static Set<String> stripRolePrefix(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .map(value -> value.startsWith("ROLE_") ? value.substring(5) : value)
                .collect(Collectors.toSet());
    }

    /**
     * The OWASP timing-equalization burn: ONE {@link PasswordEncoder#matches}
     * against the constructor-cached dummy bcrypt hash — constant input,
     * ignored result, and never a restructuring of the grant flow (the
     * account-state failure is rethrown to the caller exactly as raised).
     */
    private void burnDummyPasswordComparison() {
        PasswordEncoder encoder = passwordEncoder.getIfAvailable();
        if (encoder != null && dummyBcrypt != null) {
            encoder.matches(DUMMY_COMPARISON_SECRET, dummyBcrypt);
        }
    }

    private AuthenticationManager authenticationManager() {
        AuthenticationConfiguration configuration = authenticationConfiguration.getIfAvailable();
        if (configuration == null) {
            throw new IllegalStateException(
                    "The authorization stack is not available — the token grant requires it");
        }
        try {
            return configuration.getAuthenticationManager();
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "The global authentication manager could not be assembled", failure);
        }
    }

    private OAuth2AuthorizationService authorizationService() {
        OAuth2AuthorizationService service = authorizationService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException(
                    "The authorization service is not available — the token grant requires it");
        }
        return service;
    }

    private RefreshTokenRotationStore refreshTokenRotationStore() {
        RefreshTokenRotationStore store = refreshTokenRotationStore.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException(
                    "The refresh rotation store is not available — the token grant requires it");
        }
        return store;
    }

    private RegisteredClientRepository registeredClientRepository() {
        RegisteredClientRepository repository = registeredClientRepository.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException(
                    "The client registry is not available — the token grant requires it");
        }
        return repository;
    }

    private AuthorizationServerSettings authorizationServerSettings() {
        AuthorizationServerSettings settings = authorizationServerSettings.getIfAvailable();
        if (settings == null) {
            throw new IllegalStateException(
                    "The authorization server settings are not available — the token grant "
                            + "requires them");
        }
        return settings;
    }

    private JwtEncoder jwtEncoder() {
        JWKSource<SecurityContext> keys = jwkSource.getIfAvailable();
        if (keys == null) {
            throw new IllegalStateException(
                    "The signing key material is not available — the token grant requires it");
        }
        return new NimbusJwtEncoder(keys);
    }

    private JwtDecoder jwtDecoder() {
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        if (decoder == null) {
            throw new IllegalStateException(
                    "The resource-server decoder is not available — token verification requires it");
        }
        return decoder;
    }

    private static String opaqueToken() {
        byte[] secret = new byte[32];
        SECURE_RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

}
