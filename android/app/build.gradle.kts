plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hexnext.themer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hexnext.themer"
        // Public FabricatedOverlay / OverlayManagerTransaction APIs for One UI 7/8/9.0 (Android 14–16 / SDK 36).
        minSdk = 34
        targetSdk = 36
        versionCode = 210
        versionName = "2.1.0-OneUI9-Universal-EN"

        // English-only release configuration
        resourceConfigurations += listOf("en")

        // Support both 64-bit (arm64-v8a, x86_64) and 32-bit (armeabi-v7a, x86) architectures
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            // Always generate a single Universal APK supporting both 64-bit and 32-bit devices
            isUniversalApk = true
        }
    }

    signingConfigs {
        // Committed keystore keeps CI and local release builds signed and installable over each other.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = System.getenv("HEXNEXT_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("HEXNEXT_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("HEXNEXT_KEY_PASSWORD") ?: "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        create("release") {
            val customKeystore = System.getenv("HEXNEXT_KEYSTORE_PATH")
            storeFile = if (!customKeystore.isNullOrBlank() && file(customKeystore).exists()) {
                file(customKeystore)
            } else {
                file("debug.keystore")
            }
            storePassword = System.getenv("HEXNEXT_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("HEXNEXT_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("HEXNEXT_KEY_PASSWORD") ?: "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Material color utilities are marked @RestrictTo but are the reference Monet implementation.
        disable += "RestrictedApi"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Monet palette generation (HCT, DynamicScheme, MaterialDynamicColors).
    implementation("com.google.android.material:material:1.12.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    testImplementation("junit:junit:4.13.2")
}
