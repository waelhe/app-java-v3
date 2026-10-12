import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
    .orElse("https://app-java-v3-production-59bf.up.railway.app/")
    .get()
    .let { if (it.endsWith("/")) it else "$it/" }
val oidcIssuer = providers.gradleProperty("oidcIssuer")
    .orElse("https://app-java-v3-production-59bf.up.railway.app")
    .get()
    .removeSuffix("/")
// Client IDs are public OAuth configuration, not secrets. Gradle property takes precedence;
// the matching GitHub Actions repository variable can supply the same value to CI builds.
val oidcClientId = providers.gradleProperty("oidcClientId")
    .orElse(providers.environmentVariable("OAUTH_PUBLIC_CLIENT_ID"))
    .orElse("")
    .get()
val oidcRedirectUri = providers.gradleProperty("oidcRedirectUri")
    .orElse("com.marketplace.android:/oauth2redirect")
    .get()
val mediaMaxUploadBytes = providers.gradleProperty("mediaMaxUploadBytes")
    .map { it.toLong() }
    .orElse(10L * 1024 * 1024)
    .get()
require(mediaMaxUploadBytes > 0L) { "mediaMaxUploadBytes must be a positive number of bytes." }
val redirectScheme = oidcRedirectUri.substringBefore(":")
val releaseKeystorePath = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull

fun String.asBuildConfigString(): String = listOf('"', this, '"').joinToString("")

extensions.configure<ApplicationExtension> {
    namespace = "com.marketplace.android"
    compileSdk = 36

    signingConfigs {
        create("release") {
            releaseKeystorePath?.takeIf(String::isNotBlank)?.let { storeFile = rootProject.file(it) }
            releaseKeystorePassword?.let { storePassword = it }
            releaseKeyAlias?.let { keyAlias = it }
            releaseKeyPassword?.let { keyPassword = it }
        }
    }

    defaultConfig {
        applicationId = "com.marketplace.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = redirectScheme
        buildConfigField("String", "API_BASE_URL", apiBaseUrl.asBuildConfigString())
        buildConfigField("String", "OIDC_ISSUER", oidcIssuer.asBuildConfigString())
        buildConfigField("String", "OIDC_CLIENT_ID", oidcClientId.asBuildConfigString())
        buildConfigField("String", "OIDC_REDIRECT_URI", oidcRedirectUri.asBuildConfigString())
        buildConfigField("long", "MEDIA_MAX_UPLOAD_BYTES", "${mediaMaxUploadBytes}L")
    }

    buildTypes {
        getByName("release") {
            // Release builds are signed only with the explicitly supplied release key.
            // The preflight task below refuses missing credentials; no debug-key fallback.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.4.0"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Compose BOM pinned to the newest release whose artifacts accept compileSdk 36
    // (2026.06.01 -> Compose 1.11.4, minCompileSdk 35). The 2026.09.00 BOM's Compose
    // 1.12.1 artifacts require compileSdk 37, and the android-37 platform is not
    // published in the SDK channels (CI measured: "Failed to find package
    // 'platforms;android-37'") — the same stable-36 alignment applied to Coil 3.5.0.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-moshi:3.0.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.squareup.okhttp3:okhttp")
    implementation("net.openid:appauth:0.11.1")
    // Official Android Credential Manager passkey APIs (latest stable release).
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}


val releaseSigningValues = mapOf(
    "ANDROID_RELEASE_KEYSTORE_PATH" to releaseKeystorePath,
    "ANDROID_RELEASE_KEYSTORE_PASSWORD" to releaseKeystorePassword,
    "ANDROID_RELEASE_KEY_ALIAS" to releaseKeyAlias,
    "ANDROID_RELEASE_KEY_PASSWORD" to releaseKeyPassword
)

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        val missing = releaseSigningValues.filterValues { it.isNullOrBlank() }.keys
        check(missing.isEmpty()) {
            "Release signing is disabled. Configure the required GitHub Actions release-signing secrets; missing: ${missing.joinToString()}."
        }
        check(project.rootProject.file(releaseKeystorePath!!).isFile) {
            "ANDROID_RELEASE_KEYSTORE_PATH does not point to an existing release keystore."
        }
    }
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease" ||
        name.startsWith("packageRelease")) {
        dependsOn(verifyReleaseSigning)
    }
}
