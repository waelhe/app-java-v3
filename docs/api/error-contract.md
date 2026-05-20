# API Error Contract (RFC 7807)

This document defines the canonical error payload for Marketplace REST APIs.

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

Validation errors MAY include this extension field:

- `fieldErrors`: array of field-level validation violations.

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

- `spring.mvc.problemdetails.enabled=true` is enabled in application configuration.
- `ResourceNotFoundException` implements Spring `ErrorResponse` and provides a prebuilt RFC 7807 body.
- `GlobalExceptionHandler` enriches `ProblemDetail` with `type`, `instance`, and validation `fieldErrors`.


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
