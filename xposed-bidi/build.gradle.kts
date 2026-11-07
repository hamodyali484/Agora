plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.newoether.agora.bidi"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.newoether.agora.bidi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Signed with the debug key so CI output installs with no secrets configured.
            signingConfig = signingConfigs.getByName("debug")
            // Tiny module, no shrinking: keeps the entry class and META-INF/xposed/* intact.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // API stubs are compile-only (provided by the framework inside hooked processes).
    // Pinned to 101: api 102 needs compileSdk 37, this project builds against 36.
    compileOnly("io.github.libxposed:api:101.0.1")
    // Compile-only: TextStyle/TextDirection for the Compose hook; the classes come from
    // the target app at runtime. BOM version tracks the app's own Compose.
    // (No Compose compiler plugin: this module has no @Composable code, plain Kotlin suffices.)
    compileOnly(platform("androidx.compose:compose-bom:${libs.versions.composeBom.get()}"))
    compileOnly("androidx.compose.ui:ui-text-android")
}
