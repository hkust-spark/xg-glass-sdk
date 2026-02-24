plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.xgglassapp.logic"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    // Only depends on the entry contracts + core API surface (keeps this module device-agnostic).
    implementation("com.universalglasses:app-contract:0.0.1")
    // OpenAI Kotlin client (Maven Central)
    implementation(platform("com.aallam.openai:openai-client-bom:4.0.1"))
    implementation("com.aallam.openai:openai-client")
    // Http engine for Ktor (required at runtime on JVM/Android)
    implementation("io.ktor:ktor-client-okhttp")
}
