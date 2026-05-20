# API Error Taxonomy & Codes

Canonical error code mapping for Marketplace REST APIs.

| category | errorCode | HTTP status | title | type URI |
|---|---|---:|---|---|
| validation | `VAL-001` | 400 | Bad Request | `https://marketplace.com/errors/validation` |
| authz | `AUTHN-001` | 401 | Unauthorized | `https://marketplace.com/errors/unauthorized` |
| authz | `AUTHZ-001` | 403 | Forbidden | `https://marketplace.com/errors/access-denied` |
| not-found | `NF-001` | 404 | Not Found | `https://marketplace.com/errors/not-found` |
| conflict | `CONFLICT-001` | 409 | Conflict | `https://marketplace.com/errors/conflict` |
| rate-limit | `RL-001` | 429 | Too Many Requests | `https://marketplace.com/errors/rate-limited` |
| internal | `INT-001` | 500 | Internal Server Error | `https://marketplace.com/errors/internal-error` |

## ProblemDetail extensions

All REST errors include:

- `errorCode`: stable machine-readable code.
- `category`: error taxonomy category.
- `userMessage` (optional): user-facing fallback message if distinct from `detail`.
