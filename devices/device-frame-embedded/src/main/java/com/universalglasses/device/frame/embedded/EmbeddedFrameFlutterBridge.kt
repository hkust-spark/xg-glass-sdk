package com.universalglasses.device.frame.embedded

import android.content.Context
import com.universalglasses.core.AudioChunk
import com.universalglasses.core.AudioEncoding
import com.universalglasses.core.AudioFormat
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.core.MicrophoneOptions
import com.universalglasses.device.frame.flutter.FrameFlutterBridge
import com.universalglasses.device.frame.flutter.FrameFlutterChannelContract
import com.universalglasses.device.frame.flutter.FrameFlutterState
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugins.GeneratedPluginRegistrant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SDK-owned FlutterEngine + MethodChannel bridge.
 *
 * This removes the need for app developers to manually implement FrameFlutterBridge.
 * The only requirement is that the SDK ships with the embedded Flutter runtime/module
 * (today via included :flutter project; later via prebuilt AAR publication).
 */
class EmbeddedFrameFlutterBridge(
    private val context: Context,
) : FrameFlutterBridge {

    private val _state = MutableStateFlow<FrameFlutterState>(FrameFlutterState.Disconnected)
    override val state: StateFlow<FrameFlutterState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<GlassesEvent> = _events

    private val _mic = MutableSharedFlow<AudioChunk>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val microphone: Flow<AudioChunk> = _mic

    private val engine: FlutterEngine
    private val channel: MethodChannel

    init {
        val loader = FlutterInjector.instance().flutterLoader()
        if (!loader.initialized()) {
            loader.startInitialization(context)
        }
        loader.ensureInitializationComplete(context, null)

        engine = FlutterEngine(context)
        // register plugins (frame_ble depends on flutter_blue_plus, etc.)
        GeneratedPluginRegistrant.registerWith(engine)

        val entrypoint = DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "main")
        engine.dartExecutor.executeDartEntrypoint(entrypoint)

        channel = MethodChannel(engine.dartExecutor.binaryMessenger, FrameFlutterChannelContract.METHOD_CHANNEL)
        channel.setMethodCallHandler(::onFlutterCall)
    }

    private fun onFlutterCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != FrameFlutterChannelContract.Methods.ON_EVENT) {
            result.notImplemented()
            return
        }
        val payload = call.arguments as? Map<*, *> ?: run {
            result.success(null)
            return
        }

        val type = payload[FrameFlutterChannelContract.Events.TYPE]?.toString().orEmpty()
        when (type) {
            FrameFlutterChannelContract.Events.TYPE_STATE -> {
                val v = payload[FrameFlutterChannelContract.Events.STATE_VALUE]?.toString().orEmpty()
                val err = payload[FrameFlutterChannelContract.Events.STATE_ERROR]?.toString()
                _state.value = when (v) {
                    "connecting" -> FrameFlutterState.Connecting
                    "connected" -> FrameFlutterState.Connected
                    "error" -> FrameFlutterState.Error(err ?: "unknown")
                    else -> FrameFlutterState.Disconnected
                }
            }
            FrameFlutterChannelContract.Events.TYPE_LOG -> {
                val msg = payload[FrameFlutterChannelContract.Events.MESSAGE]?.toString().orEmpty()
                _events.tryEmit(GlassesEvent.Log(msg))
            }
            FrameFlutterChannelContract.Events.TYPE_WARNING -> {
                val msg = payload[FrameFlutterChannelContract.Events.MESSAGE]?.toString().orEmpty()
                _events.tryEmit(GlassesEvent.Warning(msg))
            }
            FrameFlutterChannelContract.Events.TYPE_TAP -> {
                val count = (payload[FrameFlutterChannelContract.Events.TAP_COUNT] as? Number)?.toInt() ?: 0
                _events.tryEmit(GlassesEvent.Tap(count))
            }
            FrameFlutterChannelContract.Events.TYPE_AUDIO -> {
                val bytes = payload[FrameFlutterChannelContract.Events.AUDIO_BYTES] as? ByteArray ?: ByteArray(0)
                val encStr = payload[FrameFlutterChannelContract.Events.AUDIO_ENCODING]?.toString().orEmpty()
                val sr = (payload[FrameFlutterChannelContract.Events.AUDIO_SAMPLE_RATE_HZ] as? Number)?.toInt()
                val ch = (payload[FrameFlutterChannelContract.Events.AUDIO_CHANNEL_COUNT] as? Number)?.toInt()
                val seq = (payload[FrameFlutterChannelContract.Events.AUDIO_SEQUENCE] as? Number)?.toLong() ?: 0L
                val eos = payload[FrameFlutterChannelContract.Events.AUDIO_EOS] as? Boolean ?: false

                val enc = when (encStr.lowercase()) {
                    "pcm_s8" -> AudioEncoding.PCM_S8
                    "opus" -> AudioEncoding.OPUS
                    else -> AudioEncoding.PCM_S16_LE
                }

                _mic.tryEmit(
                    AudioChunk(
                        bytes = bytes,
                        format = AudioFormat(
                            encoding = enc,
                            sampleRateHz = sr,
                            channelCount = ch,
                        ),
                        sequence = seq,
                        endOfStream = eos,
                    )
                )
            }
        }

        result.success(null)
    }

    override suspend fun connect(): Result<Unit> {
        _state.value = FrameFlutterState.Connecting
        return invoke<Boolean>(FrameFlutterChannelContract.Methods.CONNECT, null).map { Unit }
    }

    override suspend fun disconnect() {
        try {
            stopMicrophone()
        } catch (_: Exception) {}
        invoke<Boolean>(FrameFlutterChannelContract.Methods.DISCONNECT, null)
        _state.value = FrameFlutterState.Disconnected
        engine.destroy()
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        val args = mapOf(
            FrameFlutterChannelContract.Args.QUALITY to options.quality,
            FrameFlutterChannelContract.Args.TARGET_WIDTH to options.targetWidth,
            FrameFlutterChannelContract.Args.TARGET_HEIGHT to options.targetHeight,
            FrameFlutterChannelContract.Args.TIMEOUT_MS to options.timeoutMs,
        )
        val bytesRes = invoke<ByteArray>(FrameFlutterChannelContract.Methods.CAPTURE_PHOTO, args)
        return bytesRes.map { bytes ->
            CapturedImage(
                jpegBytes = bytes,
                width = null,
                height = null,
                rotationDegrees = null,
                sourceModel = GlassesModel.FRAME,
            )
        }
    }

    override suspend fun displayText(text: String, options: DisplayOptions): Result<Unit> {
        val args = mapOf(
            FrameFlutterChannelContract.Args.TEXT to text,
            FrameFlutterChannelContract.Args.FORCE to options.force,
            FrameFlutterChannelContract.Args.MODE to when (options.mode.name.lowercase()) {
                "append" -> "append"
                else -> "replace"
            }
        )
        return invoke<Boolean>(FrameFlutterChannelContract.Methods.DISPLAY_TEXT, args).map { Unit }
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<AudioFormat> {
        val args = mapOf(
            FrameFlutterChannelContract.Args.AUDIO_ENCODING to when (options.preferredEncoding) {
                AudioEncoding.PCM_S8 -> "pcm_s8"
                AudioEncoding.OPUS -> "opus"
                else -> "pcm_s16_le"
            },
            FrameFlutterChannelContract.Args.AUDIO_SAMPLE_RATE_HZ to options.preferredSampleRateHz,
            FrameFlutterChannelContract.Args.AUDIO_CHANNEL_COUNT to options.preferredChannelCount,
            FrameFlutterChannelContract.Args.AUDIO_VENDOR_MODE to options.vendorMode,
        )
        val res = invoke<Map<*, *>>(FrameFlutterChannelContract.Methods.START_MICROPHONE, args)
        return res.map { m ->
            val encStr = m["encoding"]?.toString().orEmpty()
            val sr = (m["sampleRateHz"] as? Number)?.toInt()
            val ch = (m["channelCount"] as? Number)?.toInt()
            val enc = when (encStr.lowercase()) {
                "pcm_s8" -> AudioEncoding.PCM_S8
                "opus" -> AudioEncoding.OPUS
                else -> AudioEncoding.PCM_S16_LE
            }
            AudioFormat(encoding = enc, sampleRateHz = sr, channelCount = ch)
        }
    }

    override suspend fun stopMicrophone(): Result<Unit> {
        return invoke<Boolean>(FrameFlutterChannelContract.Methods.STOP_MICROPHONE, null).map { Unit }
    }

    private suspend fun <T> invoke(method: String, args: Any?): Result<T> {
        return try {
            val out: T = suspendCancellableCoroutine { cont ->
                channel.invokeMethod(method, args, object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(result as T)
                    }

                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        cont.resumeWithException(GlassesError.Transport("Flutter error($errorCode): ${errorMessage ?: ""}"))
                    }

                    override fun notImplemented() {
                        cont.resumeWithException(GlassesError.Unsupported("Flutter method not implemented: $method"))
                    }
                })
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
