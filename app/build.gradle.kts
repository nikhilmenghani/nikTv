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

val fireTvApk = providers.gradleProperty("fireTvApk")
    .map(String::toBoolean).orElse(false)
val embedLocalGithubToken = !providers.environmentVariable("GITHUB_ACTIONS")
    .orNull.equals("true", ignoreCase = true) &&
    !providers.gradleProperty("skipLocalGithubToken").orNull.equals("true", ignoreCase = true)

plugins {
    id("com.google.devtools.ksp")
    id("com.android.application")
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
        buildConfigField("String", "LOCAL_GITHUB_TOKEN", "\"\"")
        if (fireTvApk.get()) {
            ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a") }
        }
    }
    splits {
        abi {
            isEnable = !fireTvApk.get()
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = !fireTvApk.get()
        }
    }
    signingConfigs {
        create("automation") {
            val path = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull
                ?: providers.environmentVariable("DEV_KEYSTORE_PATH").orNull
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                    ?: providers.environmentVariable("DEV_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                    ?: providers.environmentVariable("DEV_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
                    ?: providers.environmentVariable("DEV_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        getByName("debug") {
            val localToken = if (embedLocalGithubToken) {
                providers.gradleProperty("G_TOKEN").orNull
                    ?: providers.gradleProperty("G_Token").orNull
                    ?: ""
            } else ""
            buildConfigField("String", "LOCAL_GITHUB_TOKEN", localToken.asBuildConfigString())
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "NikTV Dev"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_debug"
            manifestPlaceholders["appBanner"] = "@drawable/tv_banner_debug"
            if (providers.environmentVariable("DEV_KEYSTORE_PATH").isPresent) signingConfig = signingConfigs.getByName("automation")
        }
        getByName("release") {
            manifestPlaceholders["appLabel"] = "NikTV"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appBanner"] = "@drawable/tv_banner"
            if (providers.environmentVariable("RELEASE_KEYSTORE_PATH").isPresent) signingConfig = signingConfigs.getByName("automation")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint {
        disable.addAll(listOf("MissingTvBanner", "OldTargetApi", "GradleDependency", "NewerVersionAvailable"))
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
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
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-cast:1.8.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    implementation("androidx.media3:media3-database:1.8.0")
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("org.videolan.android:libvlc-all:3.6.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
