package com.xgglass.buildlogic.rayneo

internal data class TemplateFile(
    val relativePath: String,
    val content: String,
)

internal object RayneoHostTemplate {
    // Bump this if you change any template content so the generator knows when to refresh.
    const val TEMPLATE_VERSION = 25

    fun files(): List<TemplateFile> = listOf(
        TemplateFile(
            "xgglass_rayneo_glass_host/build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "com.xgglass.rayneo.host"
                compileSdk = 35

                defaultConfig {
                    applicationId = "com.xgglass.rayneo.host"
                    // RayNeo devices are typically Android 12+; keep conservative but aligned with SDK.
                    // RayNeo IPC SDK requires minSdk >= 29.
                    minSdk = 29
                    targetSdk = 34
                    versionCode = 1
                    versionName = "0.0.1"
                }

                buildTypes {
                    debug { }
                    release { isMinifyEnabled = false }
                }

                buildFeatures {
                    viewBinding = true
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_1_8
                    targetCompatibility = JavaVersion.VERSION_1_8
                }
            }

            kotlin {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                }
            }

            dependencies {
                // RayNeo official SDK AARs (copied by the Gradle plugin into libs/)
                implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("com.google.android.material:material:1.12.0")

                // RayNeo glasses-side runtime client (Camera2 capture + display sink)
                implementation("io.github.hkust-spark:device-rayneo-runtime:0.1.0")

                // Entry contracts
                implementation("io.github.hkust-spark:app-contract:0.1.0")

                // Developer's shared logic module (filled by plugin)
                implementation(project("__XG_LOGIC_PROJECT__"))
            }
            """.trimIndent(),
        ),
        TemplateFile(
            "xgglass_rayneo_glass_host/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.CAMERA" />
                <uses-permission android:name="android.permission.RECORD_AUDIO" />
                <!-- Needed on some devices/ROMs for adjusting stream volume programmatically -->
                <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

                <!-- Holds a transient settings cache; not backed up. -->
                <application
                    android:name=".XgRayneoHostApplication"
                    android:allowBackup="false"
                    android:label="XG RayNeo Host"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.MaterialComponents.DayNight.NoActionBar">

                    <!-- RayNeo launcher/app-center marker -->
                    <meta-data
                        android:name="com.rayneo.mercury.app"
                        android:value="true" />

                    <!-- Developer entry class (set via manifest placeholder) -->
                    <meta-data
                        android:name="com.xgglass.app_entry_class"
                        android:value="__XG_APP_ENTRY_CLASS__" />

                    <activity
                        android:name=".XgRayneoHostActivity"
                        android:exported="true"
                        android:screenOrientation="landscape">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>

                </application>
            </manifest>
            """.trimIndent(),
        ),
        TemplateFile(
            "xgglass_rayneo_glass_host/src/main/res/layout/activity_xg_rayneo_host.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!--
            IMPORTANT:
            We intentionally avoid ScrollView/RecyclerView here.
            On some RayNeo/Mercury firmwares, touchpad (temple) gestures are delivered as MotionEvent
            and can be consumed by scrollable containers, preventing BaseEventActivity from producing TempleAction.
            -->
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:padding="12dp">

                <!-- Keep content centered and not "too wide" in landscape on glasses displays. -->
                <LinearLayout
                    android:id="@+id/llRoot"
                    android:layout_width="360dp"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center"
                    android:orientation="vertical"
                    android:gravity="center_horizontal"
                    android:focusable="true"
                    android:focusableInTouchMode="true">

                    <TextView
                        android:id="@+id/tvTitle"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="XG RayNeo Host"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/tvDisplay"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:text="(display output)"
                        android:textSize="14sp" />

                    <TextView
                        android:id="@+id/tvLog"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:text="Logs:"
                        android:textSize="12sp" />

                    <LinearLayout
                        android:id="@+id/llButtons"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="10dp"
                        android:orientation="vertical" />

                </LinearLayout>

            </FrameLayout>
            """.trimIndent(),
        ),
        TemplateFile(
            "xgglass_rayneo_glass_host/src/main/java/com/xgglass/rayneo/host/XgRayneoHostActivity.kt",
            """
            package com.xgglass.rayneo.host

            import android.Manifest
            import android.content.Context
            import android.media.AudioManager
            import android.os.Bundle
            import android.widget.Button
            import androidx.activity.result.contract.ActivityResultContracts
            import androidx.core.content.ContextCompat
            import androidx.lifecycle.Lifecycle
            import androidx.lifecycle.lifecycleScope
            import androidx.lifecycle.repeatOnLifecycle
            import com.ffalcon.mercury.android.sdk.touch.TempleAction
            import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
            import com.xgglass.appcontract.HostEnvironment
            import com.xgglass.appcontract.HostKind
            import com.xgglass.appcontract.UniversalAppContext
            import com.xgglass.appcontract.UniversalAppEntry
            import com.xgglass.appcontract.commandsWithDefaults
            import com.xgglass.core.GlassesModel
            import com.xgglass.device.rayneo.runtime.RayNeoDisplaySink
            import com.xgglass.device.rayneo.runtime.RayNeoRuntimeGlassesClient
            import com.xgglass.rayneo.host.databinding.ActivityXgRayneoHostBinding
            import kotlinx.coroutines.CancellationException
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.SupervisorJob
            import kotlinx.coroutines.cancel
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.withContext
            import org.json.JSONObject
            import java.io.File

            class XgRayneoHostActivity : BaseMirrorActivity<ActivityXgRayneoHostBinding>() {
                private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                private val client by lazy {
                    RayNeoRuntimeGlassesClient(
                        context = applicationContext,
                        displaySink = RayNeoDisplaySink { _, text, _ ->
                            // Render on both lenses (avoid Android Toast, which can appear split).
                            withContext(Dispatchers.Main) {
                                mBindingPair.updateView {
                                    tvDisplay.text = text
                                }
                            }
                        }
                    )
                }
                private var selectedIndex: Int = 0
                private var commands: List<com.xgglass.appcontract.UniversalCommand> = emptyList()
                private var isRunningCommand: Boolean = false

                /**
                 * User settings pushed from the phone via ADB.
                 * Read once in [onCreate] from the well-known file [SETTINGS_FILE_PATH].
                 */
                private var userSettings: Map<String, String> = emptyMap()

                private val requestCameraPermission = registerForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (!granted) {
                        appendLog("Camera permission denied")
                    }
                }

                private val requestMicPermission = registerForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (!granted) {
                        appendLog("Microphone permission denied")
                    }
                }

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    // Best-effort: avoid "silent by default" media stream.
                    ensureMusicVolumeNotZero()
                    // Request permissions early; commands will no-op until permissions are granted.
                    ensureCameraPermission()
                    ensureMicPermission()
                    mBindingPair.updateView { tvTitle.text = "XG RayNeo Host" }

                    val entry = loadEntryOrNull()
                    if (entry == null) {
                        appendLog("Missing UniversalAppEntry (check app_entry_class)")
                        return
                    }

                    // Load settings pushed from phone via ADB (if any).
                    userSettings = loadSettingsFromFile()
                    if (userSettings.isNotEmpty()) {
                        appendLog("Loaded ${"$"}{userSettings.size} setting(s) from file")
                    }

                    val env = HostEnvironment(hostKind = HostKind.GLASSES, model = GlassesModel.RAYNEO)
                    commands = entry.commandsWithDefaults(env)

                    // Connect early so command handlers can assume connected.
                    scope.launch { client.connect() }

                    // Build the same UI on BOTH lenses.
                    mBindingPair.updateView {
                        tvDisplay.text = ""
                        llButtons.removeAllViews()
                        if (commands.isEmpty()) {
                            llButtons.addView(Button(this@XgRayneoHostActivity).apply {
                                text = "No commands for GLASSES/RAYNEO"
                                isEnabled = false
                            })
                        } else {
                            commands.forEach { cmd ->
                                llButtons.addView(Button(this@XgRayneoHostActivity).apply {
                                    text = cmd.title
                                    setOnClickListener { runCommand(env, cmd) }
                                })
                            }
                        }
                    }
                    updateSelectionUi()

                    // Temple gestures (Mercury SDK): slide to select, click to run, double-click to exit.
                    lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            templeActionViewModel.state.collect { action ->
                                when (action) {
                                    // Different firmwares may map temple swipes to different directions.
                                    is TempleAction.SlideForward,
                                    is TempleAction.SlideUpwards,
                                    -> {
                                        if (commands.isNotEmpty()) {
                                            selectedIndex = (selectedIndex + 1).coerceAtMost(commands.lastIndex)
                                            updateSelectionUi()
                                        }
                                    }

                                    is TempleAction.SlideBackward,
                                    is TempleAction.SlideDownwards,
                                    -> {
                                        if (commands.isNotEmpty()) {
                                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                            updateSelectionUi()
                                        }
                                    }

                                    is TempleAction.Click -> {
                                        commands.getOrNull(selectedIndex)?.let { runCommand(env, it) }
                                    }

                                    is TempleAction.DoubleClick -> {
                                        finish()
                                    }

                                    else -> Unit
                                }
                            }
                        }
                    }
                }

                override fun onDestroy() {
                    scope.cancel()
                    super.onDestroy()
                }

                private fun runCommand(env: HostEnvironment, cmd: com.xgglass.appcontract.UniversalCommand) {
                    if (isRunningCommand) return
                    isRunningCommand = true
                    scope.launch {
                        try {
                            if (client.capabilities.canCapturePhoto && !ensureCameraPermission()) {
                                appendLog("Grant camera permission first")
                                return@launch
                            }
                            if (client.capabilities.canRecordAudio && !ensureMicPermission()) {
                                appendLog("Grant microphone permission first")
                                return@launch
                            }
                            val ctx = UniversalAppContext(
                                environment = env,
                                client = client,
                                scope = this@XgRayneoHostActivity.scope,
                                log = { msg -> appendLog(msg) },
                                settings = userSettings,
                            )
                            val r = cmd.run(ctx)
                            if (r.isFailure) {
                                appendLog("Failed: ${"$"}{r.exceptionOrNull()?.message ?: "unknown"}")
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            appendLog("Command error: ${"$"}{e.javaClass.simpleName}: ${"$"}{e.message}")
                        } finally {
                            isRunningCommand = false
                        }
                    }
                }

                private fun updateSelectionUi() {
                    mBindingPair.updateView {
                        val n = llButtons.childCount
                        for (i in 0 until n) {
                            val v = llButtons.getChildAt(i)
                            if (v is Button) {
                                val selected = i == selectedIndex
                                v.alpha = if (selected) 1.0f else 0.6f
                                if (selected) v.requestFocus()
                            }
                        }
                    }
                }

                private fun appendLog(msg: String) {
                    mBindingPair.updateView {
                        tvLog.text = (tvLog.text?.toString() ?: "") + "\n" + msg
                    }
                }

                private fun loadEntryOrNull(): UniversalAppEntry? {
                    val appInfo = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                    val cls = appInfo.metaData?.getString("com.xgglass.app_entry_class")?.trim().orEmpty()
                    if (cls.isBlank()) return null
                    return try {
                        val k = Class.forName(cls)
                        k.getDeclaredConstructor().newInstance() as UniversalAppEntry
                    } catch (_: Throwable) {
                        null
                    }
                }

                private fun ensureCameraPermission(): Boolean {
                    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                        return false
                    }
                    return true
                }

                private fun ensureMicPermission(): Boolean {
                    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                        return false
                    }
                    return true
                }

                private fun ensureMusicVolumeNotZero() {
                    try {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val stream = AudioManager.STREAM_MUSIC
                        val cur = am.getStreamVolume(stream)
                        val max = am.getStreamMaxVolume(stream)
                        if (cur <= 0 && max > 0) {
                            // Set to a reasonable audible value (not max) to avoid blasting volume.
                            val target = (max / 2).coerceAtLeast(1)
                            am.setStreamVolume(stream, target, 0)
                        }
                    } catch (_: Exception) {
                        // ignore
                    }
                }

                /**
                 * Read user settings from the public ADB handoff file when present, then
                 * ingest them into app-private storage and delete the public copy.
                 *
                 * Residual risk: a malicious glasses-side app could still read the public
                 * file during the brief window between the phone push and this app's next
                 * [onCreate]. This is inherent to delivering settings through `/data/local/tmp`
                 * without root because it is the only viable cross-UID ADB handoff path.
                 */
                private fun loadSettingsFromFile(): Map<String, String> {
                    return try {
                        val publicFile = File(PUBLIC_SETTINGS_FILE_PATH)
                        val privateFile = File(filesDir, SETTINGS_FILE_NAME)
                        val text = when {
                            publicFile.exists() -> {
                                val pushed = publicFile.readText(Charsets.UTF_8)
                                writePrivateSettings(privateFile, pushed)
                                publicFile.delete()
                                pushed
                            }
                            privateFile.exists() -> privateFile.readText(Charsets.UTF_8)
                            else -> return emptyMap()
                        }
                        val json = JSONObject(text)
                        json.keys().asSequence().associateWith { json.getString(it) }
                    } catch (e: Exception) {
                        appendLog("Failed to load settings file: ${"$"}{e.message}")
                        emptyMap()
                    }
                }

                private fun writePrivateSettings(privateFile: File, text: String) {
                    val tempFile = File(filesDir, "${"$"}SETTINGS_FILE_NAME.tmp")
                    tempFile.writeText(text, Charsets.UTF_8)
                    if (!tempFile.renameTo(privateFile)) {
                        tempFile.copyTo(privateFile, overwrite = true)
                        tempFile.delete()
                    }
                }

                companion object {
                    private const val SETTINGS_FILE_NAME = "xgglass_user_settings.json"
                    /** Transient path where the phone pushes user settings via ADB. */
                    private const val PUBLIC_SETTINGS_FILE_PATH = "/data/local/tmp/xgglass_user_settings.json"
                }
            }
            """.trimIndent(),
        ),
        TemplateFile(
            "xgglass_rayneo_glass_host/src/main/java/com/xgglass/rayneo/host/XgRayneoHostApplication.kt",
            """
            package com.xgglass.rayneo.host

            import android.app.Application
            import com.ffalcon.mercury.android.sdk.MercurySDK

            class XgRayneoHostApplication : Application() {
                override fun onCreate() {
                    super.onCreate()
                    MercurySDK.init(this)
                }
            }
            """.trimIndent(),
        ),
    )
}
