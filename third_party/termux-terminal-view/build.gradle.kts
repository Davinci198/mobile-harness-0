// Copied from termux-app/terminal-view (GPL) — see PLAN-TERMINAL.md.
// Adapted to Mobile-Harness build settings.
plugins { id("com.android.library") }

android {
    namespace = "com.termux.view"
    compileSdk = 36

    defaultConfig { minSdk = 28 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
    api(project(":third_party:termux-terminal-emulator"))
}
