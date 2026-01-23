plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.universalglasses.rayneo.app")
}

android {
    namespace = "com.example.xgglassapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.xgglassapp"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"

        buildConfigField("boolean", "XG_SIMULATOR", "false")
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

ugRayneo {
    // The generated RayNeo glass-host APK will load this class by reflection.
    appEntryClass.set("__XG_ENTRY_CLASS__")
    logicProjectPath.set(":ug_app_logic")
    // RayNeo/Mercury vendor AARs (used for temple gestures / navigation on glasses)
    // Replaced by xg-glass init: __XG_SDK_PATH__/third_party/rayneo/aar
    mercuryAarDir.set(File(rootDir, "__XG_SDK_PATH__/third_party/rayneo/aar").absolutePath)
    // hostProjectPath defaults to :ug_rayneo_glass_host
    // assetApkName defaults to rayneo_glass_app.apk
    // variant defaults to debug
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    // universal_glasses (single entry-point dependency)
    implementation("com.universalglasses:universal:0.0.1")

    // Shared developer logic module (implements UniversalAppEntry)
    implementation(project(":ug_app_logic"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
}


