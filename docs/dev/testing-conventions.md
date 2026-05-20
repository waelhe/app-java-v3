# Testing Conventions

## Test naming

- **Unit tests** must use the `*Test` suffix.
  - Executed by `maven-surefire-plugin` during the `test` phase.
- **Integration tests** must use one of these suffixes:
  - `*IT`
  - `*IntegrationTest`
  - Executed by `maven-failsafe-plugin` during the `integration-test` and `verify` phases.

## CI execution

CI runs `mvn verify` (via Maven Wrapper) so both unit and integration tests are executed in the standard Maven lifecycle.
