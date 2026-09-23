plugins {
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
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
