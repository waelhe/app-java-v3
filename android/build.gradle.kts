plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 uses built-in Kotlin; keep the Compose compiler on its matching KGP version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // Apollo Kotlin codegen (GraphQL Phase 5 — docs/api/graphql-target-
    // architecture.md §11-Phase-5): operation-specific Kotlin models
    // generated from the CI-published SDL contract. Measured from Maven
    // Central (latest GA) and the official v5 docs: plugin id
    // com.apollographql.apollo, Gradle 8.0+ / KGP 2.1.0+ / AGP 8.0.0+ —
    // this build's Gradle 9.7.1 / KGP 2.4.21 / AGP 9.4.1 all satisfy it.
    id("com.apollographql.apollo") version "5.2.0" apply false
}
