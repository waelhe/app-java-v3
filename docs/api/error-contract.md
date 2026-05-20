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

## Marketplace error type registry

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
