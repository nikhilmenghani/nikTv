import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

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
    id("com.google.dagger.hilt.android")
}

// Test-only manifests and package IDs must never overwrite Android Studio's normal debug outputs.
if (providers.gradleProperty("regressionBuild").orNull == "true") {
    layout.buildDirectory.set(layout.projectDirectory.dir("build-regression"))
}

// All native players share one process-level C++ runtime. Pin MPV's newer
// backwards-compatible runtime rather than picking an older transitive copy.
val mpvNativeRuntime = configurations.create("mpvNativeRuntime") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { add(mpvNativeRuntime.name, "dev.jdtech.mpv:libmpv:1.0.0") }
abstract class PrepareMpvNativeRuntime : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: org.gradle.api.file.RegularFileProperty
    @get:OutputDirectory
    abstract val outputDirectory: org.gradle.api.file.DirectoryProperty
    @get:javax.inject.Inject
    abstract val files: org.gradle.api.file.FileSystemOperations
    @get:javax.inject.Inject
    abstract val archives: org.gradle.api.file.ArchiveOperations

    @TaskAction fun extract() {
        files.sync {
            from(archives.zipTree(archive)) {
                include("jni/**/libc++_shared.so")
                eachFile { path = path.removePrefix("jni/") }
            }
            includeEmptyDirs = false
            into(outputDirectory)
        }
    }
}
val prepareMpvNativeRuntime = tasks.register<PrepareMpvNativeRuntime>("prepareMpvNativeRuntime") {
    archive.set(layout.file(mpvNativeRuntime.elements.map { it.single().asFile }))
    outputDirectory.set(layout.buildDirectory.dir("generated/mpv-jni"))
}

// dnsjava's Java-SE name-service plugin depends on sun.net APIs absent on Android.
// Keep its Android DNS implementation, without shipping the unsupported SPI or
// suppressing R8's missing-class checks for the rest of the application.
val dnsJavaAndroidSource = configurations.create("dnsJavaAndroidSource") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { add(dnsJavaAndroidSource.name, "dnsjava:dnsjava:2.1.9") }
abstract class PrepareAndroidDnsJava : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: org.gradle.api.file.RegularFileProperty
    @get:OutputFile
    abstract val output: org.gradle.api.file.RegularFileProperty

    @TaskAction fun prepare() {
        val target = output.get().asFile
        target.parentFile.mkdirs()
        ZipFile(archive.get().asFile).use { source ->
            ZipOutputStream(target.outputStream().buffered()).use { result ->
                source.entries().asSequence().filterNot {
                    it.name.startsWith("org/xbill/DNS/spi/") ||
                        it.name == "META-INF/services/sun.net.spi.nameservice.NameServiceDescriptor"
                }.forEach { entry ->
                    result.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(result) }
                    result.closeEntry()
                }
            }
        }
    }
}
val prepareAndroidDnsJava = tasks.register<PrepareAndroidDnsJava>("prepareAndroidDnsJava") {
    archive.set(layout.file(dnsJavaAndroidSource.elements.map { it.single().asFile }))
    output.set(layout.buildDirectory.file("generated/android-dnsjava/dnsjava-android.jar"))
}
configurations.configureEach {
    if (name != dnsJavaAndroidSource.name) exclude(group = "dnsjava", module = "dnsjava")
}
dependencies { add("implementation", files(prepareAndroidDnsJava.flatMap { it.output })) }
androidComponents.onVariants { variant ->
    variant.resValues.put(variant.makeResValueKey("string", "search_authority"),
        variant.applicationId.map { com.android.build.api.variant.ResValue("$it.smarttube", "Embedded SmartTube search") })
    variant.resValues.put(variant.makeResValueKey("string", "niktv_search_intent_data"),
        variant.applicationId.map { com.android.build.api.variant.ResValue("content://$it.smarttube/video", "Embedded SmartTube search") })
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareMpvNativeRuntime) { it.outputDirectory }
}

android {
    namespace = "com.nikhil.niktv"
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
    compileSdk = 37
    defaultConfig {
        applicationId = "com.nikhil.niktv"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "LOCAL_GITHUB_TOKEN", "\"\"")
        listOf("NIKTV_CRICBUZZ_AUTH_KEY", "NIKTV_CRICBUZZ_AUTH_MODE", "NIKTV_CRICBUZZ_APP_VERSION").forEach {
            buildConfigField("String", it, "\"\"")
        }
        buildConfigField("String", "NIKTUBE_BOTGUARD_API_KEY",
            providers.environmentVariable("NIKTUBE_BOTGUARD_API_KEY")
                .orElse(providers.gradleProperty("NIKTUBE_BOTGUARD_API_KEY"))
                .orElse("").get().asBuildConfigString())
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
            // Same package/signature/data for repeatable on-device performance tests.
            // Opt in explicitly; ordinary development builds stay debuggable.
            isDebuggable = !providers.gradleProperty("performanceBuild")
                .orNull.equals("true", ignoreCase = true)
            val localToken = if (embedLocalGithubToken) {
                providers.gradleProperty("G_TOKEN").orNull
                    ?: providers.gradleProperty("G_Token").orNull
                    ?: ""
            } else ""
            buildConfigField("String", "LOCAL_GITHUB_TOKEN", localToken.asBuildConfigString())
            // Local debug builds use Gradle values as a fallback; CI APKs fetch them from GitHub.
            listOf("NIKTV_CRICBUZZ_AUTH_KEY", "NIKTV_CRICBUZZ_AUTH_MODE", "NIKTV_CRICBUZZ_APP_VERSION").forEach { name ->
                val value = if (!providers.environmentVariable("GITHUB_ACTIONS").orNull.equals("true", ignoreCase = true) &&
                    !providers.gradleProperty("skipLocalCricketConfiguration").orNull.equals("true", ignoreCase = true)) {
                    providers.gradleProperty(name).orElse("").get()
                } else ""
                buildConfigField("String", name, value.asBuildConfigString())
            }
            applicationIdSuffix = if (providers.gradleProperty("regressionBuild").orNull == "true") ".regression" else ".debug"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "NikTV Dev"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_debug"
            manifestPlaceholders["appBanner"] = "@drawable/tv_banner_debug"
            if (providers.environmentVariable("DEV_KEYSTORE_PATH").isPresent) signingConfig = signingConfigs.getByName("automation")
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["appLabel"] = "NikTV"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appBanner"] = "@drawable/tv_banner"
            if (providers.environmentVariable("RELEASE_KEYSTORE_PATH").isPresent) signingConfig = signingConfigs.getByName("automation")
        }
    }
    if (providers.gradleProperty("regressionBuild").orNull == "true") {
        sourceSets.getByName("debug") {
            manifest.srcFile("src/regression/AndroidManifest.xml")
        }
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    lint {
        disable.addAll(listOf("MissingTvBanner", "OldTargetApi", "GradleDependency", "NewerVersionAvailable"))
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":niktube-flow"))
    implementation(project(":st-smarttubetv"))
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(project(":niktube-sabr"))
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
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
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
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
    implementation("org.videolan.android:libvlc-all:3.6.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
    // Expose the embedded networking API to its device smoke test; production already bundles it.
    androidTestImplementation(project(":st-sharedutils"))
    androidTestImplementation("org.chromium.net:cronet-api:143.7445.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":st-common"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
