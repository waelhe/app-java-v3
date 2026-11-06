package com.marketplace.app.graphql;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.ExecutionResultImpl;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 1 / B3 (the Saleor 458-root campaign, assignment packet PR #696 —
 * the CodeRabbit security round): the GraphQL transport's pre-login
 * operation gate — the per-operation wall that reopens the {@code /graphql}
 * POST surface for anonymous callers WITHOUT opening the schema.
 *
 * <p><b>The contract being fixed:</b> the resource-server chain's blanket
 * {@code anyRequest().authenticated()} answered 401 before GraphQL
 * dispatch, which made Saleor's authentication roots unreachable —
 * {@code tokenCreate} is the password grant itself (a caller holding a
 * bearer token never needs it), {@code tokenRefresh}/{@code tokenVerify}
 * carry their own proof in the payload, and the account-lifecycle
 * redemption family redeems single-use out-of-band tokens or creates the
 * account. The security chain now permits anonymous POSTs to
 * {@code /graphql} (the register/password-reset/webhooks public-POST
 * precedent), and THIS gate is what the anonymous surface actually
 * is.</p>
 *
 * <p><b>Deny-by-default, document-parsed:</b> the gate parses the ACTUAL
 * GraphQL document (graphql-java's own {@link Parser} — never the
 * {@code operationName} parameter, which a client can spoof freely) and
 * walks every top-level operation selection. An anonymous caller reaches
 * execution ONLY when every requested operation is a member of the
 * enumerated pre-login family below; anything else — an unknown
 * operation, an empty selection set, a document that fails to parse —
 * answers the classified AUTHN-001 GraphQL error before dispatch. Every
 * AUTHENTICATED request passes through untouched to the per-fetcher
 * permission catalog ({@code SaleorPermissionCatalog}) and the owning
 * services' role/IDOR walls.</p>
 *
 * <p><b>The officially supported interception point:</b> a
 * {@link WebGraphQlInterceptor} bean — spring-graphql's documented
 * cross-cutting request interception mechanism, collected automatically
 * into the web handler's interceptor chain. The short-circuit response is
 * built through the framework's own response types
 * ({@link DefaultExecutionGraphQlResponse} over {@link WebGraphQlResponse}),
 * and the error carries the SAME canonical taxonomy pair
 * ({@code extensions.errorCode + extensions.category}) the
 * {@code GraphQlExceptionResolver} emits for every other classified
 * GraphQL failure — one machine contract across both transports.</p>
 *
 * <p>This is the §13 decision record's documented reopening condition
 * satisfied: "an approved public-discovery journey after tested
 * per-field/object authorization" — the campaign's role-matrix tests
 * (anonymous/CONSUMER/PROVIDER/ADMIN legs per root) are that tested
 * authorization, and this gate is the per-operation wall that keeps the
 * anonymous surface exactly as wide as the Saleor authentication
 * contract.</p>
 */
@Component
public class PreLoginGraphQlGate implements WebGraphQlInterceptor {

    /**
     * The Saleor authentication-domain operations an anonymous caller may
     * reach — every one carries its own proof in the payload (credentials,
     * a rotation token, a verifiable token, a single-use out-of-band
     * redemption right) or CREATES the account. Everything else in the
     * schema is deny-by-default for the anonymous caller.
     *
     * <p>{@code accountDelete} is NOT a member (the 12:44:42Z #806
     * review, Minor · Security &amp; Privacy): the pinned Saleor 3.23.45
     * contract requires AUTHENTICATED_USER on {@code AccountDelete} and
     * validates the one-time token against the authenticated caller
     * ({@code try_generators(user=info.context.user, token=token)}) — the
     * deletion redemption is caller-bound, so the anonymous surface must
     * not carry it. Authenticated requests still flow untouched through
     * {@code chain.next(request)}.</p>
     */
    private static final Set<String> PRE_LOGIN_OPERATIONS = Set.of(
            "tokenCreate",
            "tokenRefresh",
            "tokenVerify",
            "requestPasswordReset",
            "confirmAccount",
            "setPassword",
            "confirmEmailChange",
            "accountRegister");

    private final Parser parser = new Parser();

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (!isAnonymousCaller()) {
            return chain.next(request);
        }
        if (isPreLoginOnly(request.getDocument())) {
            return chain.next(request);
        }
        return Mono.just(authenticationRequired(request));
    }

    /**
     * The caller's authentication state on the request thread — the same
     * source every data fetcher's {@code currentAuthentication()} reads.
     * Spring Security's anonymous filter populates an
     * {@link AnonymousAuthenticationToken} for unauthenticated requests on
     * a permitAll route, so {@code isAuthenticated() == true} alone does
     * NOT mean a real principal: the anonymous token is anonymous.
     */
    private static boolean isAnonymousCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }

    /**
     * Parses the actual document and walks EVERY top-level operation
     * selection: the request is pre-login only when the operation set is
     * non-empty and entirely contained in the enumerated family. A
     * document that fails to parse is NOT trusted — deny-by-default
     * answers the classified error, exactly like an unknown operation.
     *
     * <p><b>The fragment discipline (round 2 → the 2026-11-05 round-3
     * refinement):</b> a top-level fragment SPREAD or INLINE fragment is
     * resolved against the document's OWN fragment definitions — a
     * document whose every reachable root is a member of the pre-login
     * family is decidable and answers its verdict, so a legitimate
     * {@code query { ...F } fragment F on Query { tokenVerify } } from an
     * anonymous caller no longer eats a blanket AUTHN-001. The deny
     * walls stay: a fragment reference the document itself does not
     * define, a cyclic reference, or any reachable root outside the
     * family denies exactly like an unknown operation — the gate never
     * resolves what it cannot decide. The walk is confined to the
     * document's own definitions (no external fragments exist on this
     * transport), and a visited set bounds the recursion.</p>
     */
    private boolean isPreLoginOnly(String document) {
        if (document == null || document.isBlank()) {
            return false;
        }
        try {
            Document parsed = parser.parseDocument(document);
            Map<String, FragmentDefinition> fragments = new LinkedHashMap<>();
            for (Definition<?> definition : parsed.getDefinitions()) {
                if (definition instanceof FragmentDefinition fragmentDefinition) {
                    fragments.put(fragmentDefinition.getName(), fragmentDefinition);
                }
            }
            Set<String> operations = new java.util.HashSet<>();
            boolean anySelection = false;
            for (Definition<?> definition : parsed.getDefinitions()) {
                if (definition instanceof OperationDefinition operation) {
                    // The per-selection walk: a refusal (an undefined or
                    // cyclic fragment reference, or a root outside the
                    // family) short-circuits to the deny verdict.
                    Boolean verdict = collectRoots(
                            operation.getSelectionSet(), fragments,
                            new java.util.HashSet<>(), operations);
                    if (verdict == Boolean.FALSE) {
                        return false;
                    }
                    anySelection |= verdict == Boolean.TRUE;
                }
            }
            return anySelection && !operations.isEmpty()
                    && PRE_LOGIN_OPERATIONS.containsAll(operations);
        } catch (RuntimeException failure) {
            // An unparsable document from an anonymous caller is denied by
            // the same rule as an unknown operation — the gate never
            // forwards what it cannot read.
            return false;
        }
    }

    /**
     * Walks one selection set collecting the ROOT field names it selects,
     * resolving fragment spreads and inline fragments against the
     * document's own definitions. Answers {@code FALSE} the moment the
     * walk meets an undefined or cyclic fragment reference (deny — the
     * gate cannot decide what the document does not define); answers
     * {@code TRUE} when at least one root was collected, {@code null}
     * when the selection set is empty.
     */
    private Boolean collectRoots(SelectionSet selectionSet,
                                  Map<String, FragmentDefinition> fragments,
                                  Set<String> visiting,
                                  Set<String> operations) {
        if (selectionSet == null || selectionSet.getSelections().isEmpty()) {
            return null;
        }
        Boolean any = null;
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                operations.add(field.getName());
                any = Boolean.TRUE;
            } else if (selection instanceof FragmentSpread spread) {
                if (!visiting.add(spread.getName())) {
                    // A cycle (or a repeat reference already being
                    // resolved) — undecidable, deny.
                    return Boolean.FALSE;
                }
                FragmentDefinition definition = fragments.get(spread.getName());
                if (definition == null) {
                    // The document references a fragment it does not
                    // define — undecidable, deny.
                    return Boolean.FALSE;
                }
                Boolean nested = collectRoots(
                        definition.getSelectionSet(), fragments, visiting, operations);
                if (nested == Boolean.FALSE) {
                    return Boolean.FALSE;
                }
                any = any == Boolean.TRUE || nested == Boolean.TRUE ? Boolean.TRUE : any;
            } else if (selection instanceof InlineFragment inline) {
                Boolean nested = collectRoots(
                        inline.getSelectionSet(), fragments, visiting, operations);
                if (nested == Boolean.FALSE) {
                    return Boolean.FALSE;
                }
                any = any == Boolean.TRUE || nested == Boolean.TRUE ? Boolean.TRUE : any;
            } else {
                // Any other selection shape at the root level — the gate
                // refuses the shape it cannot decide.
                return Boolean.FALSE;
            }
        }
        return any;
    }

    /**
     * The short-circuit response: the classified AUTHN-001 GraphQL error —
     * the same canonical taxonomy pair ({@code errorCode} +
     * {@code category}) the exception resolver emits for every other
     * classified failure, with spring-graphql's five-value
     * {@link ErrorType#UNAUTHORIZED} as the GraphQL-spec envelope. Built
     * through the framework's own response types.
     */
    private WebGraphQlResponse authenticationRequired(WebGraphQlRequest request) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("errorCode", ApiErrorTaxonomy.AUTHN.errorCode());
        extensions.put("category", ApiErrorTaxonomy.AUTHN.category());
        GraphQLError error = GraphqlErrorBuilder.newError()
                .errorType(ErrorType.UNAUTHORIZED)
                .message("Authentication is required for this GraphQL operation")
                .extensions(extensions)
                .build();
        return new WebGraphQlResponse(
                new DefaultExecutionGraphQlResponse(request.toExecutionInput(),
                        new ExecutionResultImpl(java.util.List.of(error))));
    }
}
