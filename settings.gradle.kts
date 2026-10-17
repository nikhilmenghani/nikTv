pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
    versionCatalogs { create("flowLibs") { from(files("niktube-flow/gradle/libs.versions.toml")) } }
}
rootProject.name = "nikTv"
include(":app")
include(":niktube-sabr")
include(":niktube-flow")
project(":niktube-flow").buildFileName = "embedded.gradle.kts"
apply(from = "niktube-smarttube/embedded-settings.gradle")
