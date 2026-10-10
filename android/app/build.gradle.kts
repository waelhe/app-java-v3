import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
    .orElse("https://app-java-v3-staging-staging.up.railway.app/")
    .get()
    .let { if (it.endsWith("/")) it else "$it/" }
val oidcIssuer = providers.gradleProperty("oidcIssuer")
    .orElse("https://app-java-v3-staging-staging.up.railway.app")
    .get()
    .removeSuffix("/")
val oidcClientId = providers.gradleProperty("oidcClientId").orElse("").get()
val oidcRedirectUri = providers.gradleProperty("oidcRedirectUri")
    .orElse("com.marketplace.android:/oauth2redirect")
    .get()
val redirectScheme = oidcRedirectUri.substringBefore(":")

fun String.asBuildConfigString(): String = listOf('"', this, '"').joinToString("")

extensions.configure<ApplicationExtension> {
    namespace = "com.marketplace.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.marketplace.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = redirectScheme
        buildConfigField("String", "API_BASE_URL", apiBaseUrl.asBuildConfigString())
        buildConfigField("String", "OIDC_ISSUER", oidcIssuer.asBuildConfigString())
        buildConfigField("String", "OIDC_CLIENT_ID", oidcClientId.asBuildConfigString())
        buildConfigField("String", "OIDC_REDIRECT_URI", oidcRedirectUri.asBuildConfigString())
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
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.openid:appauth:0.11.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
