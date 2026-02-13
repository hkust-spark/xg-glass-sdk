package com.universalglasses.buildlogic.android

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

internal const val UG_COMPILE_SDK = 34
internal const val UG_MIN_SDK = 29

internal fun CommonExtension<*, *, *, *, *, *>.applyUniversalGlassesAndroidDefaults() {
    compileSdk = UG_COMPILE_SDK

    defaultConfig { minSdk = UG_MIN_SDK }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
