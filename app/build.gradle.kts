plugins {
    id("com.universalglasses.android.application")
}

android {
    namespace = "com.universalglasses.hoststub"

    defaultConfig {
        applicationId = "com.universalglasses.hoststub"
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
    }
}

dependencies {
    // Intentionally empty: this is an internal host stub module required by Flutter's Gradle plugin.
}
