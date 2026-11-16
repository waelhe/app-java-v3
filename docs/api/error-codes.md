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

### The documented exception: framework-level statuses

The framework's own protocol statuses — 405 Method Not Allowed, 406 Not
Acceptable, 415 Unsupported Media Type — answer with the OFFICIAL automatic
`ProblemDetail` body (`spring.mvc.problemdetails.enabled`, the framework
reference's "automatic before the manual" stance): RFC 9457 fields, no house
`errorCode`/`category` extensions. These statuses carry no house taxonomy
row (none appears in the table above) — the contract's extensions are the
HOUSE error taxonomy's own surface; protocol-level rejections are the
framework's. `BookingErrorContractWebMvcTest.methodNotSupportedOnBookingPath_
answers405ProblemDetailByTheAutomaticHandler_a03` pins this documented shape
(the assertion set includes the absence of the house extensions).
