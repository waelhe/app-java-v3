plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 uses built-in Kotlin; keep the Compose compiler on its matching KGP version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
