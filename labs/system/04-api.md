# 04 — API Surface (isolated system map)

> **Full endpoint table + versioning + errors + OpenAPI.** Citations are `file:line`.

## 1. Base paths

- MUST | `API_V1=/api/v1` + constants: users, listings, bookings, pricing, payments, reviews, messages, search, admin (`ApiConstants.java:8-18`); geo/property/admin-geo use literals. | [code] | ApiConstants.java:8-18

## 2. Endpoint table (method + path + gate)

- MUST | identity: `GET /users/me`, `GET /users/me/export` — authenticated + OIDC bootstrap. | [code] | UserController.java:34,55
- MUST | catalog: `GET /listings` (+`/category/{c}`, `/provider/{p}`, `/{id}`) — permitAll + rate limit; `POST /listings`, `PUT /{id}`, `/{id}/activate`, `/renew`, `/pause`, `/archive` — PROVIDER (+owner) / archive also ADMIN. | [code] | CatalogController.java:46-155 · CatalogService.java:392-544
- MUST | realestate: `PUT /listings/{id}/property` — PROVIDER + ownership; `GET .../property` — permitAll (rides listings rule). | [code] | PropertyController.java:34,48
- MUST | booking: `GET /{id}`, `/consumer/{c}`, `/provider/{p}` — authenticated participant-scoped; `POST /` (CONSUMER + RL), `/{id}/confirm`, `/complete` (PROVIDER/ADMIN + owner), `/{id}/cancel` (CONSUMER/PROVIDER + participant). | [code] | BookingController.java:34-92
- MUST | pricing: `/rules/**` — ADMIN, NO version; `/listings/{id}/calendar/**` — PROVIDER/ADMIN + ownership; `/convert` — authenticated, NO version, 503 SU-001 when unbound. | [code] | PricingRuleController.java:19-60 · ListingPriceCalendarController.java:51-117 · CurrencyExchangeController.java:25-38
- MUST | payments: intents get/create/process/cancel (CONSUMER + owner), confirm/refund (ADMIN); `POST /webhooks/{provider}` + `/webhooks/stripe` — permitAll by contract (signature-gated). | [code] | PaymentsController.java:32-98
- MUST | reviews: 4 GETs — permitAll; `POST /` (CONSUMER + booking-consumer + COMPLETED + RL), `/reverse` (PROVIDER + booking-provider), `PUT /{id}` (author-or-admin), `/{id}/reply` (PROVIDER, reviewed-provider-only, no admin bypass). | [code] | ReviewsController.java:36-125
- MUST | messaging: 6 endpoints — authenticated participants (no controller roles; service enforces). | [code] | MessagingController.java:36-88
- MUST | media: `POST /uploads` (PROVIDER + owner + RL), `/{id}/complete` (PROVIDER + owner), `GET /listings/{id}` (authenticated, no PreAuthorize), `DELETE /{id}` (PROVIDER/ADMIN + owner). | [code] | MediaController.java:47-86
- MUST | notifications: feed/read/preferences — authenticated self. | [code] | NotificationController.java:26-58
- MUST | provider: `POST /providers` (CONSUMER), `GET /{id}` (authenticated — NOT permitAll), `PUT /{id}` (PROVIDER + owner/admin), `/admin/providers/{id}/verify`, `/suspend` (ADMIN); `/providers/me/stats` (provider-self, 404 without profile). | [code] | ProviderController.java:28-59 · ProviderStatsController.java:49-82
- MUST | ledger: `/admin/ledger/...` (ADMIN); `/providers/me/ledger/balance`, `/statement` (provider-self + ownsProvider). | [code] | LedgerController.java:20-29 · ProviderLedgerController.java:51-59
- MUST | availability: 4 endpoints under `/providers/{providerId}/availability/**` — `ownsProvider` enforced in service. | [code] | AvailabilityController.java:27-66
- MUST | search: 2 GETs — permitAll + rate limit (full filter set incl. geo radius). | [code] | SearchController.java:28-124
- MUST | disputes: `POST /bookings/{id}/disputes`, `GET` (participant-or-ADMIN), `POST /admin/disputes/{id}/resolve` (ADMIN). | [code] | DisputeController.java:27-49
- MUST | geo: `/tree`, `/{id}/children`, `/suggest` — permitAll + rate limit; `/admin/geo/**` — ADMIN (class-level). | [code] | GeoController.java:34-65 · GeoAdminController.java:33-65
- MUST | admin (app): users/listings/bookings/payments/revisions + pseudonymize/purge-content/purge-audit-history — ADMIN (class-level). | [code] | AdminController.java:27-210

## 3. Versioning

- MUST | Path `/api/v1` hardcoded AND header versioning coexist: `X-API-Version` header, default `1.0` (missing header → 1.0, not 400). | [code] | ApiConstants.java:8 · ApiVersioningConfig.java:38-45
- ATTEND | All controllers declare `version="1.0"` EXCEPT `PricingRuleController` and `CurrencyExchangeController` (unversioned); no 1.1+ mappings exist. | [code] | (controllers)
- ATTEND | CORS allowlist includes `X-API-Version`. | [code] | SecurityConfig.java:208

## 4. Error contract

- MUST | Single `@RestControllerAdvice` (`GlobalExceptionHandler`) → RFC 7807 `ProblemDetail` (+ codes VAL-001/AUTHZ-001/AUTHN-001/NF-001/CONFLICT-001/RL-001/SU-001/INT-001); Boot `problemdetails.enabled=true`. | [code] | GlobalExceptionHandler.java:46-217 · ApiErrorTaxonomy.java:10-37 · application.yml:61-63
- MUST | `detail` NEVER leaks internals — catch-all/circuit/integrity paths return fixed generic messages; 401/403 use fixed literals. | [code] | GlobalExceptionHandler.java:128-217 · SecurityConfig.java:266-319
- ATTEND | Error docs: `docs/api/error-contract.md` + `docs/api/error-codes.md` (omits SU-001 — doc drift); GraphQL uses `errors[]+extensions`, NOT ProblemDetail. | [doc] | docs/api/error-contract.md:79-120

## 5. OpenAPI / pagination / realtime

- MUST | springdoc 3.1.0, single ungrouped spec at default `/v3/api-docs` (permitAll GET); `defaultProblemResponsesCustomizer` injects 400/401/403/404/409/429/500/503. | [code] | OpenApiConfig.java:23-98 · SecurityConfig.java:147
- ATTEND | Path count is runtime-only (CI boots the jar); ≈70 REST ops + actuator/oauth2. | [test] | ci.yml (OpenAPI gate)
- MUST | Lists return `PagedResponse<T>` (page 100 max) EXCEPT geo tree/children/suggest, dispute list, notification feed, media-by-listing, pricing-rules (deliberate Lists). | [code] | PagedResponse.java:10-27 · application.yml:111-113
- MUST | Search sort whitelist: price/newest/area/distance only (else 400); deterministic `id ASC` tiebreak. | [code] | SearchSorts.java:63-99
- MUST | STOMP: `/ws` + `/app/**` authenticated, `/topic/notifications/{userId}` self-only, conversations via subscription manager, rest denyAll. | [code] | WebSocketConfig.java:12-38 · WebSocketSecurityConfig.java:22-34
- MUST | GraphQL: `service/services/createService` (PROVIDER) over HTTP `/graphql` (authenticated) + `/graphql/ws`; GraphiQL on non-prod. | [code] | ServiceGraphQlController.java:17-54 · application.yml:65-74