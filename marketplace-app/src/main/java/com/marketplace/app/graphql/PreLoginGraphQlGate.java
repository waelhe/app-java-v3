package com.marketplace.app.graphql;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.ExecutionResultImpl;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
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
     */
    private static final Set<String> PRE_LOGIN_OPERATIONS = Set.of(
            "tokenCreate",
            "tokenRefresh",
            "tokenVerify",
            "requestPasswordReset",
            "confirmAccount",
            "setPassword",
            "confirmEmailChange",
            "accountRegister",
            "accountDelete");

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
     */
    private boolean isPreLoginOnly(String document) {
        if (document == null || document.isBlank()) {
            return false;
        }
        try {
            Document parsed = parser.parseDocument(document);
            Set<String> operations = new java.util.HashSet<>();
            for (Definition<?> definition : parsed.getDefinitions()) {
                if (definition instanceof OperationDefinition operation) {
                    for (Selection<?> selection : operation.getSelectionSet().getSelections()) {
                        if (selection instanceof Field field) {
                            operations.add(field.getName());
                        }
                    }
                }
            }
            return !operations.isEmpty() && PRE_LOGIN_OPERATIONS.containsAll(operations);
        } catch (RuntimeException failure) {
            // An unparsable document from an anonymous caller is denied by
            // the same rule as an unknown operation — the gate never
            // forwards what it cannot read.
            return false;
        }
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
