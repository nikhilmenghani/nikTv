plugins {
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("com.android.application") version "9.3.2" apply false
    id("com.android.library") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("androidx.room") version "2.8.4" apply false
}

subprojects {
    configurations.configureEach {
        exclude(group = "com.android.support")
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.grack:nanojson")).using(module("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996"))
        }
        resolutionStrategy.eachDependency {
            if (requested.group == "com.squareup.okhttp3") useVersion("5.4.0")
            if (requested.group == "androidx.media3") useVersion("1.11.0")
            // SmartTube's upstream Android 4.4 pin bundles a 4 KB-only native library.
            // Keep Cronet API, implementation and providers on one 16 KB-compatible release.
            if (requested.group == "org.chromium.net") useVersion("143.7445.0")
        }
    }
}

val prepareEmbeddedSmartTube = tasks.register<Exec>("prepareEmbeddedSmartTube") {
    commandLine("python", file("tools/prepare-smarttube.py").absolutePath)
    inputs.file("tools/prepare-smarttube.py")
    inputs.files("niktube-smarttube/LICENSE", "niktube-smarttube/upstream-provenance.json", "app/src/main/res/drawable/niktv_logo_foreground.png")
    inputs.files(fileTree("niktube-smarttube") {
        include("**/src/main/**", "**/build.gradle", "SharedModules/constants.gradle")
        exclude("**/build/**", "**/.git/**")
    })
    outputs.dirs(provider { subprojects.filter { it.name.startsWith("st-") }.map { it.layout.buildDirectory.dir("embedded").get().asFile } })
}
val prepareEmbeddedFlow = tasks.register<Exec>("prepareEmbeddedFlow") {
    commandLine("python", file("tools/prepare-flow.py").absolutePath)
    inputs.files("tools/prepare-flow.py", "niktube-flow/app/src/main/AndroidManifest.xml", "niktube-flow/app/proguard-rules.pro", "niktube-flow/License", "niktube-flow/upstream-provenance.json")
    outputs.file("niktube-flow/integration/AndroidManifest.xml")
    outputs.dir("niktube-flow/integration/assets/licenses")
    outputs.file("niktube-flow/integration/consumer-rules.pro")
    inputs.file("app/src/main/res/drawable/niktv_logo_foreground.png")
    outputs.file("niktube-flow/integration/res/drawable/niktube_host_logo.png")
    inputs.file("niktube-flow/app/src/main/res/values/themes.xml")
    outputs.file("app/src/main/res/values/embedded_flow_splash.xml")
    outputs.file("niktube-flow/app/src/main/java/io/github/aedev/flow/ui/components/shared/FlowLogo.kt")
    outputs.file("niktube-flow/app/src/main/java/io/github/aedev/flow/ui/screens/home/FlowHeaderLogoIcon.kt")
}
subprojects {
    tasks.configureEach {
        if (name == "preBuild" && project.name.startsWith("st-")) dependsOn(prepareEmbeddedSmartTube)
        if (name == "preBuild" && project.name == "niktube-flow") dependsOn(prepareEmbeddedFlow)
        if (name == "preBuild" && project.name == "app") dependsOn(prepareEmbeddedFlow)
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
