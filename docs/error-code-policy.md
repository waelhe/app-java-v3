# Error Code Policy (Unified Across Controllers)

## Objective
Standardize API errors across all REST controllers using RFC 7807 `ProblemDetail` and stable error type taxonomy.

## Canonical Mapping

| HTTP | Error type suffix | When used |
|---|---|---|
| 400 | `bad-request` | Invalid arguments / malformed business input |
| 400 | `validation` | Bean validation (`MethodArgumentNotValidException`) |
| 400 | `constraint-violation` | Constraint validation failures |
| 401 | `unauthorized` | Authentication required |
| 403 | `access-denied` | Authenticated but forbidden |
| 404 | `not-found` | Missing resource/endpoint |
| 409 | `conflict` | Illegal state transition |
| 409 | `optimistic-lock` | Concurrent update conflict |
| 429 | `rate-limited` | Rate limiter rejected request |
| 503 | `circuit-breaker-open` | Downstream protected service unavailable |
| 500 | `internal-error` | Unhandled exceptions |

Base URI prefix: `https://marketplace.com/errors/`

## Controller Requirements
1. Controllers must throw domain exceptions or validation errors only; no ad-hoc error bodies.
2. Global handler is the single serialization point for HTTP error payloads.
3. Any new error family must be added centrally and documented in this table.

## Verification
- Unit tests for `GlobalExceptionHandler` must verify status + type URI for critical resilience/error paths.
- Controller tests must assert expected status code for negative paths.
