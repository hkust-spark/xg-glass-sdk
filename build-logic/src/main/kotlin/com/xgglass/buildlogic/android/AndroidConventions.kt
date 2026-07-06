package com.xgglass.buildlogic.android

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion

internal const val XG_COMPILE_SDK = 36
internal const val XG_MIN_SDK = 28

internal fun ApplicationExtension.applyXgGlassAndroidDefaults() {
    compileSdk = XG_COMPILE_SDK

    defaultConfig {
        minSdk = XG_MIN_SDK
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

internal fun LibraryExtension.applyXgGlassAndroidDefaults() {
    compileSdk = XG_COMPILE_SDK

    defaultConfig {
        minSdk = XG_MIN_SDK
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
