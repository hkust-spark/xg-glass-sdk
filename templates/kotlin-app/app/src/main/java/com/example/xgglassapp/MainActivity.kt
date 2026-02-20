package com.example.xgglassapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.universalglasses.appcontract.HostEnvironment
import com.universalglasses.appcontract.HostKind
import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntry
import com.universalglasses.appcontract.UserSettingField
import com.universalglasses.appcontract.UserSettingInputType
import com.universalglasses.appcontract.commandsWithDefaults
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.device.frame.embedded.EmbeddedFrameGlassesClient
import com.universalglasses.device.metawearable.MetaWearableGlassesClient
import com.universalglasses.device.rayneo.installer.RayNeoApkSource
import com.universalglasses.device.rayneo.installer.RayNeoInstallerConfig
import com.universalglasses.device.rayneo.installer.RayNeoInstallerGlassesClient
import com.universalglasses.device.rokid.RokidGlassesClient
import com.universalglasses.device.sim.EmulatorGlassesClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var client: com.universalglasses.core.GlassesClient? = null
    private var connectJob: Job? = null
    private var stateJob: Job? = null
    private var eventsJob: Job? = null
    private var pendingConnectModel: GlassesModel? = null

    private val requestPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result
                ->
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

    /**
     * Launcher for Meta Wearables camera permission. Uses the Meta AI app's permission contract;
     * result is a [PermissionStatus].
     */
    private val metaPermissionsLauncher =
            registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
                pendingConnectModel = null
                result
                        .onSuccess { status ->
                            if (status == PermissionStatus.Granted) {
                                connect(GlassesModel.META)
                            } else {
                                appendLog(
                                        "Meta camera permission denied (status: $status). Cannot connect."
                                )
                                tvStatus.text = "Status: Meta permission denied"
                            }
                        }
                        .onFailure { err ->
                            appendLog("Meta camera permission error: $err. Cannot connect.")
                            tvStatus.text = "Status: Meta permission error"
                        }
            }

    /** Launcher for picking the Rokid SN license (.lc) file at runtime. */
    private val pickSnLicenseLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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
                    rokidPrefs.edit().putString(PREF_ROKID_LC_DISPLAY_NAME, displayName).apply()
                    tvSnLicenseFile.text = displayName
                    appendLog("Rokid: SN license loaded (${bytes.size} bytes).")
                } catch (e: Exception) {
                    appendLog("Rokid: failed to read file – ${e.message}")
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Meta Wearables SDK
        try {
            Wearables.initialize(this)
        } catch (e: Exception) {
            // Might be already initialized or other issue
            // appendLog("Wearables init: ${e.message}")
        }

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

        btnPickSnLicense.setOnClickListener { pickSnLicenseLauncher.launch(arrayOf("*/*")) }

        val deviceItems =
                if (BuildConfig.XG_SIMULATOR) {
                    listOf("SIMULATOR")
                } else {
                    listOf("SIMULATOR", "ROKID", "FRAME", "RAYNEO", "META")
                }
        spDevice.adapter =
                ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        deviceItems,
                )

        spDevice.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
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
            val model =
                    when (selected) {
                        "SIMULATOR" -> GlassesModel.SIMULATOR
                        "FRAME" -> GlassesModel.FRAME
                        "RAYNEO" -> GlassesModel.RAYNEO
                        "META" -> GlassesModel.META
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

    private fun connect(model: GlassesModel) {
        connectJob?.cancel()
        connectJob =
                scope.launch {
                    btnConnect.isEnabled = false
                    tvStatus.text = "Status: switching to ${model.name}..."

                    // Disconnect previous client FIRST (sequentially) so we don't accidentally
                    // destroy the new one.
                    val old = client
                    client = null
                    try {
                        old?.disconnect()
                    } catch (e: Exception) {
                        appendLog("WARN: disconnect previous client failed: ${e.message}")
                    }

                    val newClient =
                            when (model) {
                                GlassesModel.SIMULATOR ->
                                        EmulatorGlassesClient(this@MainActivity) { text ->
                                            tvDisplay.text = text
                                        }
                                GlassesModel.META -> createMetaClient()
                                GlassesModel.ROKID -> createRokidClient()
                                GlassesModel.FRAME -> {
                                    // SDK-owned Flutter engine + bridge
                                    EmbeddedFrameGlassesClient(this@MainActivity)
                                }
                                GlassesModel.RAYNEO -> {
                                    val host = etRayNeoIp.text?.toString()?.trim().orEmpty()
                                    if (host.isBlank()) {
                                        appendLog("RayNeo: please input glasses IP address first.")
                                        tvStatus.text = "Status: RayNeo IP missing"
                                        btnConnect.isEnabled = true
                                        return@launch
                                    }
                                    RayNeoInstallerGlassesClient(
                                            context = this@MainActivity,
                                            config =
                                                    RayNeoInstallerConfig(
                                                            host = host,
                                                            apk =
                                                                    RayNeoApkSource.Asset(
                                                                            "rayneo_glass_app.apk"
                                                                    ),
                                                    )
                                    )
                                }
                            }
                    client = newClient

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
                    appendLog(
                            "connect(${model.name}) => ${r.isSuccess} ${r.exceptionOrNull()?.message ?: ""}"
                    )

                    // After successful RayNeo install, push the current user settings to the
                    // glasses.
                    if (r.isSuccess &&
                                    newClient is RayNeoInstallerGlassesClient &&
                                    appliedSettings.isNotEmpty()
                    ) {
                        val pushR = newClient.pushUserSettings(appliedSettings)
                        if (pushR.isSuccess) appendLog("Settings synced to RayNeo glasses.")
                        else appendLog("Settings sync failed: ${pushR.exceptionOrNull()?.message}")
                    }

                    btnConnect.isEnabled = true
                }
    }

    /**
     * Creates a [MetaWearableGlassesClient] after auditing all permissions required by the Meta
     * Wearables Device Access Toolkit (DAT).
     *
     * Required permissions (per DAT docs
     * https://wearables.developer.meta.com/docs/build-integration-android): Android runtime:
     * BLUETOOTH_CONNECT, BLUETOOTH_SCAN (API 31+) / BLUETOOTH + BLUETOOTH_ADMIN
     * ```
     *                    + ACCESS_FINE_LOCATION (below API 31), plus INTERNET.
     * ```
     * Meta SDK: Permission.CAMERA (granted via the Meta AI app; checked via Wearables API).
     */
    private suspend fun createMetaClient(): MetaWearableGlassesClient {
        // ── 1. Android runtime permissions ──────────────────────────────────────
        val androidPerms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            add(Manifest.permission.INTERNET)
        }

        val granted = mutableListOf<String>()
        val denied = mutableListOf<String>()
        for (perm in androidPerms) {
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            ) {
                granted += perm.substringAfterLast('.')
            } else {
                denied += perm.substringAfterLast('.')
            }
        }
        if (denied.isEmpty()) {
            appendLog("Meta: Android permissions OK (${granted.joinToString()}).")
        } else {
            appendLog(
                    "Meta: Missing Android permissions: ${denied.joinToString()}. " +
                            "These should have been requested already; connection may fail."
            )
        }

        // ── 2. Meta SDK camera permission (handled via Meta AI app) ─────────────
        try {
            val result = Wearables.checkPermissionStatus(Permission.CAMERA)
            result
                    .onSuccess { status ->
                        if (status == PermissionStatus.Granted) {
                            appendLog("Meta: camera permission Granted.")
                        } else {
                            appendLog(
                                    "Meta: camera permission not yet Granted (status: $status). " +
                                            "Grant it through the Meta AI app when prompted."
                            )
                        }
                    }
                    .onFailure { err -> appendLog("Meta: camera permission check error: $err") }
        } catch (e: Exception) {
            appendLog("Meta: camera permission check exception: ${e.message}")
        }

        // ── 3. Create and return the client ──────────────────────────────────────
        return MetaWearableGlassesClient(this)
    }

    private fun createRokidClient(): RokidGlassesClient {
        // 1. Try runtime credentials (user-provided via in-app UI).
        val auth =
                loadRokidAuthFromRuntime()
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

    /** Load Rokid authorization from runtime user input (internal storage + SharedPreferences). */
    private fun loadRokidAuthFromRuntime(): RokidGlassesClient.RokidAuthorization? {
        val secret = rokidPrefs.getString(PREF_ROKID_CLIENT_SECRET, null)?.trim().orEmpty()
        if (secret.isBlank()) return null

        val lcFile = File(filesDir, ROKID_LC_FILENAME)
        if (!lcFile.exists()) return null
        val bytes =
                try {
                    lcFile.readBytes()
                } catch (_: Exception) {
                    ByteArray(0)
                }
        if (bytes.isEmpty()) return null

        return RokidGlassesClient.RokidAuthorization(
                snLc = bytes,
                clientSecret = secret,
        )
    }

    /**
     * Load Rokid authorization from build-time config (BuildConfig fields + res/raw resource). This
     * is the legacy path: developer sets rokid.clientSecret / rokid.snRawName in local.properties
     * (or env vars) and places the .lc file in app/src/main/res/raw/.
     */
    private fun loadRokidAuthFromBuildConfig(): RokidGlassesClient.RokidAuthorization? {
        val secret = BuildConfig.ROKID_CLIENT_SECRET.trim()
        val rawName = BuildConfig.ROKID_SN_RAW_NAME.trim()
        if (rawName.isBlank() || secret.isBlank()) return null

        val resId = resources.getIdentifier(rawName, "raw", packageName)
        if (resId == 0) return null

        val bytes =
                try {
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

    private fun ensurePermissionsThenConnect(model: GlassesModel) {
        val required = requiredPermissionsFor(model)
        val missing =
                required.filter { perm ->
                    ContextCompat.checkSelfPermission(this, perm) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                }
        if (missing.isNotEmpty()) {
            pendingConnectModel = model
            appendLog("Requesting permissions: ${missing.joinToString()}")
            requestPermissionsLauncher.launch(missing.toTypedArray())
            return
        }

        if (model == GlassesModel.META) {
            val statusCheck = pendingConnectModel
            pendingConnectModel = model
            scope.launch {
                try {
                    val result = Wearables.checkPermissionStatus(Permission.CAMERA)
                    result
                            .onSuccess { status ->
                                if (status == PermissionStatus.Granted) {
                                    connect(GlassesModel.META)
                                } else {
                                    appendLog(
                                            "Requesting Meta Camera permission (current: $status)"
                                    )
                                    metaPermissionsLauncher.launch(Permission.CAMERA)
                                }
                            }
                            .onFailure { err ->
                                appendLog("Permission check failed: $err")
                                metaPermissionsLauncher.launch(Permission.CAMERA)
                            }
                } catch (e: Exception) {
                    appendLog("Meta SDK check error: ${e.message}")
                    // Fallback
                    metaPermissionsLauncher.launch(Permission.CAMERA)
                }
            }
            return
        }

        connect(model)
    }

    private fun requiredPermissionsFor(model: GlassesModel): List<String> {
        val perms = mutableListOf<String>()

        if (model == GlassesModel.SIMULATOR) {
            perms += Manifest.permission.CAMERA
            perms += Manifest.permission.RECORD_AUDIO
        }

        // BLE permissions (Frame + Rokid + Meta)
        if (model == GlassesModel.ROKID || model == GlassesModel.FRAME || model == GlassesModel.META
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

        // Meta DAT requires INTERNET for communication with the Meta AI app.
        if (model == GlassesModel.META) {
            perms += Manifest.permission.INTERNET
        }

        // Rokid needs Wi‑Fi P2P on Android 13+
        if (model == GlassesModel.ROKID &&
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        return perms.distinct()
    }

    private fun appendLog(msg: String) {
        tvLog.text = tvLog.text.toString() + "\n" + msg
    }

    /** Update UI elements that depend on the currently selected device. */
    private fun onDeviceSelectionChanged(selected: String) {
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
        val model =
                when (spDevice.selectedItem?.toString()) {
                    "SIMULATOR" -> GlassesModel.SIMULATOR
                    "FRAME" -> GlassesModel.FRAME
                    "RAYNEO" -> GlassesModel.RAYNEO
                    "META" -> GlassesModel.META
                    else -> GlassesModel.ROKID
                }

        llCommands.removeAllViews()
        val e = entry
        if (e == null) {
            llCommands.addView(
                    TextView(this).apply {
                        text =
                                "No UniversalAppEntry (meta-data com.universalglasses.app_entry_class)"
                    }
            )
            return
        }

        if (!connected || client == null) {
            llCommands.addView(TextView(this).apply { text = "Connect first to enable commands." })
            return
        }

        val env = HostEnvironment(hostKind = HostKind.PHONE, model = model)
        val cmds = e.commandsWithDefaults(env)
        if (cmds.isEmpty()) {
            llCommands.addView(
                    TextView(this).apply { text = "No commands for PHONE/${model.name}" }
            )
            return
        }

        cmds.forEach { cmd ->
            llCommands.addView(
                    Button(this).apply {
                        text = cmd.title
                        setOnClickListener {
                            scope.launch {
                                val ctx =
                                        UniversalAppContext(
                                                environment = env,
                                                client = client!!,
                                                scope = scope,
                                                log = { appendLog(it) },
                                                onCapturedImage = { img ->
                                                    val bytes = img.jpegBytes
                                                    if (bytes.isNotEmpty()) {
                                                        scope.launch {
                                                            val bmp =
                                                                    withContext(
                                                                            Dispatchers.Default
                                                                    ) {
                                                                        BitmapFactory
                                                                                .decodeByteArray(
                                                                                        bytes,
                                                                                        0,
                                                                                        bytes.size
                                                                                )
                                                                    }
                                                            if (bmp != null)
                                                                    ivPreview.setImageBitmap(bmp)
                                                        }
                                                    }
                                                },
                                                settings = appliedSettings,
                                        )
                                val r = cmd.run(ctx)
                                if (r.isFailure)
                                        appendLog(
                                                "Command failed: ${r.exceptionOrNull()?.message ?: "unknown"}"
                                        )
                            }
                        }
                    }
            )
        }
    }

    // ===================================================================
    // User settings UI
    // ===================================================================

    private val settingsPrefs by lazy {
        getSharedPreferences("ug_user_settings", Context.MODE_PRIVATE)
    }

    /**
     * Render input fields for the entry's [UniversalAppEntry.userSettings]. Values are pre-filled
     * from SharedPreferences (falling back to defaults).
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
            val label =
                    TextView(this).apply {
                        text = field.label
                        setPadding(0, 12, 0, 2)
                    }
            llSettings.addView(label)

            val editText =
                    EditText(this).apply {
                        hint = field.hint
                        inputType =
                                when (field.inputType) {
                                    UserSettingInputType.PASSWORD ->
                                            InputType.TYPE_CLASS_TEXT or
                                                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                                    UserSettingInputType.URL ->
                                            InputType.TYPE_CLASS_TEXT or
                                                    InputType.TYPE_TEXT_VARIATION_URI
                                    UserSettingInputType.NUMBER -> InputType.TYPE_CLASS_NUMBER
                                    else -> InputType.TYPE_CLASS_TEXT
                                }
                        layoutParams =
                                LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                )
                        // Restore from prefs, or use default
                        val stored = settingsPrefs.getString(field.key, null)
                        setText(stored ?: field.defaultValue)
                    }
            llSettings.addView(editText)
            settingEdits[field.key] = editText
        }

        // Build the initial applied settings from stored/default values.
        appliedSettings = buildSettingsMap(fields)
    }

    /** Save current input values to SharedPreferences and update [appliedSettings]. */
    private fun applySettings() {
        val e = entry ?: return
        val fields = e.userSettings()
        val editor = settingsPrefs.edit()
        for (field in fields) {
            val value = settingEdits[field.key]?.text?.toString().orEmpty()
            editor.putString(field.key, value)
        }
        editor.apply()
        appliedSettings = buildSettingsMap(fields)
        appendLog("Settings applied.")

        // For RayNeo: also push the settings file to the glasses via ADB so the
        // on-glasses host can read them.
        pushSettingsToRayNeoIfNeeded()
    }

    /**
     * If the current (or last-configured) glasses model is RAYNEO and we have an IP, push the
     * settings JSON to the glasses via ADB.
     */
    private fun pushSettingsToRayNeoIfNeeded() {
        if (appliedSettings.isEmpty()) return

        // Use existing client if it's already a RayNeo installer …
        val rayNeoClient = client as? RayNeoInstallerGlassesClient

        // … otherwise create a transient one if the user has selected RAYNEO and entered an IP.
        val selected = spDevice.selectedItem?.toString()
        val ip = etRayNeoIp.text?.toString()?.trim().orEmpty()

        if (rayNeoClient == null && (selected != "RAYNEO" || ip.isBlank())) return

        scope.launch {
            try {
                val pusher =
                        rayNeoClient
                                ?: RayNeoInstallerGlassesClient(
                                        context = this@MainActivity,
                                        config =
                                                RayNeoInstallerConfig(
                                                        host = ip,
                                                        apk =
                                                                RayNeoApkSource.Asset(
                                                                        "rayneo_glass_app.apk"
                                                                ),
                                                ),
                                )
                val r = pusher.pushUserSettings(appliedSettings)
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

    /** Build a key→value map from current SharedPreferences (or defaults). */
    private fun buildSettingsMap(fields: List<UserSettingField>): Map<String, String> {
        return fields.associate { field ->
            val stored = settingsPrefs.getString(field.key, null)
            field.key to (stored ?: field.defaultValue)
        }
    }

    // ===================================================================
    // Rokid runtime credentials
    // ===================================================================

    private val rokidPrefs by lazy {
        getSharedPreferences("ug_rokid_credentials", Context.MODE_PRIVATE)
    }

    /** Save the client secret from the UI into SharedPreferences. */
    private fun saveRokidCredentials() {
        val secret = etRokidSecret.text?.toString().orEmpty().trim()
        rokidPrefs.edit().putString(PREF_ROKID_CLIENT_SECRET, secret).apply()
    }

    /** Restore previously-saved credentials into the Rokid config UI. */
    private fun restoreRokidCredentialUI() {
        val secret = rokidPrefs.getString(PREF_ROKID_CLIENT_SECRET, null).orEmpty()
        if (secret.isNotBlank()) etRokidSecret.setText(secret)
        val displayName = rokidPrefs.getString(PREF_ROKID_LC_DISPLAY_NAME, null)
        if (!displayName.isNullOrBlank()) tvSnLicenseFile.text = displayName
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
        const val PREF_ROKID_CLIENT_SECRET = "rokid_client_secret"
        const val PREF_ROKID_LC_DISPLAY_NAME = "rokid_lc_display_name"
    }

    // ===================================================================

    private fun loadEntryOrNull(): UniversalAppEntry? {
        val cls =
                try {
                    val appInfo =
                            packageManager.getApplicationInfo(
                                    packageName,
                                    android.content.pm.PackageManager.GET_META_DATA
                            )
                    appInfo.metaData
                            ?.getString("com.universalglasses.app_entry_class")
                            ?.trim()
                            .orEmpty()
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
