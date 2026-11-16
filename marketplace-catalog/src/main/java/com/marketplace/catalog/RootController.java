package com.marketplace.catalog;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The service root — the landing a browser reaches in exactly one measured
 * flow: Spring Security's post-authentication fallback. When a form-login
 * {@code POST /login} succeeds <em>without</em> a saved request (the
 * authorization-request session was lost or expired between the authorize
 * redirect and the credential submit — the redeploy-heavy trial window made
 * it routine), the default
 * {@link org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler}
 * sends the browser to {@code /} — and until this controller existed, an
 * authenticated {@code GET /} had no handler and no welcome page, so the
 * resource layer answered the raw RFC 7807 body the owner measured in the
 * browser during the 2026-09-30 frontend session:
 * {@code {"detail":"No static resource .","instance":"/","status":404,"title":"Not Found"}}.
 *
 * <p><b>Ownership (the SeoController precedent):</b> this API's own root is
 * not a page — the pages belong to the public site origin bound in
 * {@code marketplace.catalog.seo} (the client-hosting plan's frontend).
 * A bound origin answers {@code 302} to the public site's home (the browser
 * continues where a human expects to land); an unbound deployment (the
 * capability gate OFF — dev, CI, a frontend-less environment) answers the
 * honest service document: a tiny {@code application/json} map naming the
 * API root and the operational surfaces, never a fabricated URL (the same
 * gate rule every Seo accessor follows).
 *
 * <p><b>Scope discipline:</b> GET-only and deliberately on the form-login
 * default chain — the anonymous browser keeps the standard 302-to-login
 * behavior (the auth server's own entry), and no security matcher changes:
 * the fix is exactly the authenticated-root case the defect measured.
 */
@RestController
public class RootController {

    private final CatalogProperties catalogProperties;

    public RootController(CatalogProperties catalogProperties) {
        this.catalogProperties = catalogProperties;
    }

    /**
     * The authenticated service root: {@code 302} to the public site's home
     * when the SEO capability is bound; the honest service document
     * otherwise. No {@code produces} constraint (greptile r1, adopted): a
     * pure {@code Accept: text/html} client must not be answered 406 on a
     * landing route — the document carries its own explicit content type.
     */
    @GetMapping("/")
    @Operation(summary = "The service root — a redirect to the public site when bound",
            description = "The post-authentication landing (the saved-request-less "
                    + "POST /login fallback) and any stray authenticated navigation to "
                    + "the API origin. A bound marketplace.catalog.seo public site "
                    + "answers 302 to its home — the pages belong to the frontend "
                    + "origin, never to this API. An unbound deployment answers the "
                    + "honest service document (the API root and the operational "
                    + "surfaces), not a fabricated redirect and not the raw "
                    + "no-static-resource 404 the pre-fix origin produced.")
    public ResponseEntity<Map<String, String>> root() {
        return catalogProperties.seo().publicSiteHomeUrl()
                .map(home -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(java.net.URI.create(home))
                        .<Map<String, String>>build())
                .orElseGet(() -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(serviceDocument()));
    }

    /**
     * The capability-OFF body: what this deployment actually serves. The
     * paths are the deployed API's own fixed same-origin surfaces — the
     * versioned API root and the OpenAPI document. The health surface is
     * deliberately absent (greptile r2, adopted): production binds the
     * management endpoints on their own port
     * ({@code management.server.port}, 8081 by default), so a same-origin
     * {@code /actuator/health} pointer would be a fabricated URL — the exact
     * thing this controller's own rule forbids. The orchestrator's probes
     * already know where they configured health to live.
     */
    private Map<String, String> serviceDocument() {
        Map<String, String> document = new LinkedHashMap<>(4);
        document.put("service", "marketplace");
        document.put("api", "/api/v1");
        document.put("docs", "/v3/api-docs");
        return document;
    }
}
