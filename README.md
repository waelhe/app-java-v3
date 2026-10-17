# GraphQL contract distribution branch

This branch is the published GraphQL contract channel (Phase 5 of
docs/api/graphql-target-architecture.md). It is written ONLY by
.ci/publish-graphql-contract.sh, invoked by
.github/workflows/graphql-contract.yml, which publishes the runtime SDL
dumped and gated by a successful CI run on main — never the production
server, never a hand-copied file. Do not open pull requests against this
branch and do not hand-edit it; the next publication would overwrite the
hand edit and the schema-compatibility gate owns the contract anyway.

Files:
- schema.graphqls — the accepted SDL (the version this manifest records).
- manifest.json   — version, publishedAt, sourceCommit, sourceRun,
  schemaSha256. The version advances only when the SDL actually changes.
- The git history of this branch is the contract's versioned changelog:
  every version is a commit whose parent is the previous version.

Consumers:
- Android fetches the SDL at
  https://raw.githubusercontent.com/waelhe/app-java-v3/graphql-contract/schema.graphqls
  (see android/README.md — GraphQL contract consumption). The file is
  staged by CI before Gradle runs; it is gitignored in the Android tree.
- CI's android-graphql job validates the Apollo Kotlin operations against
  the run's own runtime-schema dump (the same publication this branch
  advances), so an incompatible schema change or a stale operation fails
  the required "Build & Test (JDK 25)" check before release.
