import AVFoundation
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

public enum MetaBluetoothHfpAudioPolicy {
    public static let bluetoothHfpPortType = AVAudioSession.Port.bluetoothHFP.rawValue

    public struct RouteInput: Equatable {
        public let portType: String
        public let portName: String
        public let uid: String

        public init(portType: String, portName: String, uid: String = "") {
            self.portType = portType
            self.portName = portName
            self.uid = uid
        }
    }

    public enum RouteDecision: Equatable {
        case accepted(portName: String, uid: String)
        case rejected(reason: String)
    }

    public static func evaluateActiveInputRoute(_ inputs: [RouteInput], expectedUID: String? = nil) -> RouteDecision {
        if let hfp = inputs.first(where: { $0.portType == bluetoothHfpPortType }) {
            if let expectedUID, !expectedUID.isEmpty, hfp.uid != expectedUID {
                return .rejected(
                    reason: "Meta Bluetooth HFP microphone route changed from uid \(expectedUID) to \(hfp.uid) (\(hfp.portName))"
                )
            }
            return .accepted(portName: hfp.portName, uid: hfp.uid)
        }

        let routeSummary = inputs.isEmpty
            ? "none"
            : inputs.map { "\($0.portName) (\($0.portType), uid=\($0.uid))" }.joined(separator: ", ")
        return .rejected(
            reason: "Meta Bluetooth HFP microphone is not routed; active input route: \(routeSummary)"
        )
    }

    public static func audioFormat(sampleRate: Double) -> AudioFormat {
        let sampleRateHz = max(1, Int32(sampleRate.rounded()))
        return AudioFormat(
            encoding: .pcmS16Le,
            sampleRateHz: KotlinInt(int: sampleRateHz),
            channelCount: KotlinInt(int: 1)
        )
    }
}

public final class MetaGlassesClient: BaseGlassesClient {
    private var deviceSession: DeviceSession?
    private var camera: MWDATCamera.Camera?
    private var displayCapability: Display?
    private var activeMic: PushMicrophoneSession?
    private var activeMicCapture: MetaBluetoothHfpMicrophoneCapture?
    private var isStartingMicrophone = false

    private var sessionErrorTask: Task<Void, Never>?
    private let photoCapture = MetaPhotoCaptureCoordinator()

    public init() {
        super.init(
            initialCapabilities: DeviceCapabilities(
                canCapturePhoto: true,
                canDisplayText: false,
                canDisplayImages: false,
                // Device-class capability; live Bluetooth HFP routing is validated at startMicrophone.
                canRecordAudio: true,
                canStreamVideo: false,
                supportedVideoFormats: [],
                canPlayTts: false,
                canPlayAudioBytes: false,
                supportsTapEvents: false,
                supportsLongPressEvents: false,
                supportsBatteryEvents: false,
                supportsStreamingTextUpdates: false
            ),
            eventBufferOverflow: .dropOldest
        )
    }

    deinit {
        // deinit can run off the main actor. Field teardown is safe because no
        // accessor can run concurrently once the last strong reference is gone,
        // and MetaBluetoothHfpMicrophoneCapture.stop is internally locked.
        activeMicCapture?.stop(emitEndOfStream: true)
        activeMicCapture = nil
        activeMic = nil
        sessionErrorTask?.cancel()
        camera?.stop()
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
            let started = photoCapture.start {
                try await self.capturePhotoAsync(options: options)
            } completion: { result in
                switch result {
                case .success(let image):
                    completionHandler(image, nil)
                case .failure(let error):
                    completionHandler(nil, self.transportError("Meta photo capture failed", error: error))
                }
            }
            if !started {
                completionHandler(nil, GlassesError.Busy.shared.asError())
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
        Task { @MainActor in
            do {
                let session = try await self.startMicrophoneAsync(options: options)
                completionHandler(session, nil)
            } catch {
                completionHandler(nil, self.microphoneError(error))
            }
        }
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

    @MainActor
    private func startMicrophoneAsync(options: MicrophoneOptions) async throws -> PushMicrophoneSession {
        guard _state.value is ConnectionState.Connected else {
            throw GlassesError.NotConnected.shared.asError()
        }
        guard activeMic == nil, activeMicCapture == nil, !isStartingMicrophone else {
            throw GlassesError.Busy.shared.asError()
        }
        guard options.preferredEncoding == .pcmS16Le else {
            throw GlassesError.Unsupported(detail: "Meta microphone currently supports PCM_S16_LE only.").asError()
        }

        isStartingMicrophone = true
        defer { isStartingMicrophone = false }

        try await ensureMicrophonePermission()

        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.allowBluetoothHFP]
        )
        preferBluetoothHfpInput(audioSession, phase: "before activation")

        let captureEngine = AVAudioEngine()
        let inputFormat = captureEngine.inputNode.inputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            throw GlassesError.Transport(detail: "Meta microphone input format is unavailable", cause: nil).asError()
        }

        let format = MetaBluetoothHfpAudioPolicy.audioFormat(sampleRate: inputFormat.sampleRate)
        let sink = PushMicrophoneSession(
            format: format,
            onStop: { [weak self] in
                DispatchQueue.main.async {
                    self?.stopActiveMicrophone(emitEndOfStream: false, reason: nil)
                }
            },
            extraBufferCapacity: 128
        )
        let capture = try MetaBluetoothHfpMicrophoneCapture(
            sink: sink,
            engine: captureEngine,
            inputFormat: inputFormat,
            owner: self
        )

        try audioSession.setActive(true)
        // iOS may only honor the preferred HFP input after activation; re-apply
        // and log failures so fallback-to-phone routing is diagnosable.
        preferBluetoothHfpInput(audioSession, phase: "after activation")

        let routeInputs = audioSession.currentRoute.inputs.map {
            MetaBluetoothHfpAudioPolicy.RouteInput(
                portType: $0.portType.rawValue,
                portName: $0.portName,
                uid: $0.uid
            )
        }
        switch MetaBluetoothHfpAudioPolicy.evaluateActiveInputRoute(routeInputs) {
        case .accepted(let portName, let uid):
            capture.pinRoute(uid: uid, portName: portName)
            emitLog(message: "Meta: microphone route active over Bluetooth HFP input '\(portName)' (uid=\(uid))")
        case .rejected(let reason):
            deactivateAudioSession(audioSession, context: "route rejection")
            throw GlassesError.Transport(detail: reason, cause: nil).asError()
        }

        do {
            try capture.start()
        } catch {
            capture.stop(emitEndOfStream: false)
            deactivateAudioSession(audioSession, context: "capture start failure")
            throw error
        }

        activeMic = sink
        activeMicCapture = capture
        emitLog(
            message: "Meta: microphone started over Bluetooth HFP as PCM_S16_LE \(Int(inputFormat.sampleRate.rounded())) Hz mono"
        )
        return sink
    }

    private func ensureMicrophonePermission() async throws {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return
        case .denied:
            throw GlassesError.PermissionDenied.shared.asError()
        case .undetermined:
            let granted = await withCheckedContinuation { continuation in
                AVAudioApplication.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
            if !granted {
                throw GlassesError.PermissionDenied.shared.asError()
            }
        @unknown default:
            throw GlassesError.PermissionDenied.shared.asError()
        }
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
        try Task.checkCancellation()
        guard deviceSession === session, session.state == .started else {
            throw GlassesError.NotConnected.shared.asError()
        }

        let config = StreamConfiguration(
            videoCodec: VideoCodec.raw,
            resolution: StreamingResolution.low,
            frameRate: 24
        )

        guard let newCamera = try session.addCamera(config: config) else {
            throw MetaAdapterFailure("Meta DAT did not create a camera")
        }

        camera = newCamera
        let newStream = newCamera.stream
        defer {
            // DAT 0.9 Camera owns the hardware and stopping it also stops its child stream.
            newCamera.stop()
            if camera === newCamera {
                camera = nil
            }
        }

        let jpegData = try await withMetaTimeout(timeoutMs: options.timeoutMs) {
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
        var listeners: [AnyListenerToken] = []
        try await awaitMetaCallback { (resolver: MetaCallbackAwaiter<Void>) in
            listeners.append(stream.statePublisher.listen { state in
                switch state {
                case .streaming:
                    resolver.resume(returning: ())
                case .stopped:
                    resolver.resume(throwing: MetaAdapterFailure("Meta camera stream stopped before it started"))
                case .waitingForDevice, .starting, .stopping, .paused:
                    break
                }
            })

            listeners.append(stream.errorPublisher.listen { error in
                resolver.resume(throwing: MetaAdapterFailure("Meta camera stream error: \(error.localizedDescription)"))
            })

            stream.start()
        } cleanup: {
            for listener in listeners { await listener.cancel() }
            listeners.removeAll()
        }
    }

    @MainActor
    private func capturePhotoData(from stream: MWDATCamera.Stream) async throws -> Data {
        var listeners: [AnyListenerToken] = []
        return try await awaitMetaCallback { (resolver: MetaCallbackAwaiter<Data>) in
            listeners.append(stream.photoDataPublisher.listen { photoData in
                resolver.resume(returning: photoData.data)
            })

            listeners.append(stream.errorPublisher.listen { error in
                resolver.resume(throwing: MetaAdapterFailure("Meta photo capture stream error: \(error.localizedDescription)"))
            })

            let didStartCapture = stream.capturePhoto(format: .jpeg)
            if !didStartCapture {
                resolver.resume(throwing: MetaAdapterFailure("Meta DAT rejected the photo capture request"))
            }
        } cleanup: {
            for listener in listeners { await listener.cancel() }
            listeners.removeAll()
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
                canDisplayImages: current.canDisplayImages,
                canRecordAudio: current.canRecordAudio,
                canStreamVideo: current.canStreamVideo,
                supportedVideoFormats: current.supportedVideoFormats,
                canPlayTts: current.canPlayTts,
                canPlayAudioBytes: current.canPlayAudioBytes,
                supportsTapEvents: current.supportsTapEvents,
                supportsLongPressEvents: current.supportsLongPressEvents,
                supportsBatteryEvents: current.supportsBatteryEvents,
                supportsStreamingTextUpdates: current.supportsStreamingTextUpdates
            )
        }
        return display
    }

    @MainActor
    private func waitForDisplayStart(_ display: Display) async throws {
        var listeners: [AnyListenerToken] = []
        try await awaitMetaCallback { (resolver: MetaCallbackAwaiter<Void>) in
            listeners.append(display.statePublisher.listen { state in
                switch state {
                case .started:
                    resolver.resume(returning: ())
                case .stopped:
                    resolver.resume(throwing: MetaAdapterFailure("Meta display stopped before it started"))
                case .starting, .stopping:
                    break
                }
            })

            display.start()
        } cleanup: {
            for listener in listeners { await listener.cancel() }
            listeners.removeAll()
        }
    }

    @MainActor
    private func cleanupSession() {
        stopActiveMicrophone(emitEndOfStream: true, reason: nil)

        sessionErrorTask?.cancel()
        sessionErrorTask = nil

        photoCapture.cancel()
        camera?.stop()
        camera = nil

        displayCapability?.stop()
        displayCapability = nil

        deviceSession?.stop()
        deviceSession = nil
        resetCapabilities()
    }

    @MainActor
    private func stopActiveMicrophone(emitEndOfStream: Bool, reason: String?) {
        guard let capture = activeMicCapture else {
            activeMic = nil
            return
        }
        activeMicCapture = nil
        activeMic = nil
        capture.stop(emitEndOfStream: emitEndOfStream)
        if let reason {
            emitWarn(message: reason)
        }
    }

    @MainActor
    fileprivate func stopMicrophoneAfterSystemRouteChange(_ reason: String) {
        stopActiveMicrophone(emitEndOfStream: true, reason: reason)
    }

    @MainActor
    fileprivate func emitMicrophoneWarning(_ message: String) {
        emitWarn(message: message)
    }

    @MainActor
    fileprivate func emitMicrophoneLog(_ message: String) {
        emitLog(message: message)
    }

    @MainActor
    private func preferBluetoothHfpInput(_ audioSession: AVAudioSession, phase: String) {
        guard let bluetoothInput = audioSession.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) else {
            emitLog(message: "Meta: no Bluetooth HFP input available \(phase)")
            return
        }
        do {
            try audioSession.setPreferredInput(bluetoothInput)
        } catch {
            emitWarn(message: "Meta: failed to prefer Bluetooth HFP input \(phase): \(error.localizedDescription)")
        }
    }

    @MainActor
    private func deactivateAudioSession(_ audioSession: AVAudioSession, context: String) {
        do {
            try audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
        } catch {
            emitWarn(message: "Meta: failed to deactivate audio session after \(context): \(error.localizedDescription)")
        }
    }

    private func transportError(_ message: String, error: Error) -> Error {
        GlassesError.Transport(detail: "\(message): \(error.localizedDescription)", cause: kotlinCause(error)).asError()
    }

    private func microphoneError(_ error: Error) -> Error {
        if let glassesError = error as? GlassesError {
            return glassesError.asError()
        }
        if let glassesError = (error as NSError).kotlinException as? GlassesError {
            return glassesError.asError()
        }
        return GlassesError.Transport(detail: "Meta microphone failed: \(error.localizedDescription)", cause: kotlinCause(error)).asError()
    }

    private func kotlinCause(_ error: Error) -> KotlinThrowable? {
        (error as NSError).kotlinException as? KotlinThrowable
    }

}

private final class MetaBluetoothHfpMicrophoneCapture {
    private let sink: PushMicrophoneSession
    let inputFormat: AVAudioFormat
    private let targetFormat: AVAudioFormat
    private let engine: AVAudioEngine
    private let converter: AVAudioConverter
    private weak var owner: MetaGlassesClient?
    private var routeObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private var configurationObserver: NSObjectProtocol?
    private let lock = NSLock()
    private var sequence: Int64 = 0
    private var droppedFrames = 0
    private var emptyConversionFrames = 0
    private var stopped = false
    private var didStart = false
    private var pinnedRouteUID: String?
    private var pinnedRouteName: String?

    init(sink: PushMicrophoneSession, engine: AVAudioEngine, inputFormat: AVAudioFormat, owner: MetaGlassesClient) throws {
        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: inputFormat.sampleRate,
            channels: 1,
            interleaved: true
        ) else {
            throw GlassesError.Transport(
                detail: "Meta microphone could not create PCM16 mono format",
                cause: nil
            ).asError()
        }
        guard let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            throw GlassesError.Transport(
                detail: "Meta microphone could not create PCM converter",
                cause: nil
            ).asError()
        }
        self.sink = sink
        self.engine = engine
        self.inputFormat = inputFormat
        self.targetFormat = targetFormat
        self.converter = converter
        self.owner = owner
    }

    deinit {
        stop(emitEndOfStream: false)
    }

    func pinRoute(uid: String, portName: String) {
        lock.lock()
        pinnedRouteUID = uid
        pinnedRouteName = portName
        lock.unlock()
    }

    func start() throws {
        observeAudioSession()
        var didInstallTap = false
        do {
            engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
                self?.handle(buffer)
            }
            didInstallTap = true
            engine.prepare()
            try engine.start()
            lock.lock()
            didStart = true
            lock.unlock()
        } catch {
            if didInstallTap {
                engine.inputNode.removeTap(onBus: 0)
            }
            removeAudioSessionObservers()
            throw error
        }
    }

    func stop(emitEndOfStream: Bool) {
        lock.lock()
        if stopped {
            lock.unlock()
            return
        }
        stopped = true
        let eosSequence = sequence
        let shouldTearDownAudioSession = didStart
        if emitEndOfStream {
            sink.emitEndOfStream(sequence: eosSequence)
        }
        lock.unlock()

        removeAudioSessionObservers()
        if shouldTearDownAudioSession {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
            do {
                try AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
            } catch {
                emitWarningFromAnyThread("Meta: failed to deactivate audio session during microphone stop: \(error.localizedDescription)")
            }
        }
    }

    private func observeAudioSession() {
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] _ in
            self?.handleRouteChange()
        }
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            self?.handleInterruption(notification)
        }
        configurationObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: .main
        ) { [weak self] _ in
            self?.handleEngineConfigurationChange()
        }
    }

    private func removeAudioSessionObservers() {
        if let routeObserver {
            NotificationCenter.default.removeObserver(routeObserver)
        }
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
        if let configurationObserver {
            NotificationCenter.default.removeObserver(configurationObserver)
        }
        routeObserver = nil
        interruptionObserver = nil
        configurationObserver = nil
    }

    private func handleRouteChange() {
        guard !isStopped else { return }
        let expectedUID = pinnedRouteUID
        let inputs = AVAudioSession.sharedInstance().currentRoute.inputs.map {
            MetaBluetoothHfpAudioPolicy.RouteInput(
                portType: $0.portType.rawValue,
                portName: $0.portName,
                uid: $0.uid
            )
        }
        switch MetaBluetoothHfpAudioPolicy.evaluateActiveInputRoute(inputs, expectedUID: expectedUID) {
        case .accepted(let portName, let uid):
            emitLogFromMainQueue("Meta: microphone route remains Bluetooth HFP input '\(portName)' (uid=\(uid))")
        case .rejected(let reason):
            stopFromMainQueue("Meta microphone stopped: \(reason)")
        }
    }

    private func handleInterruption(_ notification: Notification) {
        guard !isStopped else { return }
        let rawType = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
        guard let rawType else {
            emitLogFromMainQueue("Meta: ignoring audio session interruption with missing type")
            return
        }
        if rawType == AVAudioSession.InterruptionType.began.rawValue {
            stopFromMainQueue("Meta microphone stopped: audio session interrupted")
        } else if rawType == AVAudioSession.InterruptionType.ended.rawValue {
            emitLogFromMainQueue("Meta: audio session interruption ended; microphone capture remains \(engine.isRunning ? "running" : "stopped")")
        } else {
            emitLogFromMainQueue("Meta: ignoring audio session interruption type \(rawType)")
        }
    }

    private func handleEngineConfigurationChange() {
        guard !isStopped else { return }
        do {
            engine.prepare()
            if engine.isRunning {
                emitLogFromMainQueue("Meta: audio engine configuration changed; microphone capture remains running")
            } else {
                try engine.start()
                emitLogFromMainQueue("Meta: audio engine restarted after configuration change")
            }
        } catch {
            stopFromMainQueue("Meta microphone stopped: audio engine configuration recovery failed: \(error.localizedDescription)")
        }
    }

    private func handle(_ buffer: AVAudioPCMBuffer) {
        guard !isStopped else { return }
        do {
            guard let data = try convertToPcm16Mono(buffer), !data.isEmpty else {
                logEmptyConversionFrame()
                return
            }
            guard let emitted = emitConvertedData(data) else { return }
            if !emitted {
                let count = nextDroppedFrameCount()
                if shouldRateLimitLog(count) {
                    Task { @MainActor [weak self] in
                        self?.owner?.emitMicrophoneWarning(
                            "Meta: dropped mic frame due to audio backpressure; count=\(count)"
                        )
                    }
                }
            }
        } catch {
            Task { @MainActor [weak self] in
                self?.owner?.stopMicrophoneAfterSystemRouteChange(
                    "Meta microphone stopped: PCM conversion failed: \(error.localizedDescription)"
                )
            }
        }
    }

    private func stopFromMainQueue(_ reason: String) {
        MainActor.assumeIsolated {
            owner?.stopMicrophoneAfterSystemRouteChange(reason)
        }
    }

    private func emitLogFromMainQueue(_ message: String) {
        MainActor.assumeIsolated {
            owner?.emitMicrophoneLog(message)
        }
    }

    private func emitWarningFromAnyThread(_ message: String) {
        if Thread.isMainThread {
            MainActor.assumeIsolated {
                owner?.emitMicrophoneWarning(message)
            }
        } else {
            Task { @MainActor [weak owner] in
                owner?.emitMicrophoneWarning(message)
            }
        }
    }

    private func logEmptyConversionFrame() {
        let count = nextEmptyConversionFrameCount()
        guard shouldRateLimitLog(count) else { return }
        Task { @MainActor [weak self] in
            self?.owner?.emitMicrophoneWarning(
                "Meta: PCM converter produced empty mic frame; count=\(count)"
            )
        }
    }

    private func convertToPcm16Mono(_ buffer: AVAudioPCMBuffer) throws -> Data? {
        let capacity = max(
            AVAudioFrameCount(1),
            AVAudioFrameCount(Double(buffer.frameLength) * targetFormat.sampleRate / buffer.format.sampleRate) + 16
        )
        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else {
            throw MetaAdapterFailure("Meta microphone could not allocate PCM output buffer")
        }

        var didProvideInput = false
        var conversionError: NSError?
        let status = converter.convert(to: outputBuffer, error: &conversionError) { _, outStatus in
            if didProvideInput {
                outStatus.pointee = .noDataNow
                return nil
            }
            didProvideInput = true
            outStatus.pointee = .haveData
            return buffer
        }

        if status == .error {
            throw conversionError ?? MetaAdapterFailure("Meta microphone PCM conversion failed")
        }
        guard outputBuffer.frameLength > 0, let channelData = outputBuffer.int16ChannelData else {
            return nil
        }

        let byteCount = Int(outputBuffer.frameLength) * MemoryLayout<Int16>.size
        return Data(bytes: channelData[0], count: byteCount)
    }

    private var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    private func emitConvertedData(_ data: Data) -> Bool? {
        lock.lock()
        defer { lock.unlock() }
        if stopped {
            return nil
        }
        let currentSequence = sequence
        sequence += 1
        return sink.emit(bytes: data.toKotlinByteArray(), sequence: currentSequence)
    }

    private func nextDroppedFrameCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        droppedFrames += 1
        return droppedFrames
    }

    private func nextEmptyConversionFrameCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        emptyConversionFrames += 1
        return emptyConversionFrames
    }

    private func shouldRateLimitLog(_ count: Int) -> Bool {
        count == 1 || count % 100 == 0
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

private extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}
