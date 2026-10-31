import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Loads signing credentials provisioned from encrypted GitHub repository secrets (keystore.secrets.properties)
// without exposing any secret values, aliases, or passwords in Gradle build logs.
fun Project.loadGitHubSecretsProperties(): Properties {
    val props = Properties()
    val secretFiles = listOfNotNull(
        project.file("keystore.secrets.properties"),
        rootProject.file("keystore.secrets.properties"),
        rootProject.projectDir.parentFile?.resolve("android/keystore.secrets.properties"),
        project.file("keystore.properties"),
        rootProject.file("keystore.properties")
    )
    for (secretFile in secretFiles) {
        if (secretFile.exists() && secretFile.isFile) {
            secretFile.inputStream().use { props.load(it) }
            break
        }
    }
    return props
}

// Resolves a signing secret from GitHub repository secrets (keystore.secrets.properties or Gradle -P/ORG_GRADLE_PROJECT_ properties),
// falling back to environment providers or default fallback values without logging secret contents.
fun Project.resolveSigningSecret(vararg keys: String, defaultValue: String): String {
    val secretProps = loadGitHubSecretsProperties()
    for (key in keys) {
        val fromSecretsFile = secretProps.getProperty(key)?.trim()
        if (!fromSecretsFile.isNullOrEmpty()) return fromSecretsFile

        val fromGradleProp = providers.gradleProperty(key).orNull?.trim()
            ?: findProperty(key)?.toString()?.trim()
        if (!fromGradleProp.isNullOrEmpty()) return fromGradleProp

        val fromEnvProvider = providers.environmentVariable(key).orNull?.trim()
        if (!fromEnvProvider.isNullOrEmpty()) return fromEnvProvider
    }
    return defaultValue
}

// Backwards-compatible delegate for resolving signing credentials
fun Project.resolveSigningCredential(vararg keys: String, defaultValue: String): String =
    resolveSigningSecret(*keys, defaultValue = defaultValue)

// Locates or materializes the release keystore file from encrypted GitHub repository secrets
// with restricted owner-only (0600) permissions and zero key/password output in build logs.
fun Project.resolveKeystoreFile(defaultFallbackName: String = "debug.keystore"): File {
    val rawPath = resolveSigningSecret(
        "HEXNEXT_KEYSTORE_PATH",
        "KEYSTORE_PATH",
        "ANDROID_KEYSTORE_PATH",
        "RELEASE_STORE_FILE",
        defaultValue = ""
    )

    val base64Keystore = resolveSigningSecret(
        "HEXNEXT_KEYSTORE_BASE64",
        "KEYSTORE_BASE64",
        "ANDROID_KEYSTORE_BASE64",
        defaultValue = ""
    )

    // 1. If a Base64-encoded keystore secret is provided from GitHub Secrets, materialize it with 0600 permissions
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
            targetFile.setReadable(false, false)
            targetFile.setReadable(true, true)
            targetFile.setWritable(false, false)
            targetFile.setWritable(true, true)
        }
        return targetFile
    }

    // 2. If a keystore path secret is configured, search candidate roots silently
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

    // 3. Fallback to committed project/repo keystores so local & fork PR builds sign automatically
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
        val resolvedStorePassword = project.resolveSigningSecret(
            "HEXNEXT_STORE_PASSWORD",
            "KEYSTORE_PASSWORD",
            "STORE_PASSWORD",
            "ANDROID_STORE_PASSWORD",
            defaultValue = "android"
        )
        val resolvedKeyAlias = project.resolveSigningSecret(
            "HEXNEXT_KEY_ALIAS",
            "KEY_ALIAS",
            "ANDROID_KEY_ALIAS",
            defaultValue = "androiddebugkey"
        )
        val resolvedKeyPassword = project.resolveSigningSecret(
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        checkReleaseBuilds = false
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

    testImplementation("junit:junit:4.13.2")
}
