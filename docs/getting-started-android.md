# Your First App (Android, Maven)

This quickstart builds a minimal Android app against the published Maven artifacts for xg.glass `0.1.0`. It uses the Android simulator client first, so you can test without physical glasses.

Use Android Gradle Plugin `8.13.1`, Kotlin `2.1.0`, `compileSdk = 35`, and `minSdk = 28`.

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
            }
            filter {
                includeGroupByRegex("com\\.rokid(\\..+)?")
            }
        }
    }
}

rootProject.name = "XgQuickstartAndroid"
include(":app")
```

`gradle.properties`:

```properties
android.useAndroidX=true
```

Top-level `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
```

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.xgquickstart"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.xgquickstart"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("io.github.hkust-spark:xgglass-universal:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
```

The simulator module declares camera and microphone permissions. This capture-only Activity requests `CAMERA` at runtime; request `RECORD_AUDIO` before using microphone APIs.

`app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <application
        android:theme="@style/Theme.AppCompat.Light.NoActionBar"
        android:label="Xg Quickstart">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/java/com/example/xgquickstart/MainActivity.kt`:

```kotlin
package com.example.xgquickstart

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xgglass.core.CaptureOptions
import com.xgglass.device.sim.SimulatorGlassesClient
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val client by lazy {
        SimulatorGlassesClient(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
            return
        }

        lifecycleScope.launch {
            client.connect().getOrElse { error ->
                Log.e(TAG, "connect failed", error)
                return@launch
            }
            val bytes = client.capturePhoto(CaptureOptions())
                .map { it.jpegBytes.size }
                .getOrElse { error ->
                    Log.e(TAG, "capture failed", error)
                    return@launch
                }
            val message = "Captured $bytes bytes"
            Log.i(TAG, message)
            client.display(message).getOrElse { Log.e(TAG, "display failed", it) }
        }
    }

    override fun onDestroy() {
        lifecycleScope.launch { client.disconnect() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XgQuickstart"
    }
}
```

## Running on real glasses

For Rokid, swap `SimulatorGlassesClient(this)` for `RokidGlassesClient(this)`. The actual constructor shape is `RokidGlassesClient(activity: AppCompatActivity, options: RokidOptions = RokidOptions())`, and it implements the same `GlassesClient` API used above. For other Maven-supported devices, check the [README device matrix](../README.md#device-matrix-by-channel). For Brilliant Labs Frame, prefer the CLI/source flow because the working Frame integration needs the embedded Flutter module from an SDK checkout.
