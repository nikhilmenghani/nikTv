plugins {
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("com.android.application") version "9.3.2" apply false
    id("com.android.library") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("androidx.room") version "2.8.4" apply false
}

subprojects {
    configurations.configureEach {
        exclude(group = "com.android.support")
        resolutionStrategy.eachDependency {
            if (requested.group == "com.squareup.okhttp3") useVersion("5.4.0")
            if (requested.group == "androidx.media3") useVersion("1.11.0")
        }
    }
}

// Available in Android Studio's Gradle panel for device selection and installation.
tasks.register<Exec>("installLocalDebug") {
    group = "install"
    description = "Build and install NikTV Debug with this computer's GitHub token"
    dependsOn(":app:assembleDebug")
    val command = mutableListOf(
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        file("tools/install-local-debug.ps1").absolutePath, "-SkipBuild"
    )
    providers.gradleProperty("device").orNull?.let { command.addAll(listOf("-Device", it)) }
    commandLine(*command.toTypedArray())
}
