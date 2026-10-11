plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val apiBaseUrl = providers.gradleProperty("apiBaseUrl").orElse(providers.environmentVariable("DAYF_API_BASE_URL")).orElse("https://app-java-v3-production.up.railway.app").get()
val authIssuer = providers.gradleProperty("authIssuer").orElse(providers.environmentVariable("AUTH_SERVER_ISSUER")).orElse("https://app-java-v3-production.up.railway.app").get()
val publicClientId = providers.gradleProperty("oauthPublicClientId").orElse(providers.environmentVariable("OAUTH_PUBLIC_CLIENT_ID")).orElse("").get()
val redirectUri = providers.gradleProperty("oauthRedirectUri").orElse(providers.environmentVariable("DAYF_OAUTH_REDIRECT_URI")).orElse("com.marketplace.dayf:/oauth2redirect").get()

android {
    namespace = "com.marketplace.dayf"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.marketplace.dayf"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "AUTH_ISSUER", "\"$authIssuer\"")
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"$publicClientId\"")
        buildConfigField("String", "OAUTH_REDIRECT_URI", "\"$redirectUri\"")
        manifestPlaceholders["appAuthRedirectScheme"] = redirectUri.substringBefore(':')
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("net.openid:appauth:0.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
