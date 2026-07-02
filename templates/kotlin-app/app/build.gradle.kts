plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.xgglass.rayneo.app")
}

import java.io.File
import java.util.Properties

// Device-specific local config (do NOT commit secrets).
// These BuildConfig fields are OPTIONAL fallbacks: users can now enter Rokid
// credentials at runtime via the in-app UI (recommended for end users).
// Developers who prefer build-time config can still set these in local.properties:
//   rokid.clientSecret=...
//   rokid.snRawName=sn_your_file_name_without_extension
val _localProps = Properties()
val _localPropsFile = rootProject.file("local.properties")
if (_localPropsFile.exists()) {
    _localPropsFile.inputStream().use { _localProps.load(it) }
}
val _sdkLocalProps = Properties()
val _sdkLocalPropsFile = File(rootDir, "__XG_SDK_PATH__/local.properties")
if (_sdkLocalPropsFile.exists()) {
    _sdkLocalPropsFile.inputStream().use { _sdkLocalProps.load(it) }
}
fun _propOrEnv(key: String, envKey: String): String =
    (
        _localProps.getProperty(key)
            ?: _sdkLocalProps.getProperty(key)
            ?: System.getenv(envKey)
            ?: ""
    ).trim()
fun _escapeForBuildConfig(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

val rokidClientSecret = _propOrEnv("rokid.clientSecret", "ROKID_CLIENT_SECRET")
val rokidSnRawName = _propOrEnv("rokid.snRawName", "ROKID_SN_RAW_NAME")
val metaGithubToken = (
    providers.gradleProperty("github_token").orNull
        ?: providers.environmentVariable("GITHUB_TOKEN").orNull
        ?: ""
).trim()
val hasMetaDatAccess = metaGithubToken.isNotEmpty()
val appMinSdk = if (hasMetaDatAccess) 29 else 28

android {
    namespace = "com.example.xgglassapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.xgglassapp"
        minSdk = appMinSdk
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"

        buildConfigField("boolean", "XG_SIMULATOR", "false")
        buildConfigField("String", "XG_SIM_VIDEO_PATH", "\"\"")
        buildConfigField("String", "ROKID_CLIENT_SECRET", "\"${_escapeForBuildConfig(rokidClientSecret)}\"")
        buildConfigField("String", "ROKID_SN_RAW_NAME", "\"${_escapeForBuildConfig(rokidSnRawName)}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Keep APK size reasonable when Flutter is present (Frame integration).
    // Generate per‑ABI APKs instead of one universal APK.
    splits {
        abi {
            isEnable = true
            reset()
            // Most real devices are arm64; keep armeabi-v7a for older 32-bit phones.
            // If you need to run on the Android emulator, add "x86_64" here.
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug { }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

xgRayneo {
    // The generated RayNeo glass-host APK will load this class by reflection.
    appEntryClass.set("__XG_ENTRY_CLASS__")
    logicProjectPath.set(":xgglass_app_logic")
    // RayNeo/Mercury vendor AARs (used for temple gestures / navigation on glasses)
    // Replaced by xg-glass init: __XG_SDK_PATH__/third_party/rayneo/aar
    mercuryAarDir.set(File(rootDir, "__XG_SDK_PATH__/third_party/rayneo/aar").absolutePath)
    // hostProjectPath defaults to :xgglass_rayneo_glass_host
    // assetApkName defaults to rayneo_glass_app.apk
    // variant defaults to debug
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    // xg.glass SDK (single entry-point dependency)
    implementation("io.github.hkust-spark:universal:0.1.0")

    // Shared developer logic module (implements UniversalAppEntry)
    implementation(project(":xgglass_app_logic"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
}
