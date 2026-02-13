package com.universalglasses.appcontract

import com.universalglasses.core.GlassesClient
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.GlassesModel
import kotlinx.coroutines.CoroutineScope

/**
 * "Universal app" contract.
 *
 * Goal: let developers write the same business logic against [GlassesClient] for different glasses models,
 * while different hosts (phone app vs on-glasses app) render UI and trigger actions.
 *
 * This module only defines the *entry interfaces*. Build automation (e.g., generating a RayNeo host APK,
 * copying it into the phone app, installing it on connect) is implemented separately.
 */

/** Where this code is currently running. */
enum class HostKind {
    /** Running inside a phone-side Android app (controls peripheral glasses and installs on-glasses apps). */
    PHONE,

    /** Running inside an on-glasses Android app. */
    GLASSES,
}

/** Host + device context provided to the developer entry. */
data class HostEnvironment(
    val hostKind: HostKind,
    val model: GlassesModel,
)

// ---------------------------------------------------------------------------
// User-configurable settings
// ---------------------------------------------------------------------------

/** Input type hint for the host UI when rendering a [UserSettingField]. */
enum class UserSettingInputType {
    TEXT,
    /** Masked input (e.g. for API keys). */
    PASSWORD,
    URL,
    NUMBER,
}

/**
 * Describes a single user-configurable setting field.
 *
 * The host renders an appropriate input widget for each field and persists the value.
 * Values are available at runtime via [UniversalAppContext.settings].
 */
data class UserSettingField(
    /** Unique key used to store/retrieve the value (e.g. "ai_api_base_url"). */
    val key: String,
    /** Label displayed above/beside the input field. */
    val label: String,
    /** Placeholder text shown when the field is empty. */
    val hint: String = "",
    /** Default value if the user hasn't configured one. */
    val defaultValue: String = "",
    /** Input type hint for the host UI. */
    val inputType: UserSettingInputType = UserSettingInputType.TEXT,
)

/**
 * Convenience factory for common AI API configuration fields (base URL, model, API key).
 *
 * Usage in [UniversalAppEntry.userSettings]:
 * ```
 * override fun userSettings() = AIApiSettings.fields(
 *     defaultBaseUrl = "https://api.openai.com/v1/",
 *     defaultModel = "gpt-4o",
 * )
 * ```
 *
 * Reading values in a [UniversalCommand.run]:
 * ```
 * val baseUrl = AIApiSettings.baseUrl(ctx.settings)
 * val model   = AIApiSettings.model(ctx.settings)
 * val apiKey  = AIApiSettings.apiKey(ctx.settings)
 * ```
 */
object AIApiSettings {
    const val KEY_BASE_URL = "ai_api_base_url"
    const val KEY_MODEL = "ai_api_model"
    const val KEY_API_KEY = "ai_api_key"

    /** Create the standard three AI-API setting fields. */
    fun fields(
        defaultBaseUrl: String = "",
        defaultModel: String = "",
        defaultApiKey: String = "",
    ): List<UserSettingField> = listOf(
        UserSettingField(
            key = KEY_BASE_URL,
            label = "API Base URL",
            hint = "e.g. https://api.openai.com/v1/",
            defaultValue = defaultBaseUrl,
            inputType = UserSettingInputType.URL,
        ),
        UserSettingField(
            key = KEY_MODEL,
            label = "Model",
            hint = "e.g. gpt-4o",
            defaultValue = defaultModel,
            inputType = UserSettingInputType.TEXT,
        ),
        UserSettingField(
            key = KEY_API_KEY,
            label = "API Key",
            hint = "Your API key",
            defaultValue = defaultApiKey,
            inputType = UserSettingInputType.PASSWORD,
        ),
    )

    /** Read the base URL from a settings map. */
    fun baseUrl(settings: Map<String, String>): String = settings[KEY_BASE_URL].orEmpty()

    /** Read the model name from a settings map. */
    fun model(settings: Map<String, String>): String = settings[KEY_MODEL].orEmpty()

    /** Read the API key from a settings map. */
    fun apiKey(settings: Map<String, String>): String = settings[KEY_API_KEY].orEmpty()
}

// ---------------------------------------------------------------------------
// Execution context
// ---------------------------------------------------------------------------

/**
 * Execution context passed to user commands.
 *
 * - [client] is the active [GlassesClient] for the selected model.
 * - [environment] tells you whether you're running on phone or on glasses.
 * - [log] lets hosts surface logs (phone UI log view / glasses overlay, etc).
 * - [settings] contains user-configured values declared via [UniversalAppEntry.userSettings].
 */
data class UniversalAppContext(
    val environment: HostEnvironment,
    val client: GlassesClient,
    val scope: CoroutineScope? = null,
    val log: (String) -> Unit = {},
    /**
     * Optional host callback for captured images.
     *
     * - Phone host can show a preview.
     * - Glasses host can ignore it (null).
     */
    val onCapturedImage: ((CapturedImage) -> Unit)? = null,
    /**
     * User-configured settings declared by [UniversalAppEntry.userSettings].
     *
     * Keys match [UserSettingField.key]; values are what the user entered (or the default).
     */
    val settings: Map<String, String> = emptyMap(),
)

/** A user-facing action (host renders it as a button/menu item/gesture selection, etc). */
interface UniversalCommand {
    val id: String
    val title: String

    suspend fun run(ctx: UniversalAppContext): Result<Unit>
}

/**
 * Developer-implemented entry point.
 *
 * Hosts (phone and/or glasses) instantiate the entry (via reflection or explicit wiring) and call
 * [commands] to render actions.
 */
interface UniversalAppEntry {
    /** Stable identifier for analytics / routing (e.g., "auto_solver"). */
    val id: String

    /** Display name for UI (e.g., "Auto Solver"). */
    val displayName: String

    /**
     * Provide the actions this app supports in the given host/device environment.
     *
     * Example: for RayNeo, you might return capture/display actions for [HostKind.GLASSES],
     * and return an empty list for [HostKind.PHONE] (phone is only the installer).
     */
    fun commands(env: HostEnvironment): List<UniversalCommand>

    /**
     * Declare user-configurable settings for this app.
     *
     * The host renders input fields for each [UserSettingField], persists values, and passes
     * them to commands via [UniversalAppContext.settings].
     *
     * Default: no settings.
     *
     * @see AIApiSettings for a convenience factory for AI API fields.
     */
    fun userSettings(): List<UserSettingField> = emptyList()
}

/**
 * Optional simplified entry for developers who want to write one set of commands for all hosts/devices.
 *
 * This keeps [UniversalAppEntry] stable (hosts still pass [HostEnvironment]), while allowing developers to
 * ignore host/device differences by implementing a parameterless [commands].
 */
interface UniversalAppEntrySimple : UniversalAppEntry {
    fun commands(): List<UniversalCommand>

    override fun commands(env: HostEnvironment): List<UniversalCommand> = commands()
}

/**
 * Default host-side command filtering policy provided by the SDK.
 *
 * This exists so app developers don't have to repeat common "host quirks" in every entry implementation.
 */
object UniversalCommandPolicy {
    /**
     * Apply SDK defaults for which commands should be exposed in a given host environment.
     *
     * Current default:
     * - RayNeo on PHONE host is installer-only, so we hide commands there by default.
     */
    fun filterCommands(env: HostEnvironment, commands: List<UniversalCommand>): List<UniversalCommand> {
        return if (env.hostKind == HostKind.PHONE && env.model == GlassesModel.RAYNEO) emptyList() else commands
    }
}

/**
 * Convenience wrapper for hosts: apply SDK default filtering on top of [UniversalAppEntry.commands].
 *
 * Hosts should prefer calling this over [UniversalAppEntry.commands] directly.
 */
fun UniversalAppEntry.commandsWithDefaults(env: HostEnvironment): List<UniversalCommand> {
    return UniversalCommandPolicy.filterCommands(env, commands(env))
}


