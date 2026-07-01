# xg.glass SDK — Vibe Coding Reference

> **Purpose of this document**: This is a comprehensive SDK reference designed to be fed to AI coding assistants (e.g., ChatGPT, Claude, Cursor, Copilot) so they can help you build smart glasses applications using the xg.glass SDK. It contains all API surfaces, patterns, and runnable examples needed to generate working code.

---

## Table of Contents

1. [What is xg.glass](#1-what-is-xgglass)
2. [Supported Devices](#2-supported-devices)
3. [Architecture Overview](#3-architecture-overview)
4. [Quick Start](#4-quick-start)
5. [Core API Reference](#5-core-api-reference)
6. [App Entry & Lifecycle](#6-app-entry--lifecycle)
7. [Common Patterns](#7-common-patterns)
8. [Complete Example Apps](#8-complete-example-apps)
9. [User Settings & AI API Config](#9-user-settings--ai-api-config)
10. [Error Handling](#10-error-handling)
11. [Tips for AI-Assisted Development](#11-tips-for-ai-assisted-development)

---

## 1. What is xg.glass

xg.glass is a unified Kotlin SDK for building AI-powered smart glasses applications. It abstracts away vendor-specific SDKs (Rokid, Brilliant Labs Frame, RayNeo, etc.) behind four simple primitives:

| Primitive | Method | Description |
|-----------|--------|-------------|
| **Camera** | `capturePhoto()` | Capture a photo from the glasses camera |
| **Display** | `display(text)` | Show text on the glasses display |
| **Microphone** | `startMicrophone()` | Stream audio from the glasses mic |
| **Speaker** | `playAudio(source)` | Play TTS or raw audio on the glasses speaker |

Write your app logic once against these four APIs, and it runs on all supported glasses — or the simulator — without a single line of device-specific code.

---

## 2. Supported Devices

| Device | Model Enum | Camera | Display | Mic | Speaker |
|--------|-----------|--------|---------|-----|---------|
| Rokid Glasses | `GlassesModel.ROKID` | Yes | Yes | Yes | Yes (TTS + raw) |
| Meta AI Glasses | `GlassesModel.META` | Yes | No | Yes | Yes (raw) |
| Brilliant Labs Frame | `GlassesModel.FRAME` | Yes | Yes | Yes | No |
| RayNeo x2 / x3 Pro | `GlassesModel.RAYNEO` | Yes | Yes | Yes | Yes (raw) |
| Omi Glass | `GlassesModel.OMI` | Yes | No | Yes | No |
| Simulator (Emulator) | `GlassesModel.SIMULATOR` | Yes (webcam/video) | Yes | Yes | Yes (TTS + raw) |

Check capabilities at runtime via `ctx.client.capabilities`:

```kotlin
val caps = ctx.client.capabilities
if (caps.canRecordAudio) { /* safe to use startMicrophone() */ }
if (caps.canPlayTts) { /* safe to use playAudio(AudioSource.Tts(...)) */ }
```

---

## 3. Architecture Overview

An xg.glass app consists of a single Kotlin file implementing `UniversalAppEntrySimple`. The host app (phone or glasses) loads your entry via reflection, renders your commands as buttons, and calls `run(ctx)` when the user taps one.

```
┌──────────────────────────────────────────┐
│  Your App (single .kt file)             │
│  ┌────────────────────────────────────┐  │
│  │  class MyEntry : UniversalAppEntry │  │
│  │    commands() → [Command1, Cmd2…]  │  │
│  │                                    │  │
│  │  Command.run(ctx) {                │  │
│  │    ctx.client.capturePhoto()       │  │
│  │    ctx.client.display(text)        │  │
│  │    ctx.client.startMicrophone()    │  │
│  │    ctx.client.playAudio(source)    │  │
│  │  }                                 │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
          ↕ unified GlassesClient API
┌──────────────────────────────────────────┐
│  xg.glass SDK (handles connection,      │
│  device adapters, permissions, etc.)    │
└──────────────────────────────────────────┘
          ↕
┌──────────────────────────────────────────┐
│  Rokid / Meta / Frame / RayNeo / Omi / Sim │
└──────────────────────────────────────────┘
```

---

## 4. Quick Start

### Prerequisites

- JDK 17 or 21
- Android SDK + `adb` on PATH
- Flutter (for Frame support)
- Python 3.8+ (for the CLI)

### Install the CLI

```bash
git clone <xg-glass-repo>
cd <xg-glass-repo>
pip install -e .
xg-glass --help   # verify installation
```

### Meta AI Glasses setup

The Meta DAT Android artifacts are hosted on GitHub Packages, so configure a token before building:

```properties
# ~/.gradle/gradle.properties
github_token=ghp_xxxxxxxxxxxxx
```

or:

```bash
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxx
```

Notes:

- The token needs at least GitHub `read:packages` scope.
- When Meta support is enabled, generated Android host apps automatically use `minSdk 29` to satisfy the DAT camera dependency.

### Run a single-file app

```bash
# On real glasses (auto-detects connected device)
xg-glass run /path/to/MyEntry.kt

# On simulator (uses webcam as camera)
xg-glass run --sim /path/to/MyEntry.kt

# On simulator with a pre-recorded video
xg-glass run --sim --local_video /path/to/video.mp4 /path/to/MyEntry.kt
xg-glass run --sim --video_url <youtube-or-bilibili-url> /path/to/MyEntry.kt
```

### Create a full project

```bash
xg-glass init /path/to/myapp
cd /path/to/myapp
xg-glass build
xg-glass install
xg-glass run
```

---

## 5. Core API Reference

### 5.1 GlassesClient (the main interface)

This is the single interface your app interacts with. You receive it via `ctx.client` inside your command's `run()` method.

```kotlin
interface GlassesClient {
    val model: GlassesModel                    // Which device is connected
    val capabilities: DeviceCapabilities       // What this device can do
    val state: StateFlow<ConnectionState>      // Observe connection state
    val events: Flow<GlassesEvent>             // Non-fatal events (logs, taps)

    suspend fun connect(): Result<Unit>
    suspend fun disconnect()

    suspend fun capturePhoto(
        options: CaptureOptions = CaptureOptions()
    ): Result<CapturedImage>

    suspend fun display(
        text: String,
        options: DisplayOptions = DisplayOptions()
    ): Result<Unit>

    suspend fun playAudio(
        source: AudioSource,
        options: PlayAudioOptions = PlayAudioOptions()
    ): Result<Unit>

    suspend fun startMicrophone(
        options: MicrophoneOptions = MicrophoneOptions()
    ): Result<MicrophoneSession>
}
```

> **Note**: `connect()` and `disconnect()` are managed by the host. Your command code only needs to call `capturePhoto`, `display`, `playAudio`, and `startMicrophone`.

### 5.2 capturePhoto

Captures a JPEG image from the glasses camera and returns it.

```kotlin
// Default options (auto quality, auto size, 30s timeout)
val image: CapturedImage = ctx.client.capturePhoto().getOrThrow()

// With custom options
val image = ctx.client.capturePhoto(
    CaptureOptions(quality = 85, targetWidth = 1280, targetHeight = 720)
).getOrThrow()

// Access image data
image.jpegBytes        // ByteArray — JPEG data, ready for Base64 encoding or AI API
image.width            // Int? — pixel width
image.height           // Int? — pixel height
image.timestampMs      // Long — capture timestamp
image.rotationDegrees  // Int? — EXIF rotation hint
image.sourceModel      // GlassesModel — which device captured it
```

**CaptureOptions:**

```kotlin
data class CaptureOptions(
    val quality: Int? = null,          // JPEG quality 0..100 (Rokid default 90)
    val targetWidth: Int? = null,      // Desired width in pixels
    val targetHeight: Int? = null,     // Desired height in pixels
    val timeoutMs: Long = 30_000,      // Capture timeout in milliseconds
)
```

**CapturedImage:**

```kotlin
data class CapturedImage(
    val jpegBytes: ByteArray,
    val timestampMs: Long = System.currentTimeMillis(),
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    val sourceModel: GlassesModel,
)
```

### 5.3 display

Shows text on the glasses display. This is the primary output channel.

```kotlin
// Replace display content (default)
ctx.client.display("Hello from xg.glass!").getOrThrow()

// Explicitly replace
ctx.client.display("New content", DisplayOptions(mode = DisplayMode.REPLACE)).getOrThrow()

// Append to existing display content
ctx.client.display(" ...more text", DisplayOptions(mode = DisplayMode.APPEND)).getOrThrow()

// Force update (bypass throttling/dedup, useful for streaming AI output)
ctx.client.display(partialAnswer, DisplayOptions(force = true)).getOrThrow()
```

**DisplayOptions:**

```kotlin
enum class DisplayMode { REPLACE, APPEND }

data class DisplayOptions(
    val mode: DisplayMode = DisplayMode.REPLACE,  // REPLACE clears and shows new text;
                                                    // APPEND adds to existing text
    val force: Boolean = false,                    // Bypass any throttling/dedup logic
)
```

### 5.4 startMicrophone

Opens a streaming microphone session. Returns a `MicrophoneSession` whose `audio` field is a hot `Flow<AudioChunk>`.

```kotlin
// Default options (PCM 16-bit, 16kHz, mono)
val session: MicrophoneSession = ctx.client.startMicrophone().getOrThrow()

// With custom options
val session = ctx.client.startMicrophone(
    MicrophoneOptions(
        preferredSampleRateHz = 44_100,
        preferredChannelCount = 2,
        vendorMode = "voiceassistant",  // RayNeo-specific hint
    )
).getOrThrow()

// Collect audio for a fixed duration
val chunks = withTimeoutOrNull(8000L) {
    session.audio.toList()
} ?: emptyList()
session.stop()  // Always call stop() to release resources

// Combine chunks into a single byte array
val allBytes = chunks.flatMap { it.bytes.toList() }.toByteArray()
```

**MicrophoneSession:**

```kotlin
interface MicrophoneSession {
    val format: AudioFormat          // Actual format negotiated with the device
    val audio: Flow<AudioChunk>      // Hot stream — emits as soon as capture starts
    suspend fun stop()               // Release underlying resources (BLE / AudioRecord / vendor)
}
```

**MicrophoneOptions:**

```kotlin
data class MicrophoneOptions(
    val preferredEncoding: AudioEncoding = AudioEncoding.PCM_S16_LE,
    val preferredSampleRateHz: Int? = 16_000,
    val preferredChannelCount: Int? = 1,
    val vendorMode: String? = null,  // RayNeo-specific: "voiceassistant", "translation", "camcorder"
)
```

**AudioChunk:**

```kotlin
data class AudioChunk(
    val bytes: ByteArray,              // Raw audio data for this chunk
    val format: AudioFormat,           // Format of this chunk
    val sequence: Long,                // Monotonically increasing sequence number
    val timestampMs: Long = System.currentTimeMillis(),
    val endOfStream: Boolean = false,  // True when capture has ended (bytes may be empty)
)
```

**AudioFormat:**

```kotlin
data class AudioFormat(
    val encoding: AudioEncoding,  // PCM_S16_LE, PCM_S8, or OPUS
    val sampleRateHz: Int?,       // e.g. 16000, 44100 (nullable: some vendors don't expose it)
    val channelCount: Int?,       // e.g. 1 (mono), 2 (stereo) (nullable)
)
```

**AudioEncoding:**

```kotlin
enum class AudioEncoding {
    PCM_S16_LE,  // Signed 16-bit little-endian PCM (most common)
    PCM_S8,      // Signed 8-bit PCM
    OPUS,        // Opus frames (container-less)
}
```

### 5.5 playAudio

Plays audio on the glasses speaker. Two source types: text-to-speech (TTS) or raw audio bytes. Check `capabilities.canPlayTts` / `capabilities.canPlayAudioBytes` before calling.

```kotlin
// Text-to-speech (requires canPlayTts)
ctx.client.playAudio(AudioSource.Tts("Welcome!")).getOrThrow()

// TTS with custom speech rate (slower)
ctx.client.playAudio(
    AudioSource.Tts("Speaking slowly..."),
    PlayAudioOptions(speechRate = 0.8f),
).getOrThrow()

// Raw audio bytes — auto-detect container format (WAV/MP3/OGG)
ctx.client.playAudio(AudioSource.RawBytes(wavBytes)).getOrThrow()

// Raw headerless PCM with explicit format
ctx.client.playAudio(
    AudioSource.RawBytes(
        data = pcmBytes,
        pcmFormat = PcmFormat(sampleRateHz = 16_000, channelCount = 1, encoding = AudioEncoding.PCM_S16_LE),
    )
).getOrThrow()

// Play without interrupting current playback
ctx.client.playAudio(
    AudioSource.Tts("Queued after current audio"),
    PlayAudioOptions(interrupt = false),
).getOrThrow()
```

**AudioSource:**

```kotlin
sealed class AudioSource {
    data class Tts(val text: String) : AudioSource()
    data class RawBytes(
        val data: ByteArray,
        val pcmFormat: PcmFormat? = null,  // null = auto-detect container (WAV/MP3/OGG)
    ) : AudioSource()
}
```

**PcmFormat** (for headerless PCM passed via `AudioSource.RawBytes`):

```kotlin
data class PcmFormat(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val encoding: AudioEncoding = AudioEncoding.PCM_S16_LE,
)
```

**PlayAudioOptions:**

```kotlin
data class PlayAudioOptions(
    val speechRate: Float? = null,  // TTS speed multiplier, roughly 0.75..4.0
    val interrupt: Boolean = true,  // If true, interrupt any in-progress playback
)
```

### 5.6 DeviceCapabilities

Query what the connected device supports before calling optional features:

```kotlin
data class DeviceCapabilities(
    val canCapturePhoto: Boolean = true,
    val canDisplayText: Boolean = true,
    val canRecordAudio: Boolean = false,
    val canPlayTts: Boolean = false,
    val canPlayAudioBytes: Boolean = false,
    val supportsTapEvents: Boolean = false,
    val supportsStreamingTextUpdates: Boolean = false,
)
```

```kotlin
val caps = ctx.client.capabilities
if (caps.canRecordAudio) { /* safe to call startMicrophone() */ }
if (caps.canPlayTts)     { /* safe to call playAudio(AudioSource.Tts(...)) */ }
if (caps.canPlayAudioBytes) { /* safe to call playAudio(AudioSource.RawBytes(...)) */ }
```

### 5.7 Events

Subscribe to non-fatal device events:

```kotlin
sealed class GlassesEvent {
    data class Log(val message: String) : GlassesEvent()
    data class Warning(val message: String) : GlassesEvent()
    data class Tap(val count: Int) : GlassesEvent()  // e.g. single tap, double tap
}
```

```kotlin
ctx.client.events.collect { event ->
    when (event) {
        is GlassesEvent.Tap -> { /* user tapped ${event.count} times */ }
        is GlassesEvent.Log -> { /* informational: ${event.message} */ }
        is GlassesEvent.Warning -> { /* warning: ${event.message} */ }
    }
}
```

### 5.8 ConnectionState

```kotlin
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val error: GlassesError) : ConnectionState()
}
```

---

## 6. App Entry & Lifecycle

### 6.1 UniversalAppEntrySimple (recommended)

This is the simplest way to write an app. Implement one class, return your commands:

```kotlin
package com.example.myapp.logic

import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand
import com.universalglasses.core.DisplayOptions

class MyAppEntry : UniversalAppEntrySimple {
    override val id: String = "my_app"
    override val displayName: String = "My App"

    override fun commands(): List<UniversalCommand> {
        return listOf(
            object : UniversalCommand {
                override val id = "do_something"
                override val title = "Do Something"
                override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
                    // Your app logic here
                    return ctx.client.display("Done!", DisplayOptions())
                }
            }
        )
    }
}
```

### 6.2 UniversalAppEntry (advanced)

If you need different commands for phone vs. glasses hosts:

```kotlin
interface UniversalAppEntry {
    val id: String
    val displayName: String
    fun commands(env: HostEnvironment): List<UniversalCommand>
    fun userSettings(): List<UserSettingField> = emptyList()
}
```

**HostKind** — where your code is currently running:

```kotlin
enum class HostKind {
    PHONE,    // Running inside a phone-side Android app (controls peripheral glasses)
    GLASSES,  // Running inside an on-glasses Android app
}
```

**HostEnvironment** — host + device context:

```kotlin
data class HostEnvironment(
    val hostKind: HostKind,   // PHONE or GLASSES
    val model: GlassesModel,  // ROKID, META, FRAME, RAYNEO, OMI, SIMULATOR
)
```

### 6.3 UniversalCommand

Each command is a user-facing action (rendered as a button in the host UI):

```kotlin
interface UniversalCommand {
    val id: String       // Stable identifier
    val title: String    // Button label shown to user
    suspend fun run(ctx: UniversalAppContext): Result<Unit>
}
```

### 6.4 UniversalAppContext

The execution context passed to your command. Only `environment` and `client` are required; all other fields have defaults:

```kotlin
data class UniversalAppContext(
    val environment: HostEnvironment,                          // Phone or glasses, which device
    val client: GlassesClient,                                // The main SDK client — use this!
    val scope: CoroutineScope? = null,                        // For launching background work
    val log: (String) -> Unit = {},                           // Log to host UI
    val onCapturedImage: ((CapturedImage) -> Unit)? = null,   // Host callback for image preview
    val settings: Map<String, String> = emptyMap(),           // User-configured values (see Section 9)
)
```

In your command's `run()`, the most commonly used fields are:

- `ctx.client` — the `GlassesClient` for calling `capturePhoto`, `display`, etc.
- `ctx.settings` — user-configured values (API keys, model names, etc.)
- `ctx.log("message")` — send a log message to the host UI
- `ctx.onCapturedImage?.invoke(img)` — notify the host to show a captured image preview

### 6.5 File Structure for Single-File Run

When using `xg-glass run MyEntry.kt`, your file must:

1. Have a `package` declaration (e.g. `package com.example.myapp.logic`)
2. Contain a top-level `class` or `object` implementing `UniversalAppEntrySimple`

The CLI handles project scaffolding, build, install, and launch automatically.

---

## 7. Common Patterns

### 7.1 Capture Photo → AI → Display Result

The most common pattern. Capture an image, send to an AI model, display the result.

```kotlin
override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
    ctx.client.display("Capturing...", DisplayOptions())

    val img = ctx.client.capturePhoto().getOrThrow()
    val b64 = Base64.getEncoder().encodeToString(img.jpegBytes)

    // Call any AI API (OpenAI, Claude, Gemini, local model, etc.)
    val answer = callYourAI(b64)

    return ctx.client.display(answer, DisplayOptions())
}
```

### 7.2 Listen → Transcribe → AI → Display

Capture audio, transcribe with Whisper (or another ASR), process with AI, show result.

```kotlin
override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
    ctx.client.display("Listening...", DisplayOptions())

    val session = ctx.client.startMicrophone().getOrThrow()
    val chunks = withTimeoutOrNull(8000L) { session.audio.toList() } ?: emptyList()
    session.stop()

    val audioBytes = chunks.flatMap { it.bytes.toList() }.toByteArray()
    val transcript = transcribeWithWhisper(audioBytes, session.format)
    val response = callYourAI(transcript)

    return ctx.client.display(response, DisplayOptions())
}
```

### 7.3 Auto-Capture Loop

Continuously capture and process (e.g., real-time scene description, exam solving):

```kotlin
override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
    while (currentCoroutineContext().job.isActive) {
        val img = ctx.client.capturePhoto().getOrThrow()
        val answer = callYourAI(Base64.getEncoder().encodeToString(img.jpegBytes))
        ctx.client.display(answer, DisplayOptions())
        delay(3000) // Wait between captures
    }
    return Result.success(Unit)
}
```

The host cancels the coroutine to stop the loop.

### 7.4 Display + Speak Result

For devices with speaker support, display text and speak it aloud simultaneously:

```kotlin
override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
    val answer = "Your result text here"

    ctx.client.display(answer, DisplayOptions())

    if (ctx.client.capabilities.canPlayTts) {
        ctx.client.playAudio(AudioSource.Tts(answer))
    }

    return Result.success(Unit)
}
```

### 7.5 Multi-Command App

Provide multiple actions for the user to choose from. Each `object : UniversalCommand` is a separate button in the host UI:

```kotlin
override fun commands(): List<UniversalCommand> = listOf(
    object : UniversalCommand {
        override val id = "translate"
        override val title = "Translate"
        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val img = ctx.client.capturePhoto().getOrThrow()
            val text = callAI(img, "Translate the text in this image to Chinese.")
            return ctx.client.display(text, DisplayOptions())
        }
    },
    object : UniversalCommand {
        override val id = "describe"
        override val title = "Describe Scene"
        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val img = ctx.client.capturePhoto().getOrThrow()
            val text = callAI(img, "Describe this scene in one sentence.")
            return ctx.client.display(text, DisplayOptions())
        }
    },
    object : UniversalCommand {
        override val id = "read_aloud"
        override val title = "Read Aloud"
        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val img = ctx.client.capturePhoto().getOrThrow()
            val text = callAI(img, "Read all text in this image.")
            ctx.client.display(text, DisplayOptions())
            if (ctx.client.capabilities.canPlayTts) {
                ctx.client.playAudio(AudioSource.Tts(text))
            }
            return Result.success(Unit)
        }
    },
)
```

---

## 8. Complete Example Apps

See the sample apps repository for complete, runnable examples:

**https://github.com/hkust-spark/xg-glass-sample**

| Sample | Description | Key APIs Used |
|--------|-------------|---------------|
| **photo_translator** | Capture photo → AI translate → display result | `capturePhoto`, `display` |
| **exam_solver** | Auto-capture loop with streaming AI and conversation memory | `capturePhoto`, `display` (streaming with `force`), `AIApiSettings` |
| **subtext-interpreter** | Record speech → Whisper transcribe → GPT analyze → display advice | `startMicrophone`, `display`, `AIApiSettings` |

Each sample is a single `.kt` file that you can run directly:

```bash
cd xg-glass-sample/photo_translator
xg-glass run TranslationEntry.kt

# or with simulator:
xg-glass run --sim TranslationEntry.kt
```

---

## 9. User Settings & AI API Config

Apps can declare user-configurable settings (API keys, model names, URLs) that the host UI renders as input fields.

### Declaring settings

```kotlin
class MyEntry : UniversalAppEntrySimple {
    // ...
    override fun userSettings(): List<UserSettingField> = AIApiSettings.fields(
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o-mini",
    )
}
```

This creates three fields: **API Base URL**, **Model**, and **API Key**.

### Reading settings at runtime

```kotlin
override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
    val apiKey  = AIApiSettings.apiKey(ctx.settings)
    val baseUrl = AIApiSettings.baseUrl(ctx.settings)
    val model   = AIApiSettings.model(ctx.settings)
    val openAI  = OpenAI(apiKey) { host = OpenAIHost(baseUrl) }
    // ...
}
```

### Custom settings

```kotlin
override fun userSettings() = listOf(
    UserSettingField(
        key = "target_language",
        label = "Target Language",
        hint = "e.g. Chinese, Spanish, French",
        defaultValue = "Chinese",
    ),
    UserSettingField(
        key = "my_api_key",
        label = "API Key",
        inputType = UserSettingInputType.PASSWORD,
    ),
)

// Read in run():
val lang = ctx.settings["target_language"] ?: "Chinese"
```

---

## 10. Error Handling

All `GlassesClient` methods return `Result<T>`. Errors are typed:

```kotlin
sealed class GlassesError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object NotConnected : GlassesError("Not connected")
    data object PermissionDenied : GlassesError("Required permissions not granted")
    data object Busy : GlassesError("Device is busy")
    data class Timeout(val operation: String) : GlassesError("Timeout: $operation")
    data class Transport(val detail: String, val raw: Throwable? = null) : GlassesError(detail, raw)
    data class Unsupported(val detail: String) : GlassesError("Unsupported: $detail")
}
```

**Recommended patterns:**

```kotlin
// Pattern 1: getOrThrow (fail fast, let host handle errors)
val img = ctx.client.capturePhoto().getOrThrow()

// Pattern 2: getOrElse (graceful fallback)
val img = ctx.client.capturePhoto().getOrElse { error ->
    ctx.client.display("Camera error: ${error.message}", DisplayOptions())
    return Result.failure(error)
}

// Pattern 3: Check capabilities first
if (!ctx.client.capabilities.canRecordAudio) {
    return ctx.client.display("This device doesn't have a microphone.", DisplayOptions())
}
```

---

## 11. Tips for AI-Assisted Development

### For AI assistants generating xg.glass apps

1. **Always implement `UniversalAppEntrySimple`** unless the app needs different behavior on phone vs. glasses.

2. **Required imports** for a typical app:
   ```kotlin
   import com.universalglasses.appcontract.UniversalAppContext
   import com.universalglasses.appcontract.UniversalAppEntrySimple
   import com.universalglasses.appcontract.UniversalCommand
   import com.universalglasses.core.DisplayOptions
   ```

3. **For AI-powered apps, add:**
   ```kotlin
   import com.universalglasses.appcontract.AIApiSettings
   import com.universalglasses.appcontract.UserSettingField
   ```

4. **The OpenAI Kotlin client** (`com.aallam.openai`) is already available in the SDK dependencies. Use it for OpenAI-compatible APIs.

5. **Keep it simple** — a working glasses app can be built in ~10 lines of logic:
   ```kotlin
   val img = ctx.client.capturePhoto().getOrThrow()
   val b64 = Base64.getEncoder().encodeToString(img.jpegBytes)
   // ... call AI ...
   ctx.client.display(result, DisplayOptions())
   ```

6. **File naming**: name your file after your entry class (e.g., `MyAppEntry.kt`).

7. **Package declaration** is required: `package com.example.yourapp.logic`

8. **Don't manage connections** — the host handles `connect()`/`disconnect()`. Your code just uses `ctx.client`.

9. **Use `AIApiSettings`** instead of hardcoding API keys — this lets users configure their own keys via the host UI.

10. **Check capabilities** before using mic or speaker features — not all glasses support them.

### Skeleton template for AI to start from

```kotlin
package com.example.APPNAME.logic

import com.universalglasses.appcontract.*
import com.universalglasses.core.*
import com.aallam.openai.api.chat.*
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import java.util.Base64

class APPNAMEEntry : UniversalAppEntrySimple {
    override val id = "APPNAME"
    override val displayName = "APP DISPLAY NAME"

    override fun userSettings(): List<UserSettingField> = AIApiSettings.fields(
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o-mini",
    )

    override fun commands(): List<UniversalCommand> = listOf(
        object : UniversalCommand {
            override val id = "main_action"
            override val title = "ACTION TITLE"
            override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
                val openAI = OpenAI(AIApiSettings.apiKey(ctx.settings)) {
                    host = OpenAIHost(AIApiSettings.baseUrl(ctx.settings))
                }

                // === YOUR APP LOGIC HERE ===
                // Available primitives:
                //   ctx.client.capturePhoto()          → CapturedImage
                //   ctx.client.display(text, options)   → show text on glasses
                //   ctx.client.startMicrophone()        → MicrophoneSession
                //   ctx.client.playAudio(source)        → play TTS or audio

                return ctx.client.display("Done!", DisplayOptions())
            }
        }
    )
}
```

### CLI cheat sheet

```bash
# Run single file (fastest iteration)
xg-glass run MyEntry.kt                              # on real glasses
xg-glass run --sim MyEntry.kt                        # on simulator
xg-glass run --sim --local_video vid.mp4 MyEntry.kt  # simulator + video
xg-glass run --sim --video_url <url> MyEntry.kt      # simulator + YouTube/Bilibili

# Full project workflow
xg-glass init myapp        # create project
xg-glass build             # build APK
xg-glass install           # install on device
xg-glass run               # launch app
```

---

## Appendix: Available Dependencies

The following libraries are pre-configured in the app logic module (`ug_app_logic`) and can be used directly in your entry file:

| Library | Usage | Import |
|---------|-------|--------|
| xg.glass App Contract | Entry interfaces, `AIApiSettings`, `UniversalAppContext` | `com.universalglasses.appcontract.*` |
| xg.glass Core API | `GlassesClient`, models, options, errors | `com.universalglasses.core.*` |
| OpenAI Kotlin | ChatGPT, Whisper, DALL-E, OpenAI-compatible APIs | `com.aallam.openai.*` |
| Ktor Client (OkHttp) | HTTP engine for OpenAI client; also usable for custom HTTP requests | `io.ktor.client.*` |
| Kotlinx Coroutines | `delay`, `withTimeout`, `Flow`, `CoroutineScope`, etc. | `kotlinx.coroutines.*` |

For additional dependencies, add them to `ug_app_logic/build.gradle.kts` in a full project.
