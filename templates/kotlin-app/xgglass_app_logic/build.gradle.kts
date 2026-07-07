plugins {
    id("com.android.library")
}

android {
    namespace = "com.example.xgglassapp.logic"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // Only depends on the entry contracts + core API surface (keeps this module device-agnostic).
    implementation("io.github.hkust-spark:xgglass-app-contract:0.2.0")
    // OpenAI Kotlin client (Maven Central)
    implementation(platform("com.aallam.openai:openai-client-bom:4.0.1"))
    implementation("com.aallam.openai:openai-client")
    // Http engine for Ktor (required at runtime on JVM/Android)
    implementation("io.ktor:ktor-client-okhttp")
}
