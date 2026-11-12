import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Same key source as :app: local.properties carries the signing keys (CI writes them from
// repository secrets). Without them the build falls back to the debug key, which changes on
// every runner — so a build signed that way cannot be installed over a previous one.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("local.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.reader())
}

val hasKeystore = keystoreProperties.getProperty("storeFile", ".") != "."

android {
    namespace = "com.newoether.agora.bidi"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.newoether.agora.bidi"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "."))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            // Fixed key from local.properties when configured, debug key otherwise.
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
