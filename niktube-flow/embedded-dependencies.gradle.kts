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
    coreLibraryDesugaring(flowLibs.desugar.jdk.flowLibs.nio)

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


