import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The plugin API (contract, capability model + discovery rules, IPC, base
// service) compiles straight from the host repo, staged here so only those
// files travel — the sample can never drift from the contract Mobile Harness
// enforces, and host integration internals stay out of the plugin APK.
val syncPluginApi = tasks.register<Sync>("syncPluginApiSources") {
    from(rootProject.file("../../app/src/main/java/com/jarves/mh/plugins")) {
        include(
            "PluginContract.kt",
            "PluginDiscovery.kt",
            "PluginValidator.kt",
            "PluginIpc.kt",
            "PluginToolHostService.kt",
        )
    }
    into(layout.buildDirectory.dir("generated/plugin-api-src"))
}

tasks.withType<KotlinCompile>().configureEach { dependsOn(syncPluginApi) }

android {
    namespace = "com.jarves.mh.sample.plugin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarves.mh.sample.plugin"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/plugin-api-src"))

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
}
