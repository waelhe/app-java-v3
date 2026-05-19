# Chaos & Timeout Test Plan (Payments + Messaging)

## Scope
- `marketplace-payments`
- `marketplace-messaging`

## Scenarios

### Payments
1. Circuit breaker open during payment processing
   - Expected: `503` + `circuit-breaker-open`.
2. Rate limiter rejection for payment API
   - Expected: `429` + `rate-limited`.
3. Slow downstream payment provider simulation (timeout)
   - Expected: service returns fallback/error without hanging threads.

### Messaging
1. Invalid message payload contract
   - Expected: `400` + `bad-request`.
2. Broker/backpressure simulation
   - Expected: no crash; controlled error surface and log signal.
3. Retry saturation case
   - Expected: deterministic failure response and metrics increments.

## Execution Commands
```bash
mvn -pl marketplace-shared -am test
mvn -pl marketplace-payments -am test
mvn -pl marketplace-messaging -am test
```

## Acceptance Gates
- No unhandled exceptions in targeted chaos tests.
- Error responses match unified taxonomy in `docs/error-code-policy.md`.
- Timeouts fail fast and do not block request threads indefinitely.
