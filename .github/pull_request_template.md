## Summary
- What changed?
- Why now?

## Scope classification
- [ ] Docs only
- [ ] Bug fix
- [ ] Feature
- [ ] Refactor
- [ ] Security-sensitive change

## Official references used (required)
- [ ] Spring Boot Reference: https://docs.spring.io/spring-boot/reference/index.html
- [ ] Spring Boot System Requirements: https://docs.spring.io/spring-boot/system-requirements.html
- [ ] Spring Boot Build Systems (Maven): https://docs.spring.io/spring-boot/reference/using/build-systems.html
- [ ] Spring Guides: https://spring.io/guides
- [ ] Other official sources (list links below)

Links used:
- 

## Functional changes
- 

## API / OpenAPI impact
- [ ] No API contract change
- [ ] API contract changed (documented)
- [ ] OpenAPI updated
- [ ] RFC 7807 ProblemDetail behavior verified

Details:
- 

## Database migrations
- [ ] No schema change
- [ ] Added migration(s) under `marketplace-app/src/main/resources/db/migration`

Migration files:
- 

## Security and authorization
- [ ] Not applicable
- [ ] Authorization rules updated
- [ ] Negative access tests added (401/403)

Details:
- 


## Secrets & config hardening (JIRA-SEC-02)
- [ ] No secrets were added to git history or tracked files
- [ ] Secret injection uses env vars or secret manager only
- [ ] `application*.yml` reviewed for sensitive defaults
- [ ] Production-safe config verified (actuator exposure, management port, log redaction)
- [ ] CI secret scan (`gitleaks`) passes with high/critical fail policy

## Testing executed
- [ ] `mvn clean verify`
- [ ] `mvn -pl marketplace-app -am test`
- [ ] Local run profile check (`spring-boot:run -Dspring-boot.run.profiles=dev`)
- [ ] Added/updated unit tests for changed behavior

Commands + results:
- 

## Modulith / architecture checks
- [ ] Modulith verification passed
- [ ] Architecture rules passed

## Dependency governance
- [ ] No manual version added for artifacts managed by Boot BOM
- [ ] New dependency compatibility validated with Spring Boot 4.x
- [ ] If manual version was added, exception is documented with owner and review date

## Rollout / rollback readiness
- [ ] Rollout plan reviewed (`docs/release/rollout-strategy.md`)
- [ ] Rollback trigger and owner identified
- [ ] Canary suitability assessed for risky changes

## Deviations (required if any)
Describe any deviation from official docs or project governance and why:
- 

## Follow-up tasks
- [ ] No follow-up required
- [ ] Follow-up issue(s) created and linked

Links:
- 
