import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("androidx.room")
}

android {
    namespace = "io.github.aedev.flow"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("integration/consumer-rules.pro")
        buildConfigField("int", "VERSION_CODE", "18")
        buildConfigField("String", "VERSION_NAME", "\"2.2.1\"")
        buildConfigField("int", "NIGHTLY_RUN", "0")
        buildConfigField("String", "FLAVOR", "\"embedded\"")
        buildConfigField("Boolean", "UPDATER_ENABLED", "false")
        buildConfigField("String", "DISCORD_APPLICATION_ID", "\"\"")
        buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
        buildConfigField("String", "LASTFM_API_KEY", "\"\"")
        buildConfigField("String", "LASTFM_API_SECRET", "\"\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true; compose = true }
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("app/src/main/java", "app/src/foss/java", "integration/java"))
            kotlin.setSrcDirs(listOf("app/src/main/java", "app/src/foss/java", "integration/java"))
            res.setSrcDirs(listOf("app/src/main/res", "integration/res"))
            assets.setSrcDirs(listOf("app/src/main/assets", "integration/assets"))
            manifest.srcFile("integration/AndroidManifest.xml")
        }
        getByName("test") {
            java.setSrcDirs(listOf("app/src/test/java", "app/src/testFoss/java"))
            kotlin.setSrcDirs(listOf("app/src/test/java", "app/src/testFoss/java"))
            resources.setSrcDirs(listOf("app/src/test/resources"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    packaging.resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES", "/META-INF/*.version")
    testOptions.unitTests { isReturnDefaultValues = true; isIncludeAndroidResources = true }
}
room { schemaDirectory("$projectDir/app/schemas") }
kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}
dependencies {
    // --- Core Android ---
    implementation(flowLibs.androidx.core.ktx)
    implementation(flowLibs.androidx.core.splashscreen)
    implementation(flowLibs.androidx.activity.compose)

    // --- Compose (Using BOM is best practice) ---
    implementation(platform(flowLibs.androidx.compose.bom))
    implementation(flowLibs.androidx.ui)
    implementation(flowLibs.androidx.ui.graphics)
    implementation(flowLibs.androidx.ui.tooling.preview)
    implementation(flowLibs.androidx.material3)
    implementation(flowLibs.androidx.material.icons.extended)

    // --- Navigation ---
    implementation(flowLibs.androidx.navigation.compose)

    // --- Lifecycle & Architecture ---
    implementation(flowLibs.androidx.lifecycle.viewmodel.compose)
    implementation(flowLibs.androidx.lifecycle.runtime.compose)
    implementation(flowLibs.androidx.window)
    implementation(flowLibs.androidx.window.core)
    implementation(flowLibs.androidx.material3.adaptive.layout)
    implementation(flowLibs.androidx.material3.adaptive.navigation)
    implementation(flowLibs.androidx.lifecycle.runtime.ktx)

    // --- Layouts ---
    implementation(flowLibs.androidx.constraintlayout.compose)

    // --- Image Loading ---
    implementation(flowLibs.coil.compose)
    implementation(flowLibs.compose.reorderable)
    implementation(flowLibs.coil.video)
    implementation(flowLibs.coil.network.okhttp)
    implementation("androidx.palette:palette-ktx:1.0.0")

    // --- Dependency Injection ---
    implementation(flowLibs.hilt.android)
    ksp(flowLibs.hilt.android.compiler)
    implementation(flowLibs.hilt.navigation.compose)

    // --- Data & Network ---
    implementation(flowLibs.newpipe.extractor)

    // Networking
    implementation(flowLibs.okhttp)

    // Ktor (Managed in flowLibs.versions.toml)
    implementation(flowLibs.ktor.client.core)
    implementation(flowLibs.ktor.client.okhttp)
    implementation(flowLibs.ktor.client.content.negotiation)
    implementation(flowLibs.ktor.serialization.kotlinx.json)
    implementation(flowLibs.ktor.client.encoding)

    // --- Device Sync (FLOW-SYNC/1) ---
    implementation(flowLibs.ktor.server.core) {
        exclude(group = "org.fusesource.jansi", module = "jansi")
    }
    implementation(flowLibs.ktor.server.cio)
    implementation(flowLibs.ktor.server.websockets)
    implementation(flowLibs.zxing.core)
    implementation(flowLibs.androidx.camera.core)
    implementation(flowLibs.androidx.camera.camera2)
    implementation(flowLibs.androidx.camera.lifecycle)
    implementation(flowLibs.androidx.camera.view)

    // Serialization & JSON
    implementation(flowLibs.kotlinx.serialization.json)
    implementation(flowLibs.gson)

    // conscrypt for OkHttp TLS support on older Android versions
    implementation(flowLibs.conscrypt.android)

    // --- Media Playback ---
    implementation(flowLibs.androidx.media3.exoplayer)
    implementation(flowLibs.androidx.media3.ui)
    implementation(flowLibs.androidx.media3.common)
    implementation(flowLibs.androidx.media3.session)
    implementation(flowLibs.androidx.media3.exoplayer.hls)
    implementation(flowLibs.androidx.media3.exoplayer.dash)
    implementation(flowLibs.androidx.media3.datasource)
    implementation(flowLibs.androidx.media3.datasource.okhttp)
    implementation(flowLibs.androidx.media3.muxer)
    implementation(flowLibs.androidx.media3.inspector)
    implementation(flowLibs.androidx.media)

    // --- Database & Storage ---
    implementation(flowLibs.androidx.room.runtime)
    implementation(flowLibs.androidx.room.ktx)
    ksp(flowLibs.androidx.room.compiler)

    implementation(flowLibs.androidx.datastore.preferences)
    // implementation(flowLibs.androidx.datastore) // In TOML if needed

    // --- Home-screen widgets (Jetpack Glance) ---
    implementation(flowLibs.androidx.glance.appwidget)
    implementation(flowLibs.androidx.glance.material3)
    implementation(flowLibs.androidx.graphics.shapes)

    // --- Async & Utils ---
    implementation(flowLibs.kotlinx.coroutines.android)
    implementation(flowLibs.androidx.paging.runtime.ktx)
    implementation(flowLibs.androidx.paging.compose)

    implementation(flowLibs.androidx.work.runtime.ktx)

    implementation(flowLibs.brotli)
    implementation(flowLibs.re2j)

    // --- Baseline profiles ---
    // Runtime installer for the merged baseline profile. AGP merges profiles shipped inside
    // library AARs (Compose, RecyclerView, ...) at build time; this applies them at runtime,
    // which matters for sideloaded/F-Droid installs that bypass Play's cloud profiles.
    implementation(flowLibs.androidx.profileinstaller)

    // Desugaring for older Android versions
    coreLibraryDesugaring(flowLibs.desugar.jdk.libs.nio)

    // --- Testing ---
    testImplementation(flowLibs.junit)
    testImplementation(flowLibs.kxml2)
    testImplementation(flowLibs.kotlinx.coroutines.test)
    testImplementation(flowLibs.mockk)
    testImplementation(flowLibs.truth)
    testImplementation(flowLibs.turbine)
    testImplementation(flowLibs.androidx.glance.appwidget.testing)
    testImplementation(flowLibs.hilt.android.testing)
    kspTest(flowLibs.hilt.android.compiler)

    // Compose UI tests in the JVM (Robolectric) so CI's unit-test task covers them
    testImplementation(flowLibs.robolectric)
    testImplementation(flowLibs.androidx.test.core)
    testImplementation(flowLibs.androidx.test.ext.junit)
    testImplementation(platform(flowLibs.androidx.compose.bom))
    testImplementation(flowLibs.androidx.ui.test.junit4)

    // Room migration tests (device-sync schema 20→23)
    androidTestImplementation(flowLibs.androidx.room.testing)
    androidTestImplementation(flowLibs.androidx.test.ext.junit)
    androidTestImplementation(flowLibs.androidx.espresso.core)
    androidTestImplementation(platform(flowLibs.androidx.compose.bom))
    androidTestImplementation(flowLibs.androidx.ui.test.junit4)
    androidTestImplementation(flowLibs.hilt.android.testing)
    kspAndroidTest(flowLibs.hilt.android.compiler)

    debugImplementation(flowLibs.androidx.ui.tooling)
    debugImplementation(flowLibs.androidx.ui.test.manifest)
}



ksp { arg("dagger.fastInit", "enabled") }
hilt { enableAggregatingTask = true }


apply(from = rootProject.file("tools/upstream-credentials.gradle"))
