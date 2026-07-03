import Foundation
import MWDATCamera
import MWDATCore
import MWDATDisplay
import XgGlass

public enum MetaDATRuntime {
    private static var isConfigured = false

    public static func configureIfNeeded() throws {
        guard !isConfigured else { return }
        try Wearables.configure()
        isConfigured = true
    }

    public static func handleOpenURL(_ url: URL) async throws {
        try configureIfNeeded()
        _ = try await Wearables.shared.handleUrl(url)
    }
}

public final class MetaGlassesClient: BaseGlassesClient {
    private var deviceSession: DeviceSession?
    private var stream: MWDATCamera.Stream?
    private var displayCapability: Display?

    private var sessionErrorTask: Task<Void, Never>?
    private var streamStateListenerToken: AnyListenerToken?
    private var streamErrorListenerToken: AnyListenerToken?
    private var photoDataListenerToken: AnyListenerToken?
    private var displayStateListenerToken: AnyListenerToken?

    public init() {
        super.init(
            initialCapabilities: DeviceCapabilities(
                canCapturePhoto: true,
                canDisplayText: false,
                canRecordAudio: false,
                canPlayTts: false,
                canPlayAudioBytes: false,
                supportsTapEvents: false,
                supportsStreamingTextUpdates: false
            ),
            eventBufferOverflow: .dropOldest
        )
    }

    deinit {
        sessionErrorTask?.cancel()
        stream?.stop()
        displayCapability?.stop()
        deviceSession?.stop()
    }

    public override var model: XgGlassKit.GlassesModel {
        XgGlassKit.GlassesModel.meta
    }

    public override func doConnect(completionHandler: @escaping @Sendable (Error?) -> Void) {
        Task { @MainActor in
            do {
                try await self.connectAsync()
                completionHandler(nil)
            } catch {
                completionHandler(self.transportError("Meta connect failed", error: error))
            }
        }
    }

    public override func mapConnectError(error: KotlinException) -> GlassesError {
        if let glassesError = error as? GlassesError {
            return glassesError
        }
        return GlassesError.Transport(detail: "Meta connect failed: \(error.message ?? "unknown error")", cause: error)
    }

    public override func disconnect(completionHandler: @escaping @Sendable (Error?) -> Void) {
        Task { @MainActor in
            self.cleanupSession()
            self._state.setValue(ConnectionState.Disconnected.shared)
            completionHandler(nil)
        }
    }

    public override func capturePhoto(options: CaptureOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        Task { @MainActor in
            do {
                let image = try await self.capturePhotoAsync(options: options)
                completionHandler(image, nil)
            } catch {
                completionHandler(nil, self.transportError("Meta photo capture failed", error: error))
            }
        }
    }

    public override func display(text: String, options: DisplayOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        Task { @MainActor in
            do {
                try await self.displayAsync(text: text)
                completionHandler(nil, nil)
            } catch {
                completionHandler(
                    nil,
                    GlassesError.Unsupported(
                        detail: "Meta display requires a display-capable Meta wearable: \(error.localizedDescription)"
                    ).asError()
                )
            }
        }
    }

    public override func playAudio(source: AudioSource, options: PlayAudioOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        completionHandler(nil, GlassesError.Unsupported(detail: "Meta iOS adapter does not implement audio playback").asError())
    }

    public override func startMicrophone(options: MicrophoneOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        // TODO(meta-ios-mic): the Meta DAT SDK exposes no audio API; Meta mic is Bluetooth-HFP capture via AVFoundation (like the Android HFP path) and needs the paired device -- unvalidatable on the Simulator.
        completionHandler(nil, GlassesError.Unsupported(detail: "Meta microphone on iOS requires Bluetooth-HFP audio capture (the Ray-Ban Meta acts as a Bluetooth headset); not yet implemented").asError())
    }

    public func startRegistration(completionHandler: @escaping @Sendable (Error?) -> Void) {
        Task { @MainActor in
            do {
                try MetaDATRuntime.configureIfNeeded()
                try await Wearables.shared.startRegistration()
                completionHandler(nil)
            } catch {
                completionHandler(self.transportError("Meta registration failed", error: error))
            }
        }
    }

    @MainActor
    private func connectAsync() async throws {
        try MetaDATRuntime.configureIfNeeded()

        if let session = deviceSession, session.state == .started {
            emitLog(message: "Meta DAT session already connected")
            return
        }

        cleanupSession()

        let wearables = Wearables.shared
        let selector: any DeviceSelector
        if let deviceIdentifier = wearables.devices.first {
            selector = SpecificDeviceSelector(device: deviceIdentifier)
        } else {
            selector = AutoDeviceSelector(wearables: wearables)
        }
        let session = try wearables.createSession(deviceSelector: selector)
        deviceSession = session

        let stateStream = session.stateStream()
        let errorStream = session.errorStream()
        try session.start()

        if session.state != .started {
            try await waitForSessionStart(stateStream: stateStream, errorStream: errorStream)
        }

        sessionErrorTask = Task { [weak self] in
            for await error in session.errorStream() {
                guard let self else { return }
                await MainActor.run {
                    self.emitWarn(message: "Meta DAT session error: \(error.localizedDescription)")
                }
            }
        }

        emitLog(message: "Meta DAT session connected")
    }

    private func waitForSessionStart(
        stateStream: AsyncStream<DeviceSessionState>,
        errorStream: AsyncStream<DeviceSessionError>
    ) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                for await state in stateStream {
                    if state == .started {
                        return
                    }
                    if state == .stopped {
                        throw MetaAdapterFailure("Meta device session stopped before it started")
                    }
                }
                throw MetaAdapterFailure("Meta device session ended before it started")
            }

            group.addTask {
                for await error in errorStream {
                    throw MetaAdapterFailure("Meta device session error: \(error.localizedDescription)")
                }
                try await waitUntilCancelled()
            }

            guard try await group.next() != nil else {
                throw MetaAdapterFailure("Meta device session did not start")
            }
            group.cancelAll()
        }
    }

    @MainActor
    private func capturePhotoAsync(options: CaptureOptions) async throws -> CapturedImage {
        guard let session = deviceSession, session.state == .started else {
            throw MetaAdapterFailure("Meta client is not connected")
        }

        try await ensureCameraPermission()

        let config = StreamConfiguration(
            videoCodec: VideoCodec.raw,
            resolution: StreamingResolution.low,
            frameRate: 24
        )

        guard let newStream = try session.addStream(config: config) else {
            throw MetaAdapterFailure("Meta DAT did not create a camera stream")
        }

        stream = newStream
        defer {
            newStream.stop()
            stream = nil
            clearStreamListeners()
        }

        let jpegData = try await withTimeout(timeoutMs: options.timeoutMs) {
            try await self.waitForStreamToStart(newStream)
            return try await self.capturePhotoData(from: newStream)
        }

        return CapturedImage(
            jpegBytes: jpegData.toKotlinByteArray(),
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            width: nil,
            height: nil,
            rotationDegrees: nil,
            sourceModel: model
        )
    }

    private func ensureCameraPermission() async throws {
        let permission = Permission.camera
        var status = try await Wearables.shared.checkPermissionStatus(permission)
        if status != .granted {
            status = try await Wearables.shared.requestPermission(permission)
        }
        guard status == .granted else {
            throw MetaAdapterFailure("Meta camera permission was not granted")
        }
    }

    @MainActor
    private func waitForStreamToStart(_ stream: MWDATCamera.Stream) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let resolver = ContinuationResolver(continuation)

            streamStateListenerToken = stream.statePublisher.listen { state in
                switch state {
                case .streaming:
                    resolver.resume(returning: ())
                case .stopped:
                    resolver.resume(throwing: MetaAdapterFailure("Meta camera stream stopped before it started"))
                case .waitingForDevice, .starting, .stopping, .paused:
                    break
                }
            }

            streamErrorListenerToken = stream.errorPublisher.listen { error in
                resolver.resume(throwing: MetaAdapterFailure("Meta camera stream error: \(error.localizedDescription)"))
            }

            stream.start()
        }
    }

    @MainActor
    private func capturePhotoData(from stream: MWDATCamera.Stream) async throws -> Data {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            let resolver = ContinuationResolver(continuation)

            photoDataListenerToken = stream.photoDataPublisher.listen { photoData in
                resolver.resume(returning: photoData.data)
            }

            streamErrorListenerToken = stream.errorPublisher.listen { error in
                resolver.resume(throwing: MetaAdapterFailure("Meta photo capture stream error: \(error.localizedDescription)"))
            }

            let didStartCapture = stream.capturePhoto(format: .jpeg)
            if !didStartCapture {
                resolver.resume(throwing: MetaAdapterFailure("Meta DAT rejected the photo capture request"))
            }
        }
    }

    @MainActor
    private func displayAsync(text: String) async throws {
        guard let session = deviceSession, session.state == .started else {
            throw MetaAdapterFailure("Meta client is not connected")
        }
        if let device = Wearables.shared.deviceForIdentifier(session.deviceId), !device.supportsDisplay() {
            throw MetaAdapterFailure("Meta wearable does not support display")
        }

        let display = try await startDisplayIfNeeded(on: session)
        try await display.send(
            FlexBox(direction: .column, spacing: 8) {
                MWDATDisplay.Text("XgGlassKit", style: .meta, color: .secondary)
                MWDATDisplay.Text(text, style: .body)
            }
            .padding(24)
            .background(.card)
        )
    }

    @MainActor
    private func startDisplayIfNeeded(on session: DeviceSession) async throws -> Display {
        if let displayCapability {
            return displayCapability
        }

        let display = try session.addDisplay()
        displayCapability = display
        try await waitForDisplayStart(display)
        updateCapabilities { current in
            current.doCopy(
                canCapturePhoto: current.canCapturePhoto,
                canDisplayText: true,
                canRecordAudio: current.canRecordAudio,
                canPlayTts: current.canPlayTts,
                canPlayAudioBytes: current.canPlayAudioBytes,
                supportsTapEvents: current.supportsTapEvents,
                supportsStreamingTextUpdates: current.supportsStreamingTextUpdates
            )
        }
        return display
    }

    @MainActor
    private func waitForDisplayStart(_ display: Display) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let resolver = ContinuationResolver(continuation)

            displayStateListenerToken = display.statePublisher.listen { state in
                switch state {
                case .started:
                    resolver.resume(returning: ())
                case .stopped:
                    resolver.resume(throwing: MetaAdapterFailure("Meta display stopped before it started"))
                case .starting, .stopping:
                    break
                }
            }

            display.start()
        }
    }

    @MainActor
    private func cleanupSession() {
        sessionErrorTask?.cancel()
        sessionErrorTask = nil

        stream?.stop()
        stream = nil
        clearStreamListeners()

        displayCapability?.stop()
        displayCapability = nil
        displayStateListenerToken = nil

        deviceSession?.stop()
        deviceSession = nil
        resetCapabilities()
    }

    private func clearStreamListeners() {
        streamStateListenerToken = nil
        streamErrorListenerToken = nil
        photoDataListenerToken = nil
    }

    private func withTimeout<T>(
        timeoutMs: Int64,
        operation: @escaping () async throws -> T
    ) async throws -> T {
        let safeTimeoutMs = max(timeoutMs, 1)
        let timeoutNanoseconds = UInt64(safeTimeoutMs) * 1_000_000

        return try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask {
                try await operation()
            }
            group.addTask {
                try await Task.sleep(nanoseconds: timeoutNanoseconds)
                throw MetaAdapterFailure("Meta operation timed out after \(safeTimeoutMs) ms")
            }

            guard let value = try await group.next() else {
                throw MetaAdapterFailure("Meta operation did not complete")
            }
            group.cancelAll()
            return value
        }
    }

    private func transportError(_ message: String, error: Error) -> Error {
        GlassesError.Transport(detail: "\(message): \(error.localizedDescription)", cause: nil).asError()
    }

}

private func waitUntilCancelled() async throws {
    while !Task.isCancelled {
        try await Task.sleep(nanoseconds: 60_000_000_000)
    }
}

private struct MetaAdapterFailure: LocalizedError {
    let message: String

    init(_ message: String) {
        self.message = message
    }

    var errorDescription: String? {
        message
    }
}

private final class ContinuationResolver<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?

    init(_ continuation: CheckedContinuation<T, Error>) {
        self.continuation = continuation
    }

    func resume(returning value: T) {
        take()?.resume(returning: value)
    }

    func resume(throwing error: Error) {
        take()?.resume(throwing: error)
    }

    private func take() -> CheckedContinuation<T, Error>? {
        lock.lock()
        defer { lock.unlock() }
        let current = continuation
        continuation = nil
        return current
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
