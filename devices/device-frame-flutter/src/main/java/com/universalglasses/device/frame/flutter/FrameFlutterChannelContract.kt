package com.universalglasses.device.frame.flutter

/**
 * Kotlin<->Flutter contract for the embedded Frame Flutter module.
 *
 * The host app should implement [FrameFlutterBridge] and route these calls to Flutter via MethodChannel/EventChannel.
 *
 * This SDK module deliberately does NOT depend on Flutter classes, to keep it optional.
 */
object FrameFlutterChannelContract {
    /**
     * A single bidirectional MethodChannel.
     *
     * - Android -> Flutter: connect/disconnect/capturePhoto/displayText
     * - Flutter -> Android: onEvent (logs/state/tap)
     */
    const val METHOD_CHANNEL = "universal_glasses/frame/methods"

    object Methods {
        const val CONNECT = "connect"
        const val DISCONNECT = "disconnect"
        const val CAPTURE_PHOTO = "capturePhoto"
        const val DISPLAY_TEXT = "displayText"
        const val START_MICROPHONE = "startMicrophone"
        const val STOP_MICROPHONE = "stopMicrophone"
        // Flutter -> Android
        const val ON_EVENT = "onEvent"
    }

    object Args {
        // capturePhoto
        const val QUALITY = "quality"
        const val TARGET_WIDTH = "targetWidth"
        const val TARGET_HEIGHT = "targetHeight"
        const val TIMEOUT_MS = "timeoutMs"

        // displayText
        const val TEXT = "text"
        const val FORCE = "force"
        const val MODE = "mode" // "replace" | "append"

        // microphone
        const val AUDIO_ENCODING = "audioEncoding" // "pcm_s16_le" | "pcm_s8" | "opus"
        const val AUDIO_SAMPLE_RATE_HZ = "sampleRateHz"
        const val AUDIO_CHANNEL_COUNT = "channelCount"
        const val AUDIO_VENDOR_MODE = "vendorMode"
    }

    object Events {
        // event "type" values (map payloads)
        const val TYPE = "type"
        const val TYPE_LOG = "log"
        const val TYPE_WARNING = "warning"
        const val TYPE_TAP = "tap"
        const val TYPE_STATE = "state"
        const val TYPE_AUDIO = "audio"

        // common fields
        const val MESSAGE = "message"

        // tap
        const val TAP_COUNT = "count"

        // state
        const val STATE_VALUE = "value" // "disconnected" | "connecting" | "connected" | "error"
        const val STATE_ERROR = "error" // string

        // audio
        const val AUDIO_BYTES = "bytes" // byte[]
        const val AUDIO_ENCODING = "encoding"
        const val AUDIO_SAMPLE_RATE_HZ = "sampleRateHz"
        const val AUDIO_CHANNEL_COUNT = "channelCount"
        const val AUDIO_SEQUENCE = "sequence"
        const val AUDIO_EOS = "eos"
    }
}


