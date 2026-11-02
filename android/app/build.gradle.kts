import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}
fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
        ?: signingProperties.getProperty(name)?.takeIf { it.isNotEmpty() }

val releaseStorePath = signingValue("HEXNEXT_KEYSTORE_PATH")
val releaseStorePassword = signingValue("HEXNEXT_STORE_PASSWORD")
val releaseKeyAlias = signingValue("HEXNEXT_KEY_ALIAS")
val releaseKeyPassword = signingValue("HEXNEXT_KEY_PASSWORD")
val signingValues = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = signingValues.all { it != null }
require(signingValues.all { it == null } || releaseSigningConfigured) {
    "Incomplete release signing: configure all four HEXNEXT_* signing values or none."
}
val releaseStoreFile = releaseStorePath?.let { rootProject.file(it) }
require(releaseStoreFile == null || releaseStoreFile.isFile) {
    "The configured release keystore does not exist."
}
require(providers.gradleProperty("requireReleaseSigning").orNull != "true" || releaseSigningConfigured) {
    "Release signing is required. Configure HEXNEXT_KEYSTORE_PATH, HEXNEXT_STORE_PASSWORD, HEXNEXT_KEY_ALIAS and HEXNEXT_KEY_PASSWORD."
}

android {
    namespace = "com.hexnext.themer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hexnext.themer"
        // Public FabricatedOverlay / OverlayManagerTransaction APIs for One UI 7/8/9.0 (Android 14–16 / SDK 36).
        minSdk = 35
        targetSdk = 36
        versionCode = 220
        versionName = "2.2.0"

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
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds stay unshrunk and unobfuscated so stack traces and the Shizuku
            // service are readable on a test phone.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // R8 shrinks and obfuscates; proguard-rules.pro keeps the Binder/AIDL surface, the
            // engine's serialised models and the Material colour utilities.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        jniLibs {
            // Uncompressed page-aligned 64-bit & 32-bit native libraries for Android 16 / One UI 9.0 (16KB page ready)
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin"
            )
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
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    lint {
        // Material color utilities are marked @RestrictTo but are the reference Monet implementation.
        disable += "RestrictedApi"
        checkReleaseBuilds = true
    }
}

// Configuration-cache-safe Gradle check task to verify required Android SDK build tools (aapt2, apksigner, zipalign) before building
val configuredAndroidSdkDir: File = android.sdkDirectory
val envAndroidHomeProvider = providers.environmentVariable("ANDROID_HOME")
val envAndroidSdkRootProvider = providers.environmentVariable("ANDROID_SDK_ROOT")

val verifyAndroidSdkTools by tasks.registering {
    group = "verification"
    description = "Checks that required Android SDK build tools (aapt2, apksigner, zipalign) are present in the environment before building."

    val sdkDirCandidate = configuredAndroidSdkDir
    val envHome = envAndroidHomeProvider.orNull
    val envSdkRoot = envAndroidSdkRootProvider.orNull

    doLast {
        val sdkRoot = sequenceOf(
            sdkDirCandidate.takeIf { it.exists() },
            envHome?.takeIf { it.isNotBlank() }?.let(::File),
            envSdkRoot?.takeIf { it.isNotBlank() }?.let(::File)
        ).filterNotNull().firstOrNull { it.exists() && it.isDirectory }
            ?: throw GradleException(
                "Android SDK directory not found. Ensure ANDROID_HOME or ANDROID_SDK_ROOT is set before building."
            )

        val buildToolsRoot = File(sdkRoot, "build-tools")
        val buildToolsDirs = buildToolsRoot
            .takeIf { it.exists() && it.isDirectory }
            ?.listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.name }
            .orEmpty()

        if (buildToolsDirs.isEmpty()) {
            throw GradleException(
                "No Android SDK build-tools versions found under ${buildToolsRoot.absolutePath}."
            )
        }

        val requiredTools = listOf("aapt2", "apksigner", "zipalign")
        val missingTools = mutableListOf<String>()

        for (tool in requiredTools) {
            val resolvedBinary = buildToolsDirs.firstNotNullOfOrNull { dir ->
                listOf(tool, "$tool.exe", "$tool.bat")
                    .map { File(dir, it) }
                    .firstOrNull { it.exists() && it.isFile }
            }
            if (resolvedBinary == null) {
                missingTools.add(tool)
            } else {
                logger.lifecycle("[✓] Gradle verifyAndroidSdkTools: Verified $tool -> ${resolvedBinary.absolutePath}")
            }
        }

        if (missingTools.isNotEmpty()) {
            throw GradleException(
                "Missing required Android SDK build tools (${missingTools.joinToString(", ")}) in ${buildToolsRoot.absolutePath}."
            )
        }
    }
}

tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
    dependsOn(verifyAndroidSdkTools)
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

    // Pure-JVM engine (FRRO reader, `cmd overlay dump` parser, One UI rules); substituted by
    // includeBuild("engine") in settings.gradle.kts.
    implementation("com.hexnext:engine")

    testImplementation("junit:junit:4.13.2")
}
