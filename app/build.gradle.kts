fun String.withoutPropertyQuotes(): String {
    val value = trim()
    return when {
        value.length >= 2 && value.first() == '"' && value.last() == '"' -> value.substring(1, value.length - 1)
        value.length >= 2 && value.first() == '\'' && value.last() == '\'' -> value.substring(1, value.length - 1)
        else -> value
    }.trim()
}

fun String.asBuildConfigString(): String {
    val value = withoutPropertyQuotes()
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val defaultProfileName = providers.gradleProperty("NIKTV_DEFAULT_PROFILE_NAME")
    .orElse(providers.environmentVariable("NIKTV_DEFAULT_PROFILE_NAME")).orElse("")
val defaultPortalUrl = providers.gradleProperty("NIKTV_DEFAULT_PORTAL_URL")
    .orElse(providers.environmentVariable("NIKTV_DEFAULT_PORTAL_URL")).orElse("")
val defaultMacAddress = providers.gradleProperty("NIKTV_DEFAULT_MAC_ADDRESS")
    .orElse(providers.environmentVariable("NIKTV_DEFAULT_MAC_ADDRESS")).orElse("")
val defaultSerialNumber = providers.gradleProperty("NIKTV_DEFAULT_SERIAL_NUMBER")
    .orElse(providers.environmentVariable("NIKTV_DEFAULT_SERIAL_NUMBER")).orElse("")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.nikhil.niktv"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.nikhil.niktv"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_PROFILE_NAME", defaultProfileName.get().asBuildConfigString())
        buildConfigField("String", "DEFAULT_PORTAL_URL", defaultPortalUrl.get().asBuildConfigString())
        buildConfigField("String", "DEFAULT_MAC_ADDRESS", defaultMacAddress.get().asBuildConfigString())
        buildConfigField("String", "DEFAULT_SERIAL_NUMBER", defaultSerialNumber.get().asBuildConfigString())
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
