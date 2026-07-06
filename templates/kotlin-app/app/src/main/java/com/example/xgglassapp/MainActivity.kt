package com.example.xgglassapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xgglass.appcontract.HostEnvironment
import com.xgglass.appcontract.HostKind
import com.xgglass.appcontract.UniversalAppContext
import com.xgglass.appcontract.UniversalAppEntry
import com.xgglass.appcontract.UserSettingField
import com.xgglass.appcontract.UserSettingInputType
import com.xgglass.appcontract.commandsWithDefaults
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceManager
import com.xgglass.core.DeviceManagerState
import com.xgglass.core.ExternalActivityBridge
import com.xgglass.core.ExternalActivityResult
import com.xgglass.core.GlassesEvent
import com.xgglass.core.GlassesClient
import com.xgglass.core.GlassesModel
import com.xgglass.core.android.SecureStore
// xg:device:even:begin
import com.xgglass.device.even.EvenGlassesClient
// xg:device:even:end
// xg:device:frame:begin
import com.xgglass.device.frame.embedded.EmbeddedFrameGlassesClient
// xg:device:frame:end
// xg:device:inmo:begin
import com.xgglass.device.inmo.runtime.InmoRuntimeGlassesClient
// xg:device:inmo:end
// xg:device:rayneo:begin
import com.xgglass.device.rayneo.installer.RayNeoApkSource
import com.xgglass.device.rayneo.installer.RayNeoDeviceManager
import com.xgglass.device.rayneo.installer.RayNeoInstallerConfig
// xg:device:rayneo:end
// xg:device:rokid:begin
import com.xgglass.device.rokid.RokidGlassesClient
// xg:device:rokid:end
// xg:device:omi:begin
import com.xgglass.device.omi.OmiGlassesClient
// xg:device:omi:end
// xg:device:simulator:begin
import com.xgglass.device.sim.SimulatorGlassesClient
// xg:device:simulator:end
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var spDevice: Spinner
    private lateinit var btnConnect: Button
    private lateinit var ivPreview: ImageView
    private lateinit var tvDisplay: TextView
    private lateinit var tvDisplayTitle: TextView
    private lateinit var etRayNeoIp: EditText
    private lateinit var llCommands: LinearLayout
    private lateinit var tvSettingsTitle: TextView
    private lateinit var llSettings: LinearLayout
    private lateinit var btnApplySettings: Button

    // Rokid runtime credential UI
    private lateinit var llRokidConfig: LinearLayout
    private lateinit var btnPickSnLicense: Button
    private lateinit var tvSnLicenseFile: TextView
    private lateinit var etRokidSecret: EditText

    private val entry: UniversalAppEntry? by lazy { loadEntryOrNull() }

    /** Map of setting key → EditText widget, populated by [renderSettings]. */
    private val settingEdits = mutableMapOf<String, EditText>()

    /** Current applied settings (key → value). */
    private var appliedSettings: Map<String, String> = emptyMap()

    private var client: GlassesClient? = null
    private var deviceManager: DeviceManager? = null
    private var connectJob: Job? = null
    private var stateJob: Job? = null
    private var eventsJob: Job? = null
    private var pendingConnectModel: GlassesModel? = null
    private var externalActivityContinuation: kotlinx.coroutines.CancellableContinuation<ExternalActivityResult>? = null
    private val externalActivityMutex = Mutex()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            appendLog("Permissions denied; cannot connect.")
            tvStatus.text = "Status: permissions denied"
            pendingConnectModel = null
            return@registerForActivityResult
        }
        val model = pendingConnectModel
        pendingConnectModel = null
        if (model != null) connect(model)
    }

    private val externalActivityLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        externalActivityContinuation?.let { continuation ->
            if (continuation.isActive) {
                continuation.resume(
                    ExternalActivityResult(
                        resultCode = result.resultCode,
                        data = result.data,
                    )
                )
            }
        }
        externalActivityContinuation = null
    }

    /** Launcher for picking the Rokid SN license (.lc) file at runtime. */
    private val pickSnLicenseLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                appendLog("Rokid: selected file is empty.")
                return@registerForActivityResult
            }
            // Persist the .lc bytes into internal storage so we survive app restarts.
            val lcFile = File(filesDir, ROKID_LC_FILENAME)
            lcFile.writeBytes(bytes)
            // Extract a display name for the UI.
            val displayName = queryFileName(uri) ?: "sn_license.lc"
            secureRokid.putString(PREF_ROKID_LC_DISPLAY_NAME, displayName)
            tvSnLicenseFile.text = displayName
            appendLog("Rokid: SN license loaded (${bytes.size} bytes).")
        } catch (e: Exception) {
            appendLog("Rokid: failed to read file – ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        spDevice = findViewById(R.id.spDevice)
        btnConnect = findViewById(R.id.btnConnect)
        ivPreview = findViewById(R.id.ivPreview)
        tvDisplay = findViewById(R.id.tvDisplay)
        tvDisplayTitle = findViewById(R.id.tvDisplayTitle)
        etRayNeoIp = findViewById(R.id.etRayNeoIp)
        llCommands = findViewById(R.id.llCommands)
        tvSettingsTitle = findViewById(R.id.tvSettingsTitle)
        llSettings = findViewById(R.id.llSettings)
        btnApplySettings = findViewById(R.id.btnApplySettings)

        // Rokid runtime credential UI
        llRokidConfig = findViewById(R.id.llRokidConfig)
        btnPickSnLicense = findViewById(R.id.btnPickSnLicense)
        tvSnLicenseFile = findViewById(R.id.tvSnLicenseFile)
        etRokidSecret = findViewById(R.id.etRokidSecret)

        // Restore previously-saved Rokid credentials into the UI.
        restoreRokidCredentialUI()

        btnPickSnLicense.setOnClickListener {
            pickSnLicenseLauncher.launch(arrayOf("*/*"))
        }

        val deviceItems = if (BuildConfig.XG_SIMULATOR) {
            listOf("SIMULATOR")
        } else {
            // xg:device:all:begin
            listOf("ROKID", "META", "FRAME", "RAYNEO", "INMO", "OMI", "EVEN", "SIMULATOR")
            // xg:device:all:end
            // xg:device:partial:begin
            listOf(
                // xg:device:rokid:begin
                "ROKID",
                // xg:device:rokid:end
                // xg:device:meta:begin
                "META",
                // xg:device:meta:end
                // xg:device:frame:begin
                "FRAME",
                // xg:device:frame:end
                // xg:device:rayneo:begin
                "RAYNEO",
                // xg:device:rayneo:end
                // xg:device:inmo:begin
                "INMO",
                // xg:device:inmo:end
                // xg:device:omi:begin
                "OMI",
                // xg:device:omi:end
                // xg:device:even:begin
                "EVEN",
                // xg:device:even:end
                // xg:device:simulator:begin
                "SIMULATOR",
                // xg:device:simulator:end
            )
            // xg:device:partial:end
        }
        spDevice.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            deviceItems,
        )

        spDevice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                onDeviceSelectionChanged(spDevice.selectedItem?.toString() ?: "ROKID")
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnConnect.setOnClickListener {
            val selected = spDevice.selectedItem?.toString() ?: "ROKID"
            val model = when (selected) {
                "SIMULATOR" -> GlassesModel.SIMULATOR
                "META" -> GlassesModel.META
                "FRAME" -> GlassesModel.FRAME
                "RAYNEO" -> GlassesModel.RAYNEO
                "INMO" -> GlassesModel.INMO
                "OMI" -> GlassesModel.OMI
                "EVEN" -> GlassesModel.EVEN
                else -> GlassesModel.ROKID
            }
            // Save Rokid credentials entered in the UI before connecting.
            if (model == GlassesModel.ROKID) {
                saveRokidCredentials()
            }
            ensurePermissionsThenConnect(model)
        }

        renderSettings()

        btnApplySettings.setOnClickListener { applySettings() }

        // Settings title click toggles collapsible content
        tvSettingsTitle.setOnClickListener { toggleSettingsCollapse() }

        renderCommandsForCurrentSelection(connected = false)

        // Set initial display visibility based on default device selection
        onDeviceSelectionChanged(spDevice.selectedItem?.toString() ?: "ROKID")

        if (BuildConfig.XG_SIMULATOR) {
            // Simulator builds are meant to run on an emulator; auto-connect.
            ensurePermissionsThenConnect(GlassesModel.SIMULATOR)
        }
    }

    override fun onDestroy() {
        // Cancel SDK collectors/jobs so this Activity is not retained after destroy.
        scope.cancel()
        super.onDestroy()
    }

    // xg:device:inmo:begin
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val inmoClient = client as? InmoRuntimeGlassesClient
        if (inmoClient?.onHostKeyEvent(keyCode) == true) return true
        return super.onKeyDown(keyCode, event)
    }
    // xg:device:inmo:end

    private fun connect(model: GlassesModel) {
        // xg:device:rayneo:begin
        if (model == GlassesModel.RAYNEO) {
            installRayNeo()
            return
        }
        // xg:device:rayneo:end

        connectJob?.cancel()
        connectJob = scope.launch {
            btnConnect.isEnabled = false
            tvStatus.text = "Status: switching to ${model.name}..."

            try {
                // Disconnect previous client FIRST (sequentially) so we don't accidentally destroy the new one.
                val old = client
                client = null
                try {
                    old?.disconnect()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    appendLog("WARN: disconnect previous client failed: ${e.message}")
                }

                val newClient = when (model) {
                    // xg:device:simulator:begin
                    GlassesModel.SIMULATOR -> SimulatorGlassesClient(
                        activity = this@MainActivity,
                        displaySink = { text -> tvDisplay.text = text },
                        videoPath = BuildConfig.XG_SIM_VIDEO_PATH.takeIf { it.isNotEmpty() },
                    )
                    // xg:device:simulator:end
                    // xg:device:rokid:begin
                    GlassesModel.ROKID -> createRokidClient()
                    // xg:device:rokid:end
                    // xg:device:meta:begin
                    GlassesModel.META -> createMetaClient()
                    // xg:device:meta:end
                    // xg:device:frame:begin
                    GlassesModel.FRAME -> {
                        // SDK-owned Flutter engine + bridge
                        EmbeddedFrameGlassesClient(this@MainActivity)
                    }
                    // xg:device:frame:end
                    // xg:device:rayneo:begin
                    GlassesModel.RAYNEO -> error("RayNeo is handled via DeviceManager")
                    // xg:device:rayneo:end
                    // xg:device:inmo:begin
                    GlassesModel.INMO -> InmoRuntimeGlassesClient(this@MainActivity)
                    // xg:device:inmo:end
                    // xg:device:omi:begin
                    GlassesModel.OMI -> OmiGlassesClient(this@MainActivity)
                    // xg:device:omi:end
                    // xg:device:even:begin
                    GlassesModel.EVEN -> EvenGlassesClient(this@MainActivity)
                    // xg:device:even:end
                    GlassesModel.ANDROID_XR -> {
                        appendLog("Android XR: preview scaffold is not enabled in this app.")
                        tvStatus.text = "Status: Android XR preview scaffold not enabled"
                        return@launch
                    }
                    // xg:device:partial:begin
                    else -> {
                        appendLog("${model.name}: not included in this generated app.")
                        tvStatus.text = "Status: ${model.name} not included"
                        return@launch
                    }
                    // xg:device:partial:end
                }

                client = newClient
                val oldManager = deviceManager
                deviceManager = null
                try {
                    oldManager?.close()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    appendLog("WARN: close previous device manager failed: ${e.message}")
                }

                // Restart collectors for the new client.
                stateJob?.cancel()
                eventsJob?.cancel()

                stateJob = launch {
                    newClient.state.collectLatest { st ->
                        tvStatus.text = "Status: $st"
                        val connected = st is ConnectionState.Connected
                        renderCommandsForCurrentSelection(connected = connected)
                    }
                }

                eventsJob = launch {
                    newClient.events.collectLatest { ev ->
                        when (ev) {
                            is GlassesEvent.Log -> appendLog(ev.message)
                            is GlassesEvent.Warning -> appendLog("WARN: ${ev.message}")
                            is GlassesEvent.Tap -> appendLog("TAP: ${ev.count}")
                        }
                    }
                }

                val r = newClient.connect()
                appendLog("connect(${model.name}) => ${r.isSuccess} ${r.exceptionOrNull()?.message ?: ""}")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                client = null
                appendLog("connect(${model.name}) crashed: ${e.message ?: e.javaClass.simpleName}")
                tvStatus.text = "Status: connect failed"
                renderCommandsForCurrentSelection(connected = false)
            } finally {
                btnConnect.isEnabled = true
            }
        }
    }

    // xg:device:rayneo:begin
    private fun installRayNeo() {
        connectJob?.cancel()
        connectJob = scope.launch {
            btnConnect.isEnabled = false
            tvStatus.text = "Status: installing RayNeo glasses app..."

            try {
                val oldClient = client
                client = null
                try {
                    oldClient?.disconnect()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    appendLog("WARN: disconnect previous client failed: ${e.message}")
                }

                val manager = createRayNeoDeviceManagerFromInput() ?: return@launch
                val oldManager = deviceManager
                deviceManager = manager
                try {
                    if (oldManager !== manager) oldManager?.close()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    appendLog("WARN: close previous device manager failed: ${e.message}")
                }

                stateJob?.cancel()
                eventsJob?.cancel()

                stateJob = launch {
                    manager.state.collectLatest { st ->
                        tvStatus.text = when (st) {
                            DeviceManagerState.Idle -> "Status: RayNeo idle"
                            DeviceManagerState.Installing -> "Status: installing RayNeo glasses app..."
                            DeviceManagerState.Installed -> "Status: RayNeo glasses app installed"
                            is DeviceManagerState.Error -> "Status: RayNeo install error: ${st.error.message}"
                        }
                        renderCommandsForCurrentSelection(connected = false)
                    }
                }

                eventsJob = launch {
                    manager.events.collectLatest { ev ->
                        when (ev) {
                            is GlassesEvent.Log -> appendLog(ev.message)
                            is GlassesEvent.Warning -> appendLog("WARN: ${ev.message}")
                            is GlassesEvent.Tap -> appendLog("TAP: ${ev.count}")
                        }
                    }
                }

                val r = manager.install()
                appendLog("install(RAYNEO) => ${r.isSuccess} ${r.exceptionOrNull()?.message ?: ""}")

                if (r.isSuccess && appliedSettings.isNotEmpty()) {
                    val pushR = manager.pushSettings(appliedSettings)
                    if (pushR.isSuccess) appendLog("Settings synced to RayNeo glasses.")
                    else appendLog("Settings sync failed: ${pushR.exceptionOrNull()?.message}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                deviceManager = null
                appendLog("install(RAYNEO) crashed: ${e.message ?: e.javaClass.simpleName}")
                tvStatus.text = "Status: RayNeo install failed"
                renderCommandsForCurrentSelection(connected = false)
            } finally {
                btnConnect.isEnabled = true
            }
        }
    }

    private fun createRayNeoDeviceManagerFromInput(): RayNeoDeviceManager? {
        val host = etRayNeoIp.text?.toString()?.trim().orEmpty()
        if (host.isBlank()) {
            appendLog("RayNeo: please input glasses IP address first.")
            tvStatus.text = "Status: RayNeo IP missing"
            return null
        }
        return createRayNeoDeviceManager(host)
    }

    private fun createRayNeoDeviceManager(host: String): RayNeoDeviceManager {
        return RayNeoDeviceManager(
            context = this@MainActivity,
            config = RayNeoInstallerConfig(
                host = host,
                apk = RayNeoApkSource.Asset("rayneo_glass_app.apk"),
            ),
        )
    }
    // xg:device:rayneo:end

    // xg:device:rokid:begin
    private fun createRokidClient(): RokidGlassesClient {
        // 1. Try runtime credentials (user-provided via in-app UI).
        val auth = loadRokidAuthFromRuntime()
            // 2. Fall back to build-time credentials (local.properties / env / res/raw).
            ?: loadRokidAuthFromBuildConfig()

        if (auth == null) {
            appendLog(
                "Rokid: SN auth missing.\n" +
                    "  Option A (recommended): select your .lc file and enter client secret in the UI above.\n" +
                    "  Option B: put .lc under app/src/main/res/raw/ and set in local.properties:\n" +
                    "    rokid.clientSecret=<your-client-secret>\n" +
                    "    rokid.snRawName=<raw_resource_name_without_extension>"
            )
        }

        return RokidGlassesClient(
            this,
            RokidGlassesClient.RokidOptions(authorization = auth),
        )
    }
    // xg:device:rokid:end

    // xg:device:meta:begin
    private fun createMetaClient(): GlassesClient {
        return try {
            val clazz = Class.forName("com.xgglass.device.meta.MetaWearablesGlassesClient")
            val ctor = clazz.getConstructor(AppCompatActivity::class.java, ExternalActivityBridge::class.java)
            ctor.newInstance(
                this,
                ExternalActivityBridge { intent -> launchExternalActivity(intent) },
            ) as GlassesClient
        } catch (_: ClassNotFoundException) {
            throw IllegalStateException(
                "Meta DAT module is not available in this build. Set github_token in ~/.gradle/gradle.properties or export GITHUB_TOKEN, then sync again."
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create MetaWearablesGlassesClient: ${e.message}", e)
        }
    }
    // xg:device:meta:end

    // xg:device:meta:begin
    private suspend fun launchExternalActivity(intent: Intent): ExternalActivityResult {
        return externalActivityMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                externalActivityContinuation = continuation
                continuation.invokeOnCancellation {
                    if (externalActivityContinuation === continuation) {
                        externalActivityContinuation = null
                    }
                }
                externalActivityLauncher.launch(intent)
            }
        }
    }
    // xg:device:meta:end

    // xg:device:rokid:begin
    /**
     * Load Rokid authorization from runtime user input (internal storage + SecureStore).
     */
    private fun loadRokidAuthFromRuntime(): RokidGlassesClient.RokidAuthorization? {
        val secret = secureRokid.getString(PREF_ROKID_CLIENT_SECRET)?.trim().orEmpty()
        if (secret.isBlank()) return null

        val lcFile = File(filesDir, ROKID_LC_FILENAME)
        if (!lcFile.exists()) return null
        val bytes = try { lcFile.readBytes() } catch (_: Exception) { ByteArray(0) }
        if (bytes.isEmpty()) return null

        return RokidGlassesClient.RokidAuthorization(
            snLc = bytes,
            clientSecret = secret,
        )
    }
    // xg:device:rokid:end

    // xg:device:rokid:begin
    /**
     * Load Rokid authorization from build-time config (BuildConfig fields + res/raw resource).
     * This is the legacy path: developer sets rokid.clientSecret / rokid.snRawName in
     * local.properties (or env vars) and places the .lc file in app/src/main/res/raw/.
     */
    private fun loadRokidAuthFromBuildConfig(): RokidGlassesClient.RokidAuthorization? {
        val secret = BuildConfig.ROKID_CLIENT_SECRET.trim()
        val rawName = BuildConfig.ROKID_SN_RAW_NAME.trim()
        if (rawName.isBlank() || secret.isBlank()) return null

        val resId = resources.getIdentifier(rawName, "raw", packageName)
        if (resId == 0) return null

        val bytes = try {
            resources.openRawResource(resId).use { it.readBytes() }
        } catch (_: Exception) {
            ByteArray(0)
        }
        if (bytes.isEmpty()) return null

        return RokidGlassesClient.RokidAuthorization(
            snLc = bytes,
            clientSecret = secret,
        )
    }
    // xg:device:rokid:end

    private fun ensurePermissionsThenConnect(model: GlassesModel) {
        val required = requiredPermissionsFor(model)
        val missing = required.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            connect(model)
            return
        }
        pendingConnectModel = model
        appendLog("Requesting permissions: ${missing.joinToString()}")
        requestPermissionsLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissionsFor(model: GlassesModel): List<String> {
        val perms = mutableListOf<String>()

        if (model == GlassesModel.SIMULATOR || model == GlassesModel.INMO) {
            perms += Manifest.permission.CAMERA
            perms += Manifest.permission.RECORD_AUDIO
        }

        if (model == GlassesModel.META) {
            perms += Manifest.permission.RECORD_AUDIO
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_CONNECT
            }
        }

        // BLE permissions (Frame + Rokid + OMI + Even)
        if (
            model == GlassesModel.ROKID ||
            model == GlassesModel.FRAME ||
            model == GlassesModel.OMI ||
            model == GlassesModel.EVEN
        ) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_CONNECT
            } else {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
                perms += Manifest.permission.BLUETOOTH
                perms += Manifest.permission.BLUETOOTH_ADMIN
            }
        }

        // Rokid needs Wi‑Fi P2P on Android 13+
        if (model == GlassesModel.ROKID && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        return perms.distinct()
    }

    private fun appendLog(msg: String) {
        tvLog.text = tvLog.text.toString() + "\n" + msg
    }

    /** Update UI elements that depend on the currently selected device. */
    private fun onDeviceSelectionChanged(selected: String) {
        btnConnect.text = if (selected == "RAYNEO") "Install app to glasses" else "Connect"
        etRayNeoIp.visibility =
            if (selected == "RAYNEO") android.view.View.VISIBLE else android.view.View.GONE
        llRokidConfig.visibility =
            if (selected == "ROKID") android.view.View.VISIBLE else android.view.View.GONE

        // Display section is only useful for SIMULATOR mode
        val showDisplay = selected == "SIMULATOR"
        tvDisplayTitle.visibility =
            if (showDisplay) android.view.View.VISIBLE else android.view.View.GONE
        tvDisplay.visibility =
            if (showDisplay) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Toggle the collapsible settings panel. */
    private fun toggleSettingsCollapse() {
        val isVisible = llSettings.visibility == android.view.View.VISIBLE
        if (isVisible) {
            llSettings.visibility = android.view.View.GONE
            btnApplySettings.visibility = android.view.View.GONE
            tvSettingsTitle.text = "▶ Settings"
        } else {
            llSettings.visibility = android.view.View.VISIBLE
            btnApplySettings.visibility = android.view.View.VISIBLE
            tvSettingsTitle.text = "▼ Settings"
        }
    }

    private fun renderCommandsForCurrentSelection(connected: Boolean) {
        val model = when (spDevice.selectedItem?.toString()) {
            "SIMULATOR" -> GlassesModel.SIMULATOR
            "META" -> GlassesModel.META
            "FRAME" -> GlassesModel.FRAME
            "RAYNEO" -> GlassesModel.RAYNEO
            "INMO" -> GlassesModel.INMO
            "OMI" -> GlassesModel.OMI
            "EVEN" -> GlassesModel.EVEN
            else -> GlassesModel.ROKID
        }

        llCommands.removeAllViews()
        if (model == GlassesModel.RAYNEO) {
            llCommands.addView(TextView(this).apply {
                text = "RayNeo uses an on-glasses app. Install it to the glasses, then run commands on the glasses."
            })
            return
        }

        val e = entry
        if (e == null) {
            llCommands.addView(TextView(this).apply { text = "No UniversalAppEntry (meta-data com.xgglass.app_entry_class)" })
            return
        }

        if (!connected || client == null) {
            llCommands.addView(TextView(this).apply { text = "Connect first to enable commands." })
            return
        }

        val env = HostEnvironment(hostKind = HostKind.PHONE, model = model)
        val cmds = e.commandsWithDefaults(env)
        if (cmds.isEmpty()) {
            llCommands.addView(TextView(this).apply { text = "No commands for PHONE/${model.name}" })
            return
        }

        cmds.forEach { cmd ->
            llCommands.addView(Button(this).apply {
                text = cmd.title
                setOnClickListener {
                    scope.launch {
                        try {
                            val ctx = UniversalAppContext(
                                environment = env,
                                client = client!!,
                                scope = scope,
                                log = { appendLog(it) },
                                onCapturedImage = { img ->
                                    val bytes = img.jpegBytes
                                    if (bytes.isNotEmpty()) {
                                        scope.launch {
                                            val bmp = withContext(Dispatchers.Default) {
                                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            }
                                            if (bmp != null) ivPreview.setImageBitmap(bmp)
                                        }
                                    }
                                },
                                settings = appliedSettings,
                            )
                            val r = cmd.run(ctx)
                            if (r.isFailure) appendLog("Command failed: ${r.exceptionOrNull()?.message ?: "unknown"}")
                        } catch (e: Exception) {
                            appendLog("Command error: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
            })
        }
    }

    // ===================================================================
    // User settings UI
    // ===================================================================

    private val secureSettings by lazy {
        SecureStore.create(this, SECURE_SETTINGS_STORE).also(::migrateLegacySettingsIfNeeded)
    }

    /**
     * Render input fields for the entry's [UniversalAppEntry.userSettings].
     * Values are pre-filled from SecureStore (falling back to defaults).
     */
    private fun renderSettings() {
        val e = entry ?: return
        val fields = e.userSettings()
        if (fields.isEmpty()) return

        tvSettingsTitle.visibility = android.view.View.VISIBLE
        // Start collapsed — user clicks title to expand
        llSettings.visibility = android.view.View.GONE
        btnApplySettings.visibility = android.view.View.GONE
        tvSettingsTitle.text = "▶ AI Settings"
        llSettings.removeAllViews()
        settingEdits.clear()

        for (field in fields) {
            val label = TextView(this).apply {
                text = field.label
                setPadding(0, 12, 0, 2)
            }
            llSettings.addView(label)

            val editText = EditText(this).apply {
                hint = field.hint
                inputType = when (field.inputType) {
                    UserSettingInputType.PASSWORD ->
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    UserSettingInputType.URL ->
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    UserSettingInputType.NUMBER ->
                        InputType.TYPE_CLASS_NUMBER
                    else ->
                        InputType.TYPE_CLASS_TEXT
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                // Restore from encrypted storage, or use default
                val stored = secureSettings.getString(field.key)
                setText(stored ?: field.defaultValue)
            }
            llSettings.addView(editText)
            settingEdits[field.key] = editText
        }

        // Build the initial applied settings from stored/default values.
        appliedSettings = buildSettingsMap(fields)
    }

    /** Save current input values to SecureStore and update [appliedSettings]. */
    private fun applySettings() {
        val e = entry ?: return
        val fields = e.userSettings()
        for (field in fields) {
            val value = settingEdits[field.key]?.text?.toString().orEmpty()
            secureSettings.putString(field.key, value)
        }
        appliedSettings = buildSettingsMap(fields)
        appendLog("Settings applied.")

        // xg:device:rayneo:begin
        // For RayNeo: also push the settings file to the glasses via ADB so the
        // on-glasses host can read them.
        pushSettingsToRayNeoIfNeeded()
        // xg:device:rayneo:end
    }

    // xg:device:rayneo:begin
    /**
     * If the current (or last-configured) glasses model is RAYNEO and we have an IP,
     * push the settings JSON to the glasses via ADB.
     */
    private fun pushSettingsToRayNeoIfNeeded() {
        if (appliedSettings.isEmpty()) return

        // Use existing manager if the RayNeo install flow already created one.
        val rayNeoManager = deviceManager

        // Otherwise create a transient one if the user has selected RAYNEO and entered an IP.
        val selected = spDevice.selectedItem?.toString()
        val ip = etRayNeoIp.text?.toString()?.trim().orEmpty()

        if (rayNeoManager == null && (selected != "RAYNEO" || ip.isBlank())) return

        scope.launch {
            try {
                val pusher = rayNeoManager ?: createRayNeoDeviceManager(ip)
                val r = pusher.pushSettings(appliedSettings)
                if (r.isSuccess) {
                    appendLog("Settings pushed to RayNeo glasses.")
                } else {
                    appendLog("Settings push failed: ${r.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                appendLog("Settings push error: ${e.message}")
            }
        }
    }
    // xg:device:rayneo:end

    /** Build a key→value map from current SecureStore values (or defaults). */
    private fun buildSettingsMap(fields: List<UserSettingField>): Map<String, String> {
        return fields.associate { field ->
            val stored = secureSettings.getString(field.key)
            field.key to (stored ?: field.defaultValue)
        }
    }

    private fun migrateLegacySettingsIfNeeded(store: SecureStore) {
        if (store.getString(KEY_SETTINGS_MIGRATED) == "true") return
        val legacyPrefs = getSharedPreferences(LEGACY_SETTINGS_PREFS, Context.MODE_PRIVATE)
        legacyPrefs.all.forEach { (key, value) ->
            if (value is String && store.getString(key) == null) {
                store.putString(key, value)
            }
        }
        legacyPrefs.edit().clear().apply()
        deleteSharedPreferences(LEGACY_SETTINGS_PREFS)
        store.putString(KEY_SETTINGS_MIGRATED, "true")
    }

    // ===================================================================
    // Rokid runtime credentials
    // ===================================================================

    private val secureRokid by lazy {
        SecureStore.create(this, SECURE_ROKID_STORE).also(::migrateLegacyRokidIfNeeded)
    }

    /** Save the client secret from the UI into SecureStore. */
    private fun saveRokidCredentials() {
        val secret = etRokidSecret.text?.toString().orEmpty().trim()
        secureRokid.putString(PREF_ROKID_CLIENT_SECRET, secret)
    }

    /** Restore previously-saved credentials into the Rokid config UI. */
    private fun restoreRokidCredentialUI() {
        val secret = secureRokid.getString(PREF_ROKID_CLIENT_SECRET).orEmpty()
        if (secret.isNotBlank()) etRokidSecret.setText(secret)
        val displayName = secureRokid.getString(PREF_ROKID_LC_DISPLAY_NAME)
        if (!displayName.isNullOrBlank()) tvSnLicenseFile.text = displayName
    }

    private fun migrateLegacyRokidIfNeeded(store: SecureStore) {
        if (store.getString(KEY_ROKID_MIGRATED) == "true") return
        val legacyPrefs = getSharedPreferences(LEGACY_ROKID_PREFS, Context.MODE_PRIVATE)
        legacyPrefs.all.forEach { (key, value) ->
            if (value is String && store.getString(key) == null) {
                store.putString(key, value)
            }
        }
        legacyPrefs.edit().clear().apply()
        deleteSharedPreferences(LEGACY_ROKID_PREFS)
        store.putString(KEY_ROKID_MIGRATED, "true")
    }

    /** Try to extract a display file name from a content URI. */
    private fun queryFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
        }
    }

    private companion object {
        /** Internal storage file name for the persisted Rokid SN license bytes. */
        const val ROKID_LC_FILENAME = "rokid_sn_license.lc"
        const val SECURE_SETTINGS_STORE = "xgglass_user_settings_secure"
        const val LEGACY_SETTINGS_PREFS = "xgglass_user_settings"
        const val KEY_SETTINGS_MIGRATED = "__xgglass_settings_migrated"
        const val SECURE_ROKID_STORE = "xgglass_rokid_credentials_secure"
        const val LEGACY_ROKID_PREFS = "xgglass_rokid_credentials"
        const val KEY_ROKID_MIGRATED = "__xgglass_rokid_migrated"
        const val PREF_ROKID_CLIENT_SECRET = "rokid_client_secret"
        const val PREF_ROKID_LC_DISPLAY_NAME = "rokid_lc_display_name"
    }

    // ===================================================================

    private fun loadEntryOrNull(): UniversalAppEntry? {
        val cls = try {
            val appInfo = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
            appInfo.metaData?.getString("com.xgglass.app_entry_class")?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (cls.isBlank()) return null
        return try {
            val k = Class.forName(cls)
            k.getDeclaredConstructor().newInstance() as UniversalAppEntry
        } catch (_: Throwable) {
            null
        }
    }
}
