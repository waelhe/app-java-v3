package com.marketplace.app.graphql;

import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.context.DgsCustomContextBuilderWithRequest;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * The request-scoped GraphQL context — the official DGS mechanism
 * ({@code DgsCustomContextBuilderWithRequest}, the custom-context
 * extension point of the DGS version in use, graphql-dgs 12.1.0) that
 * captures the caller's {@link Authentication} ONCE, on the servlet
 * request thread the security filter chain populated
 * ({@code DgsWebMvcGraphQLInterceptor.intercept} builds the context
 * synchronously while that thread's {@code RequestContextHolder} and
 * {@code SecurityContextHolder} are bound), and hands it to EVERY
 * datafetcher through the request-scoped {@code DgsContext} attached
 * to the ExecutionInput's GraphQLContext.
 *
 * <p><b>Why this exists (the wave-4 CI evidence, INT-001 on
 * {@code menuItemCreate.menuItem.menu}):</b> with
 * {@code spring.threads.virtual.enabled=true} the DGS engine executes
 * datafetchers on {@code dgs-virtual-thread-*} threads, and the
 * {@code SecurityContextHolder} ThreadLocal populated by the security
 * filter is reliably present for ROOT datafetchers but NOT for the
 * field-level completion resolvers the engine fans out — the wave-4 CI
 * failure measured exactly that (the menu field resolver's
 * {@code currentAuthentication()} answered
 * {@code AuthenticationCredentialsNotFoundException} → the classified
 * INT-001 internal error). Reading the caller from the REQUEST-SCOPED
 * context object removes the thread-local dependence entirely: the
 * context is built once per request on the thread that HAS the
 * authentication, and every resolver — root or field, whatever thread
 * the engine picks — reads the same object.</p>
 *
 * <p>Field-level completion resolvers therefore use
 * {@link #callerOf(DataFetchingEnvironment)} instead of the
 * {@code currentAuthentication()} ThreadLocal read (the
 * {@code AddressDataFetcher.userAddresses} precedent — completion
 * resolvers never touched the holder there either). The captured
 * authentication may be null (an anonymous request) — completion
 * resolvers answer their honest absent floor in that case, exactly as
 * they already do for a concurrently-deleted source.</p>
 */
public record MarketplaceGraphQlContext(Authentication authentication) {

    /** The builder DGS consults per request (the official extension point). */
    @Component
    public static class Builder implements DgsCustomContextBuilderWithRequest<MarketplaceGraphQlContext> {

        @Override
        public MarketplaceGraphQlContext build(Map<String, ? extends Object> extensions,
                                               HttpHeaders headers,
                                               WebRequest webRequest) {
            return new MarketplaceGraphQlContext(
                    SecurityContextHolder.getContext().getAuthentication());
        }
    }

    /**
     * The request's caller — captured once per request by the builder.
     * Never read from the ThreadLocal holder: field-level resolvers run
     * on threads the holder does not reach (the wave-4 CI evidence).
     */
    public static Authentication callerOf(DataFetchingEnvironment environment) {
        Object custom = DgsContext.getCustomContext(environment);
        return custom instanceof MarketplaceGraphQlContext context
                ? context.authentication()
                : null;
    }
}
