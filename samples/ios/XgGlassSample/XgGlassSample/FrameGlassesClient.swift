@_implementationOnly import Flutter
import Foundation
import XgGlassMetaTesting

final class FrameGlassesClient: BaseGlassesClient {
    private let runtime: FrameFlutterRuntime?
    private let runtimeStartupError: Error?
    private let ownsRuntime: Bool
    private let connectTimeoutSeconds: TimeInterval
    private var eventObserverToken: UUID?
    private var stateObserverToken: UUID?
    private var activeMicSink: PushMicrophoneSession?

    init(runtime: FrameFlutterRuntime? = nil, connectTimeoutSeconds: TimeInterval = 10) {
        if let runtime {
            // Injected runtime is owned by the caller; do not shut it down on deinit.
            self.runtime = runtime
            self.runtimeStartupError = nil
            self.ownsRuntime = false
        } else {
            do {
                self.runtime = try FrameFlutterRuntime()
                self.runtimeStartupError = nil
                self.ownsRuntime = true
            } catch {
                self.runtime = nil
                self.runtimeStartupError = error
                self.ownsRuntime = false
            }
        }
        self.connectTimeoutSeconds = connectTimeoutSeconds

        super.init(
            initialCapabilities: DeviceCapabilities(
                canCapturePhoto: true,
                canDisplayText: true,
                canRecordAudio: true,
                canPlayTts: false,
                canPlayAudioBytes: false,
                supportsTapEvents: true,
                supportsStreamingTextUpdates: true
            ),
            eventBufferOverflow: .dropOldest
        )

        eventObserverToken = self.runtime?.addEventObserver { [weak self] payload in
            self?.handleRuntimeEvent(payload)
        }
        stateObserverToken = self.runtime?.addStateObserver { [weak self] state in
            self?.handleRuntimeState(state)
        }
    }

    deinit {
        if let eventObserverToken {
            runtime?.removeEventObserver(eventObserverToken)
        }
        if let stateObserverToken {
            runtime?.removeStateObserver(stateObserverToken)
        }
        // Only tear down the FlutterEngine if we created it; an injected runtime is caller-owned.
        if ownsRuntime {
            runtime?.shutdown()
        }
    }

    override var model: GlassesModel {
        .frame
    }

    override func doConnect(completionHandler: @escaping @Sendable (Error?) -> Void) {
        guard let runtime else {
            completionHandler(runtimeUnavailableError())
            return
        }

        let resolver = FrameCompletionResolver<Error?>(completionHandler)
        let timeoutSeconds = connectTimeoutSeconds
        let timeout = DispatchWorkItem { [weak self] in
            let message = "Frame connect failed: timed out waiting for Flutter BLE connect after \(timeoutSeconds) seconds"
            self?.emitWarn(message: message)
            resolver.resolve(GlassesError.Transport(detail: message, cause: nil).asError())
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + timeoutSeconds, execute: timeout)

        // The Dart "connect" reply resolves only after _connect() completes (or throws), so a
        // non-error reply is an authoritative success. Reading a separately-delivered state event
        // here would race the reply and spuriously fail; the base marks Connected and the state
        // observer keeps ConnectionState in sync afterwards.
        runtime.connect { _, error in
            timeout.cancel()
            if let error {
                resolver.resolve(frameTransportError("Frame connect failed", error: error))
            } else {
                resolver.resolve(nil)
            }
        }
    }

    override func mapConnectError(error: KotlinException) -> GlassesError {
        if let glassesError = error as? GlassesError {
            return glassesError
        }
        return GlassesError.Transport(detail: "Frame connect failed: \(error.message ?? "unknown error")", cause: error)
    }

    override func disconnect(completionHandler: @escaping @Sendable (Error?) -> Void) {
        guard let runtime else {
            clearActiveMic()
            _state.setValue(ConnectionState.Disconnected.shared)
            completionHandler(nil)
            return
        }

        runtime.disconnect { [weak self] _, error in
            self?.clearActiveMic()
            self?._state.setValue(ConnectionState.Disconnected.shared)
            if let error {
                // frameTransportError is a free function, so a released `self` cannot collapse
                // a real error into a false success.
                completionHandler(frameTransportError("Frame disconnect failed", error: error))
            } else {
                completionHandler(nil)
            }
        }
    }

    override func capturePhoto(options: CaptureOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        guard let runtime else {
            completionHandler(nil, runtimeUnavailableError())
            return
        }

        runtime.capturePhoto(arguments: capturePhotoArguments(options)) { [weak self] bytes, error in
            guard let self else {
                completionHandler(nil, GlassesError.Transport(detail: "Frame client was released during capture", cause: nil).asError())
                return
            }
            if let error {
                completionHandler(nil, frameOperationError("Frame photo capture failed", operation: "capturePhoto", error: error))
                return
            }
            guard let bytes else {
                completionHandler(nil, GlassesError.Transport(detail: "Frame photo capture returned no bytes", cause: nil).asError())
                return
            }

            let image = CapturedImage(
                jpegBytes: bytes.data.toKotlinByteArray(),
                timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
                width: nil,
                height: nil,
                rotationDegrees: nil,
                sourceModel: self.model
            )
            completionHandler(image, nil)
        }
    }

    override func display(text: String, options: DisplayOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        guard let runtime else {
            completionHandler(nil, runtimeUnavailableError())
            return
        }

        runtime.displayText(arguments: displayTextArguments(text: text, options: options)) { _, error in
            if let error {
                completionHandler(nil, frameOperationError("Frame display failed", operation: "displayText", error: error))
            } else {
                completionHandler(nil, nil)
            }
        }
    }

    override func playAudio(source: AudioSource, options: PlayAudioOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        completionHandler(
            nil,
            GlassesError.Unsupported(detail: "Frame does not have a speaker; audio playback is not supported").asError()
        )
    }

    override func startMicrophone(options: MicrophoneOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        guard let runtime else {
            completionHandler(nil, runtimeUnavailableError())
            return
        }
        guard activeMicSink == nil else {
            completionHandler(nil, GlassesError.Busy.shared.asError())
            return
        }

        runtime.invoke(method: "startMicrophone", arguments: microphoneArguments(options)) { [weak self] response, error in
            guard let self else {
                completionHandler(nil, GlassesError.Transport(
                    detail: "Frame client was released during microphone start",
                    cause: nil
                ).asError())
                return
            }
            if let error {
                completionHandler(nil, frameOperationError("Frame startMicrophone failed", operation: "startMicrophone", error: error))
                return
            }

            let format = self.audioFormat(from: response)
            let sink = PushMicrophoneSession(
                format: format,
                onStop: { [weak self] in
                    self?.runtime?.invoke(method: "stopMicrophone", arguments: nil) { _, _ in }
                    self?.clearActiveMic()
                },
                extraBufferCapacity: 128
            )
            self.activeMicSink = sink
            completionHandler(sink, nil)
        }
    }

    private func handleRuntimeState(_ state: FrameFlutterState) {
        switch state {
        case .connecting, .connected:
            // The base owns Connecting/Connected during connect(). Spontaneous auto-reconnect
            // from the Flutter/BLE side is not reflected yet.
            // TODO(frame-ios-reconnect): map a spontaneous .connected -> ConnectionState.Connected.
            break
        case .disconnected:
            // Don't let a post-failure "disconnected" event clobber a just-set Error state
            // (the base sets Error from a failed doConnect; the Dart side may then emit disconnected).
            if !(_state.value is ConnectionState.Error) {
                _state.setValue(ConnectionState.Disconnected.shared)
            }
        case .error(let message):
            _state.setValue(ConnectionState.Error(error: GlassesError.Transport(
                detail: "Frame error: \(message)",
                cause: nil
            )))
        }
    }

    private func handleRuntimeEvent(_ payload: [String: Any]) {
        switch payload["type"] as? String {
        case "log":
            if let message = payload["message"] as? String {
                emitLog(message: message)
            }
        case "warning":
            if let message = payload["message"] as? String {
                emitWarn(message: message)
            }
        case "tap":
            let count = int32Value(payload["count"]) ?? 1
            _ = _events.tryEmit(value: GlassesEvent.Tap(count: count))
        case "audio":
            handleAudioEvent(payload)
        case "state":
            // "state" is handled by the state observer.
            break
        default:
            emitWarn(message: "Frame: ignoring unhandled event type \(payload["type"] as? String ?? "unknown")")
        }
    }

    private func handleAudioEvent(_ payload: [String: Any]) {
        guard let sink = activeMicSink else {
            return
        }

        let sequence = int64Value(payload["sequence"]) ?? 0
        if (payload["eos"] as? Bool) == true {
            sink.emitEndOfStream(sequence: sequence)
            clearActiveMic()
            return
        }

        if let bytes = payload["bytes"] as? FlutterStandardTypedData {
            _ = sink.emit(bytes: bytes.data.toKotlinByteArray(), sequence: sequence)
        }
    }

    private func capturePhotoArguments(_ options: CaptureOptions) -> [String: Any] {
        var args: [String: Any] = [
            "quality": wireJpegQuality(options.photoQuality),
            "timeoutMs": options.timeoutMs,
        ]
        if let targetWidth = options.targetWidth {
            args["targetWidth"] = Int(targetWidth.int32Value)
        }
        if let targetHeight = options.targetHeight {
            args["targetHeight"] = Int(targetHeight.int32Value)
        }
        return args
    }

    private func displayTextArguments(text: String, options: DisplayOptions) -> [String: Any] {
        [
            "text": text,
            "force": options.force,
            "mode": options.mode == .append ? "append" : "replace",
        ]
    }

    private func microphoneArguments(_ options: MicrophoneOptions) -> [String: Any] {
        var args: [String: Any] = [
            "audioEncoding": wireEncoding(options.preferredEncoding),
        ]
        if let sampleRateHz = options.preferredSampleRateHz {
            args["sampleRateHz"] = Int(sampleRateHz.int32Value)
        }
        if let channelCount = options.preferredChannelCount {
            args["channelCount"] = Int(channelCount.int32Value)
        }
        if let vendorMode = wireVendorMode(options.audioHint) {
            args["vendorMode"] = vendorMode
        }
        return args
    }

    private func audioFormat(from response: Any?) -> AudioFormat {
        let map = response as? [String: Any] ?? [:]
        return AudioFormat(
            encoding: audioEncoding(from: map["encoding"]),
            sampleRateHz: int32Value(map["sampleRateHz"]).map { KotlinInt(int: $0) },
            channelCount: int32Value(map["channelCount"]).map { KotlinInt(int: $0) }
        )
    }

    private func wireJpegQuality(_ quality: PhotoQuality) -> Int {
        switch quality {
        case .lowest:
            return 25
        case .low:
            return 50
        case .medium:
            return 70
        case .high:
            return 90
        case .highest:
            return 100
        default:
            return 90
        }
    }

    private func wireEncoding(_ encoding: AudioEncoding) -> String {
        switch encoding {
        case .pcmS8:
            return "pcm_s8"
        case .opus:
            return "opus"
        case .pcmS16Le:
            return "pcm_s16_le"
        default:
            return "pcm_s16_le"
        }
    }

    private func audioEncoding(from value: Any?) -> AudioEncoding {
        switch value as? String {
        case "pcm_s8":
            return .pcmS8
        case "opus":
            return .opus
        default:
            return .pcmS16Le
        }
    }

    private func wireVendorMode(_ hint: AudioCaptureHint) -> String? {
        switch hint {
        case .voiceAssistant:
            return "voiceassistant"
        case .translation:
            return "translation"
        case .camcorder:
            return "camcorder"
        case .default_:
            return nil
        default:
            return nil
        }
    }

    private func runtimeUnavailableError() -> Error {
        let detail = runtimeStartupError.map { "Frame Flutter runtime failed to start: \($0.localizedDescription)" }
            ?? "Frame Flutter runtime is unavailable"
        return GlassesError.Transport(detail: detail, cause: nil).asError()
    }

    private func int32Value(_ value: Any?) -> Int32? {
        if let value = value as? Int32 {
            return value
        }
        if let value = value as? Int {
            return Int32(value)
        }
        if let value = value as? NSNumber {
            return value.int32Value
        }
        return nil
    }

    private func int64Value(_ value: Any?) -> Int64? {
        if let value = value as? Int64 {
            return value
        }
        if let value = value as? Int32 {
            return Int64(value)
        }
        if let value = value as? Int {
            return Int64(value)
        }
        if let value = value as? NSNumber {
            return value.int64Value
        }
        return nil
    }

    private func clearActiveMic() {
        if Thread.isMainThread {
            activeMicSink = nil
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.activeMicSink = nil
            }
        }
    }
}

// Free functions (not methods) so that a released `self` in a completion closure can never
// collapse a real error into a `nil` success via optional chaining.
private func frameOperationError(_ prefix: String, operation: String, error: FlutterError) -> Error {
    if error.code == "not_connected" {
        return GlassesError.NotConnected.shared.asError()
    }
    if error.code.lowercased().contains("timeout") ||
        (error.message?.lowercased().contains("timeout") ?? false) {
        return GlassesError.Timeout(operation: operation).asError()
    }
    if error.code == "unimplemented" || error.code == "not_implemented" {
        return GlassesError.Unsupported(detail: "\(prefix): \(frameDescribe(error))").asError()
    }
    return frameTransportError(prefix, error: error)
}

private func frameTransportError(_ prefix: String, error: FlutterError) -> Error {
    GlassesError.Transport(detail: "\(prefix): \(frameDescribe(error))", cause: nil).asError()
}

private func frameDescribe(_ error: FlutterError) -> String {
    var parts = [error.code]
    if let message = error.message, !message.isEmpty {
        parts.append(message)
    }
    if let details = error.details {
        parts.append(String(describing: details))
    }
    return parts.joined(separator: ": ")
}

private final class FrameCompletionResolver<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var completion: ((T) -> Void)?

    init(_ completion: @escaping (T) -> Void) {
        self.completion = completion
    }

    func resolve(_ value: T) {
        lock.lock()
        let current = completion
        completion = nil
        lock.unlock()
        current?(value)
    }
}

private extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}
