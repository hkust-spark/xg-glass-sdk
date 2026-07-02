@_implementationOnly import Flutter
import Foundation
import XgGlassKit

final class FrameGlassesClient: BaseGlassesClient {
    private let runtime: FrameFlutterRuntime?
    private let runtimeStartupError: Error?
    private let ownsRuntime: Bool
    private let connectTimeoutSeconds: TimeInterval
    private var eventObserverToken: UUID?
    private var stateObserverToken: UUID?

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

    override var model: XgGlassKit.GlassesModel {
        XgGlassKit.GlassesModel.frame
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
            resolver.resolve(GlassesError.Transport(detail: message, raw: nil).asError())
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
        return GlassesError.Transport(detail: "Frame connect failed: \(error.message ?? "unknown error")", raw: error)
    }

    override func disconnect(completionHandler: @escaping @Sendable (Error?) -> Void) {
        guard let runtime else {
            _state.setValue(ConnectionState.Disconnected.shared)
            completionHandler(nil)
            return
        }

        runtime.disconnect { [weak self] _, error in
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
                completionHandler(nil, GlassesError.Transport(detail: "Frame client was released during capture", raw: nil).asError())
                return
            }
            if let error {
                completionHandler(nil, frameOperationError("Frame photo capture failed", operation: "capturePhoto", error: error))
                return
            }
            guard let bytes else {
                completionHandler(nil, GlassesError.Transport(detail: "Frame photo capture returned no bytes", raw: nil).asError())
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
        // TODO(frame-ios-mic): stream Flutter onEvent type=="audio" into a Kotlin SharedFlow<AudioChunk>.
        completionHandler(
            nil,
            GlassesError.Unsupported(detail: "Frame microphone (iOS) not implemented yet").asError()
        )
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
                raw: nil
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
        case "audio", "state":
            // "state" is handled by the state observer; "audio" is deferred with the mic.
            break
        default:
            emitWarn(message: "Frame: ignoring unhandled event type \(payload["type"] as? String ?? "unknown")")
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

    private func runtimeUnavailableError() -> Error {
        let detail = runtimeStartupError.map { "Frame Flutter runtime failed to start: \($0.localizedDescription)" }
            ?? "Frame Flutter runtime is unavailable"
        return GlassesError.Transport(detail: detail, raw: nil).asError()
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
    GlassesError.Transport(detail: "\(prefix): \(frameDescribe(error))", raw: nil).asError()
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
