package com.example.xgglassapp

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Bundle
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
    private lateinit var etRayNeoIp: EditText
    private lateinit var llCommands: LinearLayout

    private val entry: UniversalAppEntry? by lazy { loadEntryOrNull() }

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
                if (model != null) {
                    // Re-enter check flow to handle any secondary permissions (e.g. Meta SDK)
                    ensurePermissionsThenConnect(model)
                }
            }

    private val metaPermissionsLauncher =
            registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
                var granted = false
                result.onSuccess { status ->
                    if (status == PermissionStatus.Granted) granted = true
                }

                if (granted) {
                    val model = pendingConnectModel
                    if (model != null) connect(model)
                } else {
                    appendLog("Meta permission denied: $result")
                    tvStatus.text = "Status: Meta permission denied"
                    pendingConnectModel = null
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
        etRayNeoIp = findViewById(R.id.etRayNeoIp)
        llCommands = findViewById(R.id.llCommands)

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
                        val selected = spDevice.selectedItem?.toString() ?: "ROKID"
                        etRayNeoIp.visibility =
                                if (selected == "RAYNEO") android.view.View.VISIBLE
                                else android.view.View.GONE
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
            ensurePermissionsThenConnect(model)
        }

        renderCommandsForCurrentSelection(connected = false)

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
                                GlassesModel.META -> MetaWearableGlassesClient(this@MainActivity)
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
                    btnConnect.isEnabled = true
                }
    }

    private fun createRokidClient(): RokidGlassesClient {
        val secret = BuildConfig.ROKID_CLIENT_SECRET.trim()
        val rawName = BuildConfig.ROKID_SN_RAW_NAME.trim()

        val auth =
                loadRokidAuthorizationOrNull(
                        rawName = rawName,
                        clientSecret = secret,
                )
        if (auth == null) {
            appendLog(
                    "Rokid: SN auth missing. Put your .lc under app/src/main/res/raw/ and set in local.properties:\n" +
                            "  rokid.clientSecret=<your-client-secret>\n" +
                            "  rokid.snRawName=<raw_resource_name_without_extension>\n" +
                            "Or use env vars ROKID_CLIENT_SECRET / ROKID_SN_RAW_NAME."
            )
        }

        return RokidGlassesClient(
                this,
                RokidGlassesClient.RokidOptions(authorization = auth),
        )
    }

    private fun loadRokidAuthorizationOrNull(
            rawName: String,
            clientSecret: String,
    ): RokidGlassesClient.RokidAuthorization? {
        if (rawName.isBlank() || clientSecret.isBlank()) return null

        // rawName is the resource entry name without extension, e.g. "sn_0a98..." for
        // res/raw/sn_0a98....lc
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
                clientSecret = clientSecret,
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
                                                }
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
