# 02 — Spring Web MVC + Spring Boot Web (isolated official reference)

> **Tree refs:** 2.13 (Framework Web Servlet: MVC), 1.3 (Boot Web), 1.11 (Boot how-to: web) + 9.1 (RFC 9457 framing).
> **Sources:** `https://docs.spring.io/spring-framework/reference/web/webmvc.html` (7.0.9) + `https://docs.spring.io/spring-boot/reference/web/servlet.html` (4.1.1) — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. `[quote]`=verbatim · `[paraphrase]`=faithful restatement.
> **Isolation:** no internal repo data, no current system. **Encoding:** UTF-8.

---

## 1. DispatcherServlet & Context Hierarchy (2.13)

- MUST | Spring MVC is a front-controller pattern: `DispatcherServlet` centralizes request processing; actual work done by configurable delegate beans. | [quote] | web/webmvc/mvc-servlet.html
- MUST | In Boot, bootstrap via Spring configuration, not the Servlet lifecycle — Filter/Servlet beans are detected and registered with the embedded container. | [paraphrase] | web/webmvc/mvc-servlet.html
- ATTEND | A single `WebApplicationContext` suffices for most apps; a context hierarchy (root infra + servlet-specific child) is needed only when sharing infra beans across multiple servlets. | [quote] | web/webmvc/mvc-servlet/context-hierarchy.html
- ATTEND | Special beans detected by DispatcherServlet: HandlerMapping, HandlerAdapter, HandlerExceptionResolver, ViewResolver, LocaleResolver, MultipartResolver, FlashMapManager. | [quote] | web/webmvc/mvc-servlet/special-bean-types.html
- ATTEND | If a special bean is missing, DispatcherServlet falls back to defaults in `DispatcherServlet.properties`; use the MVC Config (`WebMvcConfigurer`) as the starting point. | [quote] | web/webmvc/mvc-servlet/config.html
- MUST | Prefer `PathPatternParser` (default since 6.0) over the deprecated `AntPathMatcher` — it sanitizes one path segment at a time. | [quote] | web/webmvc/mvc-servlet/handlermapping-path.html
- AVOID | Relying on `servletPath` (prefix-based servlet mapping) — map the DispatcherServlet as default `/` to avoid `servletPath`/`pathInfo`. | [quote] | web/webmvc/mvc-servlet/handlermapping-path.html
- ATTEND | `postHandle` cannot mutate `@ResponseBody`/`ResponseEntity` responses (already committed); use `ResponseBodyAdvice` for that. | [paraphrase] | web/webmvc/mvc-servlet/handlermapping-interceptor.html
- AVOID | Interceptors as a security layer — path-matching mismatch risk; use Spring Security instead. | [quote] | web/webmvc/mvc-servlet/handlermapping-interceptor.html
- ATTEND | Exception resolution chain: `SimpleMappingExceptionResolver`, `DefaultHandlerExceptionResolver`, `ResponseStatusExceptionResolver`, `ExceptionHandlerExceptionResolver`; unresolved → Servlet container error page. | [paraphrase] | web/webmvc/mvc-servlet/exceptionhandlers.html
- ATTEND | Do NOT enable `enableLoggingRequestDetails=true` in production — params/headers are masked by default; full logging may leak sensitive data. | [paraphrase] | web/webmvc/mvc-servlet/logging.html

## 2. Annotated Controllers (2.13)

- MUST | Use `@RestController` (= `@Controller` + `@ResponseBody`) for body-writing controllers; use HTTP-method shortcuts (`@GetMapping`/`@PostMapping`) over bare `@RequestMapping`. | [quote] | web/webmvc/mvc-controller/ann.html · ann-requestmapping.html
- ATTEND | With interface-based proxying (6.0+), controllers are no longer detected from a type-level `@RequestMapping` on an interface alone. | [paraphrase] | web/webmvc/mvc-controller/ann.html
- ATTEND | Multiple `@RequestMapping` on the same element → warning, only the first is used. | [quote] | web/webmvc/mvc-controller/ann-requestmapping.html
- ATTEND | Method-level `consumes`/`produces` override rather than extend the class-level declaration. | [paraphrase] | web/webmvc/mvc-controller/ann-requestmapping.html
- AVOID | `@RequestBody` for form data — the body is consumed on parse and cannot be read again; use `@RequestParam`. | [quote] | web/webmvc/mvc-controller/ann-methods/requestbody.html
- MUST | Declare `Errors`/`BindingResult` immediately after a validated `@ModelAttribute`/`@RequestBody`/`@RequestPart` argument to handle errors locally. | [paraphrase] | web/webmvc/mvc-controller/ann-methods/modelattrib-method-args.html
- MUST | Handle BOTH `MethodArgumentNotValidException` and `HandlerMethodValidationException` — either may be raised depending on method signature. | [paraphrase] | web/webmvc/mvc-controller/ann-validation.html
- AVOID | Broad property binding on rich domain objects via `@ModelAttribute` — restrict `allowedFields` or use constructor binding / a web-specific binding object (security). | [paraphrase] | web/webmvc/mvc-controller/ann-methods/modelattrib-method-args.html
- ATTEND | Type conversion auto-applies to String-based args; an empty String converting to null is treated as missing (→ Missing…Exception). | [paraphrase] | web/webmvc/mvc-controller/ann-methods/typeconversion.html
- ATTEND | `HttpSession` args are never null and session access is not thread-safe — consider `synchronizeOnSession`. | [paraphrase] | web/webmvc/mvc-controller/ann-methods/arguments.html
- ATTEND | Return values: `@ResponseBody`→converter; `ResponseEntity`→full response; `ErrorResponse`/`ProblemDetail`→RFC 9457 body; `String`→view name; simple-type returns→`@ResponseBody`-style model attribute. | [paraphrase] | web/webmvc/mvc-controller/ann-methods/return-types.html
- ATTEND | Flash attributes are removed after the next request and can be consumed too early by async/polling — use mainly for redirects. | [quote] | web/webmvc/mvc-controller/ann-methods/flash-attributes.html
- ATTEND | Set `ignoreDefaultModelOnRedirect=true` for new apps to keep default model attributes out of redirect URLs. | [paraphrase] | web/webmvc/mvc-controller/ann-methods/redirecting-passing-data.html
- MUST | Enable multipart handling: declare a `multipartResolver` bean (`StandardServletMultipartResolver`) AND Servlet `MultipartConfigElement`. | [paraphrase] | web/webmvc/mvc-servlet/multipart.html
- ATTEND | Jackson `@JsonView` renders a subset of fields; one view class per method (compose via interface). | [paraphrase] | web/webmvc/mvc-controller/ann-methods/jackson.html

## 3. Exceptions & @ControllerAdvice (2.13)

- ATTEND | `@ExceptionHandler` methods may live in controllers or `@ControllerAdvice`; be as specific as possible in the argument signature (root-vs-cause matching can surprise). | [quote] | web/webmvc/mvc-controller/ann-exceptionhandler.html
- ATTEND | Local `@ExceptionHandler` applies before global; global `@ModelAttribute`/`@InitBinder` applies before local. | [paraphrase] | web/webmvc/mvc-controller/ann-advice.html
- MUST | Place primary root exception mappings on an `@ControllerAdvice` with a prioritized `order` — a cause match on a higher-priority advice beats a root match on a lower one. | [paraphrase] | web/webmvc/mvc-controller/ann-advice.html
- ATTEND | Narrow `@ControllerAdvice` scope via `annotations=...`/package/`assignableTypes=...`; selectors evaluated at runtime may cost performance. | [paraphrase] | web/webmvc/mvc-controller/ann-advice.html

## 4. CORS (2.13)

- MUST | Explicitly declare CORS config when cross-origin is expected — no config ⇒ no CORS headers ⇒ browsers reject. | [quote] | web/webmvc-cors.html
- MUST | With `allowCredentials=true`, do NOT use wildcard `*` in `allowOrigins` — use `allowOriginPatterns` or a finite domain set. | [quote] | web/webmvc-cors.html
- ATTEND | `@CrossOrigin` defaults: all origins/headers/methods; credentials off; maxAge 30 min; local overrides global for single-value attrs. | [paraphrase] | web/webmvc-cors.html
- MUST | In Boot, global CORS via a `WebMvcConfigurer` bean with `addCorsMappings`; `@CrossOrigin` needs no extra config. | [quote] | web/webmvc/servlet.html#web.servlet.spring-mvc.cors
- MUST | Handle CORS BEFORE Spring Security — preflight carries no cookies; use `CorsFilter` or a `UrlBasedCorsConfigurationSource`. | [quote] | spring-security/reference/servlet/integrations/cors.html

## 5. API Versioning (2.13 + 1.3)

- MUST | Enable API versioning via `ApiVersionConfigurer` in the MVC config (or `spring.mvc.apiversion.*` in Boot) before version-mapped endpoints work. | [paraphrase] | web/webmvc/mvc-config/api-version.html · web/webmvc/servlet.html#web.servlet.spring-mvc.api-versioning
- ATTEND | Version resolution options: header, query param, media-type param, or path segment (declared as a URI variable like `/{version}`). | [paraphrase] | web/webmvc/mvc-config/api-version.html
- MUST | Handle 400 for invalid/missing versions — `InvalidApiVersionException`, `MissingApiVersionException`, `NotAcceptableApiVersionException` all yield 400. | [paraphrase] | web/webmvc-versioning.html
- ATTEND | `@RequestMapping` version attr: fixed (`"1.2"`), baseline (`"1.2+"`), or none; un-versioned methods have the LOWEST priority. | [quote] | web/webmvc/mvc-controller/ann-requestmapping.html
- AVOID | Un-versioned controller methods for new endpoints — they are superseded by any versioned alternative. | [paraphrase] | web/webmvc/mvc-controller/ann-requestmapping.html

## 6. Error Handling & Problem Details — RFC 9457 (2.13 + 9.1)

- MUST | Return `ProblemDetail`/`ErrorResponse` (or extend `ResponseEntityExceptionHandler`) for RFC 9457 `application/problem+json` responses. | [quote] | web/webmvc/mvc-ann-rest-exceptions.html
- MUST | In Boot, set `spring.mvc.problemdetails.enabled=true` to auto-handle built-in MVC exceptions as Problem Details. | [quote] | web/webmvc/servlet.html#web.servlet.spring-mvc.error-handling
- ATTEND | Default `spring.mvc.problemdetails.enabled` is OFF — must be enabled explicitly. | [paraphrase] | web/webmvc/mvc-ann-rest-exceptions.html
- AVOID | Revealing internals — default message codes carry fully-qualified exception class names; customize `type`/`title`/`detail` via `MessageSource` codes. | [paraphrase] | web/webmvc/mvc-ann-rest-exceptions.html
- ATTEND | `instance` is set from the current URL path if not already set; non-standard fields go into `ProblemDetail.properties` (unwrapped to top level). | [paraphrase] | web/webmvc/mvc-ann-rest-exceptions.html
- ATTEND | To override a built-in Boot-handled exception, place your own `@ControllerAdvice` at order < 0 (Boot's handler is order 0). | [paraphrase] | web/webmvc/mvc-ann-rest-exceptions.html
- MUST | Boot default `/error` mapping: JSON for machine clients, whitelabel for browsers; replace fully via `ErrorController`/`ErrorAttributes`/`BasicErrorController`. | [quote] | web/webmvc/servlet.html#web.servlet.spring-mvc.error-handling
- MUST | Register filters handling error-page paths with ALL `DispatcherType`s — default `FilterRegistrationBean` excludes ERROR. | [paraphrase] | web/webmvc/servlet.html#web.servlet.spring-mvc.error-handling

## 7. Spring Boot Web defaults (1.3)

- ATTEND | Embedded server default port 8080; configure via `server.*`; programmatic via `WebServerFactoryCustomizer`. | [quote] | web/webmvc/servlet.html#web.servlet.embedded-container
- ATTEND | Filter/Servlet/Listener beans auto-register with the embedded container; a single Servlet maps to `/`, filters to `/*`. | [paraphrase] | web/webmvc/servlet.html#web.servlet.embedded-container
- AVOID | Expecting beans to be reliably initialized with `ServletContext` in an embedded container — use `ApplicationListener<ApplicationStartedEvent>` or lazy lookup. | [paraphrase] | web/webmvc/servlet.html#web.servlet.embedded-container
- AVOID | `@EnableWebMvc` in a Boot app when keeping Boot MVC auto-configuration — they cannot be used together. | [quote] | web/webmvc/servlet.html#web.servlet.spring-mvc.auto-configuration
- ATTEND | Keep Boot MVC auto-config and add customizations via a `WebMvcConfigurer` bean (without `@EnableWebMvc`). | [paraphrase] | web/webmvc/servlet.html#web.servlet.spring-mvc.auto-configuration
- AVOID | Expecting `Period`/`Duration`/`DataSize` converters or `@DurationUnit`/`@DataSizeUnit` in Spring MVC's ConversionService — MVC uses a different ConversionService than the Environment. | [paraphrase] | web/webmvc/servlet.html#web.servlet.spring-mvc.auto-configuration
- ATTEND | Boot serves static from `/static`,`/public`,`/resources`,`/META-INF/resources` mapped on `/**`; tune via `spring.mvc.static-path-pattern`/`spring.web.resources.static-locations`. | [quote] | web/webmvc/servlet.html#web.servlet.spring-mvc.static-content
- AVOID | `src/main/webapp` in a jar-packaged Boot app — silently ignored. | [paraphrase] | web/webmvc/servlet.html#web.servlet.spring-mvc.static-content
- AVOID | Suffix pattern matching / path-extension content negotiation (Boot disables by default as best practice; RFD risk). | [paraphrase] | web/webmvc/servlet.html#web.servlet.spring-mvc.path-matching
- ATTEND | `PathPatternParser` is incompatible with `spring.mvc.servlet.path` prefix — switch to `ant-path-matcher` if a prefix is needed. | [paraphrase] | web/webmvc/servlet.html#web.servlet.spring-mvc.path-matching
- ATTEND | Prefer query-parameter content negotiation (`spring.mvc.contentnegotiation.favor-parameter=true`) over suffix matching for legacy clients. | [paraphrase] | web/webmvc/servlet.html#web.servlet.spring-mvc.path-matching

## 8. Functional Endpoints — WebMvc.fn (brief, 2.13)

- ATTEND | Routing in WebMvc.fn is order-based — declare more specific routes before general ones (unlike annotation most-specific matching). | [paraphrase] | web/webmvc-functional.html
- MUST | Functional-endpoint CORS needs a dedicated `CorsFilter` — not applied automatically like annotated controllers. | [paraphrase] | web/webmvc-functional.html

---

## Cross-cutting gap insertions (from the standards-miner)

| Gap | Home | Rule added |
|---|---|---|
| RFC 9457 (7807 replaced) | §6 + 9.1 | Problem Details via RFC 9457; enable `spring.mvc.problemdetails.enabled=true`. |
| Test slices over full context | → `07-testing.md` | (owned by testing family) |
| Defense in depth (URL+method+object) | → `03-security.md` | (owned by security family) |

## Verification note
- All pages fetched live 2026-09-12 at Framework 7.0.9 + Boot 4.1.1; exact URLs above. Suffix `#web.servlet.spring-mvc.*` anchors resolve within Boot `web/servlet.html`.
- Tree coverage: 2.13 ✅ (servlet MVC subset) · 1.3 ◐ (servlet) · 9.1 ◐ (RFC 9457 framing).