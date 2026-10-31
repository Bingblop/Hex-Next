import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Helper to resolve signing credentials from environment variables or Gradle properties
fun Project.resolveSigningCredential(vararg keys: String, defaultValue: String): String {
    for (key in keys) {
        val envVal = System.getenv(key)?.trim()
        if (!envVal.isNullOrEmpty()) return envVal
        val propVal = findProperty(key)?.toString()?.trim()
        if (!propVal.isNullOrEmpty()) return propVal
    }
    return defaultValue
}

// Helper to locate or materialize the keystore file from CI environment variables
fun Project.resolveKeystoreFile(defaultFallbackName: String = "debug.keystore"): File {
    val rawPath = resolveSigningCredential(
        "HEXNEXT_KEYSTORE_PATH",
        "KEYSTORE_PATH",
        "ANDROID_KEYSTORE_PATH",
        "RELEASE_STORE_FILE",
        defaultValue = ""
    )

    val base64Keystore = resolveSigningCredential(
        "HEXNEXT_KEYSTORE_BASE64",
        "KEYSTORE_BASE64",
        "ANDROID_KEYSTORE_BASE64",
        defaultValue = ""
    )

    // 1. If a Base64-encoded keystore secret is provided in CI, materialize it to disk
    if (base64Keystore.isNotEmpty()) {
        val targetFile = if (rawPath.isNotEmpty()) {
            val candidate = File(rawPath)
            if (candidate.isAbsolute) candidate else project.file(rawPath)
        } else {
            layout.buildDirectory.file("keystore/ci-release.keystore").get().asFile
        }
        if (!targetFile.exists() || targetFile.length() == 0L) {
            targetFile.parentFile?.mkdirs()
            val cleanB64 = base64Keystore.replace("\\s".toRegex(), "")
            targetFile.writeBytes(Base64.getDecoder().decode(cleanB64))
        }
        return targetFile
    }

    // 2. If a file path is provided via environment variables or Gradle properties, search candidate roots
    if (rawPath.isNotEmpty()) {
        val directFile = File(rawPath)
        val candidates = listOfNotNull(
            directFile.takeIf { it.isAbsolute },
            project.file(rawPath),                                  // relative to android/app/
            rootProject.file(rawPath),                              // relative to android/
            rootProject.projectDir.parentFile?.resolve(rawPath),    // relative to repo root
            directFile
        )
        val existing = candidates.firstOrNull { it.exists() && it.isFile }
        if (existing != null) {
            return existing
        }
        return if (directFile.isAbsolute) directFile else project.file(rawPath)
    }

    // 3. Fallback to committed project/repo keystores so local & PR CI builds sign automatically
    val fallbackCandidates = listOfNotNull(
        project.file(defaultFallbackName),
        project.file("release.keystore"),
        rootProject.file(defaultFallbackName),
        rootProject.projectDir.parentFile?.resolve("bin/release.keystore"),
        rootProject.projectDir.parentFile?.resolve("android/app/$defaultFallbackName")
    )
    return fallbackCandidates.firstOrNull { it.exists() && it.isFile } ?: project.file(defaultFallbackName)
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
        val resolvedKeystore = project.resolveKeystoreFile("debug.keystore")
        val resolvedStorePassword = project.resolveSigningCredential(
            "HEXNEXT_STORE_PASSWORD",
            "KEYSTORE_PASSWORD",
            "STORE_PASSWORD",
            "ANDROID_STORE_PASSWORD",
            defaultValue = "android"
        )
        val resolvedKeyAlias = project.resolveSigningCredential(
            "HEXNEXT_KEY_ALIAS",
            "KEY_ALIAS",
            "ANDROID_KEY_ALIAS",
            defaultValue = "androiddebugkey"
        )
        val resolvedKeyPassword = project.resolveSigningCredential(
            "HEXNEXT_KEY_PASSWORD",
            "KEY_PASSWORD",
            "ANDROID_KEY_PASSWORD",
            defaultValue = resolvedStorePassword
        )

        getByName("debug") {
            storeFile = resolvedKeystore
            storePassword = resolvedStorePassword
            keyAlias = resolvedKeyAlias
            keyPassword = resolvedKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        create("release") {
            storeFile = resolvedKeystore
            storePassword = resolvedStorePassword
            keyAlias = resolvedKeyAlias
            keyPassword = resolvedKeyPassword
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
