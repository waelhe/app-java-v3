# API Error Contract (RFC 7807 / RFC 9457)

This document defines the canonical error payload for Marketplace REST APIs.

> **Spec note (A-03, measured 2026-10-07):** the Spring Framework's
> governing reference (`docs.spring.io/spring-framework/reference/web/webmvc/
> mvc-ann-rest-exceptions.html`) now cites the specification as **RFC 9457**
> ("Problem Details for HTTP APIs", which obsoletes RFC 7807). The wire
> format is unchanged between the two revisions — the fields below are
> exactly the same — so every "RFC 7807" reference in this contract reads
> as the same payload under the current spec name.

## Media type

All API errors must use:

- `Content-Type: application/problem+json`

## Base contract

Errors follow RFC 7807 (`ProblemDetail`) with the following core fields:

- `type` (URI): stable error category URI.
- `title` (string): short, human-readable error title.
- `status` (integer): HTTP status code.
- `detail` (string): human-readable explanation for this occurrence.
- `instance` (URI): request path or request-specific identifier.
- `errorCode` (string): stable machine-readable taxonomy code.
- `category` (string): canonical taxonomy category.
- `userMessage` (string, optional): user-facing safe message.

## Marketplace error type registry

See also: [Error Taxonomy & Codes](./error-codes.md).

| HTTP status | type URI | title |
|---|---|---|
| 400 | `https://marketplace.com/errors/bad-request` | `Bad Request` |
| 400 (validation) | `https://marketplace.com/errors/validation` | `Bad Request` |
| 400 (constraint) | `https://marketplace.com/errors/constraint-violation` | `Bad Request` |
| 401 | `https://marketplace.com/errors/unauthorized` | `Unauthorized` |
| 403 | `https://marketplace.com/errors/access-denied` | `Forbidden` |
| 404 | `https://marketplace.com/errors/not-found` | `Not Found` |
| 409 | `https://marketplace.com/errors/conflict` | `Conflict` |
| 409 (optimistic lock) | `https://marketplace.com/errors/optimistic-lock` | `Conflict` |
| 429 | `https://marketplace.com/errors/rate-limited` | `Too Many Requests` |
| 500 | `https://marketplace.com/errors/internal-error` | `Internal Server Error` |
| 503 | `https://marketplace.com/errors/circuit-breaker-open` | `Service Unavailable` |

## Validation extensions

Both validation legs answer this extension field — the body leg
(`@Valid @RequestBody` → `MethodArgumentNotValidException`) and the method leg
(`@Validated` parameters → `ConstraintViolationException`, §5 contract
completeness: the field is the violation's leaf name, i.e. the rejected
parameter):

- `fieldErrors`: array of field-level validation violations (an empty array
  when the violation set is empty — the shape is always present on a 400
  validation answer).

Example:

```json
{
  "type": "https://marketplace.com/errors/validation",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/api/users",
  "fieldErrors": [
    {
      "field": "email",
      "message": "must be a well-formed email address"
    }
  ]
}
```

## OpenAPI linkage

- `components.schemas.ProblemDetail` defines the reusable schema.
- Reusable responses are defined under `components.responses` and use `application/problem+json`.
- An OpenAPI customizer injects defaults for: `400, 401, 403, 404, 409, 429, 500` when missing.

## Implementation notes

- `spring.mvc.problemdetails.enabled=true` is enabled in application
  configuration — the official automatic: Spring Boot autoconfigures its own
  `ResponseEntityExceptionHandler` advice (measured `@Order(0)`) that renders
  every built-in Spring MVC exception (405, 415, 406, unreadable body, …) with
  the Framework's own RFC 9457 body.
- `GlobalExceptionHandler` is `@Order(Ordered.HIGHEST_PRECEDENCE)` — ordered
  **ahead** of that automatic handler, exactly as the official reference
  prescribes for taking a specific built-in over ("You'll need to ensure your
  handler is ordered ahead of the one configured by Spring Boot whose order is
  0."). It takes over precisely the two built-ins whose documented house
  contract is richer than the automatic body: `MethodArgumentNotValidException`
  (the `fieldErrors` extension below) and `NoResourceFoundException` (the
  taxonomy 404 with `errorCode`/`category`/`instance`), plus every
  domain/security/validation/resilience handler.
- `GlobalErrorFallbackHandler` is `@Order(Ordered.LOWEST_PRECEDENCE)` — the
  uncaught-exception safety net (the 500 INTERNAL taxonomy body) as its own
  LAST-ordered advice, behind the automatic handler. Measured necessity: a
  catch-all inside the ahead-ordered advice would swallow every built-in
  before the automatic handler sees it (a 405 answered 500 in the measured
  regression) — the official reference prescribes the takeover advice for
  *specific* built-ins only, so the composition is three layers in resolution
  order: the specific house advice, Boot's automatic order-0 handler, then the
  fallback.
- `ApiProblemDetailException` (the `ResourceNotFoundException` family) extends
  the Framework's own `ErrorResponseException` — the official base the
  reference defines as "basic ErrorResponse implementation that others can use
  as a convenient base class" — carrying a prebuilt RFC 9457 body with the
  taxonomy `type`/`errorCode`/`category`.
- `GlobalExceptionHandler` enriches `ProblemDetail` with `type`, `instance`,
  and validation `fieldErrors`.
- The unified contract tests pinning all three layers of the composition live
  in `BookingErrorContractWebMvcTest` (the 404/403/400-`fieldErrors` house
  side and the 405 automatic side).


## REST (RFC 7807) vs GraphQL error envelope

Marketplace intentionally uses **two different error envelopes** depending on protocol:

- **REST (`/api/**`)** uses RFC 7807 `application/problem+json` (`type`, `title`, `status`, `detail`, `instance`).
- **GraphQL (`/graphql`)** uses the GraphQL spec envelope: top-level `errors[]` entries with `message`, `path`, and `extensions`.

### Why they differ

- RFC 7807 is HTTP-centric and maps one request to one HTTP status/result body.
- GraphQL can return partial data and multiple resolver errors in a single response, so errors are expressed per-entry inside `errors[]`.

### Marketplace GraphQL `extensions` contract

For domain/runtime failures resolved by the central GraphQL exception resolver, each error includes:

- `errorCode`: stable machine-readable code (e.g. `NOT_FOUND`, `DOMAIN_CONFLICT`, `INTERNAL_ERROR`).
- `category`: high-level class (`RESOURCE`, `DOMAIN`, `VALIDATION`, `INTERNAL`).
- `traceId`: propagated correlation identifier, included only when `marketplace.graphql.errors.include-trace-id=true`.

### Security and leakage policy

- Internal/unknown exceptions MUST return a generic message (`An unexpected error occurred`).
- Stack traces and internal exception details MUST NOT be exposed in GraphQL error messages.

## Migration note (naming)

- The local API DTO formerly named `ErrorResponse` has been renamed to `ApiErrorPayload` to avoid collisions with Spring's `org.springframework.web.ErrorResponse`.
- Do not introduce new local types named `ErrorResponse`; use `ApiErrorPayload` for payload DTOs and Spring `ErrorResponse` for framework contracts.

## Backward compatibility CI gate

OpenAPI compatibility is enforced in CI by comparing the current branch spec against the latest release tag baseline (`/v3/api-docs`).

Breaking changes fail CI, including:

- endpoint/path removal,
- request or response schema narrowing,
- response status code removal/change,
- media type removal/change.

Temporary exceptions are only allowed when documented in `.ci/openapi-compat-allowlist.yml` with a ticket reference, reason, and expiry date.
