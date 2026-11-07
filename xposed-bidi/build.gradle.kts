plugins {
    alias(libs.plugins.android.application)
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
    // libxposed API stubs are compile-only (provided by the framework inside hooked processes).
    // Pinned to 101: api 102 needs compileSdk 37, this project builds against 36.
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly("io.github.libxposed:api:101.0.1")
}
