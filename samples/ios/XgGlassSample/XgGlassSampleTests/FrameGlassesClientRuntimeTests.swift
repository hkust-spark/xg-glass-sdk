import XCTest
@_implementationOnly import Flutter
import XgGlassMetaTesting
@testable import XgGlassSample

final class ActiveClientKindTests: XCTestCase {
    func testEvenClientKindIsExposedInPicker() {
        XCTAssertTrue(ActiveClientKind.allCases.contains(.even))
        XCTAssertEqual(ActiveClientKind.even.title, "Even G1")
    }
}

final class FrameGlassesClientTransitionTests: XCTestCase {
    func testConnectedWhileDisconnectedBecomesConnected() {
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: ConnectionState.Disconnected.shared,
            runtime: .connected,
            hasCompletedConnect: true
        )

        XCTAssertTrue(next is ConnectionState.Connected, "Expected Connected, got \(String(describing: next))")
    }

    func testConnectedBeforePreviousSuccessIsIgnored() {
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: ConnectionState.Disconnected.shared,
            runtime: .connected,
            hasCompletedConnect: false
        )

        XCTAssertNil(next)
    }

    func testConnectedWhileErrorIsIgnored() {
        let current = ConnectionState.Error(error: GlassesError.Transport(detail: "previous failure", cause: nil))
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: current,
            runtime: .connected,
            hasCompletedConnect: true
        )

        XCTAssertNil(next)
    }

    func testConnectedWhileConnectingIsIgnored() {
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: ConnectionState.Connecting.shared,
            runtime: .connected,
            hasCompletedConnect: true,
            connectInFlight: true
        )

        XCTAssertNil(next)
    }

    func testDisconnectedWhileErrorIsIgnored() {
        let current = ConnectionState.Error(error: GlassesError.Transport(detail: "connect failed", cause: nil))
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: current,
            runtime: .disconnected,
            hasCompletedConnect: true
        )

        XCTAssertNil(next)
    }

    func testDisconnectedWhileConnectedBecomesDisconnected() {
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: ConnectionState.Connected.shared,
            runtime: .disconnected,
            hasCompletedConnect: true
        )

        XCTAssertTrue(next is ConnectionState.Disconnected, "Expected Disconnected, got \(String(describing: next))")
    }

    func testErrorBecomesConnectionStateError() {
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: ConnectionState.Connected.shared,
            runtime: .error("runtime failed"),
            hasCompletedConnect: true
        )

        let errorState = next as? ConnectionState.Error
        XCTAssertNotNil(errorState)
        XCTAssertTrue(errorState?.error is GlassesError.Transport)
    }
}

@MainActor
final class FrameGlassesClientRuntimeTests: XCTestCase {
    private var client: FrameGlassesClient?
    private var audioCollectors: [AnyObject] = []

    override func tearDown() {
        if let client {
            let expectation = expectation(description: "disconnect Frame client")
            client.disconnect { _ in
                expectation.fulfill()
            }
            wait(for: [expectation], timeout: 5)
        }
        client = nil
        audioCollectors = []
        super.tearDown()
    }

    func testFrameRuntimeSpontaneousConnectedAfterDropRestoresConnected() throws {
        let runtime = FakeFrameRuntime()
        let client = FrameGlassesClient(runtime: runtime, connectTimeoutSeconds: 1)
        self.client = client

        try connect(client)
        runtime.emitState(.disconnected)
        XCTAssertTrue(waitForState(timeout: 2, client: client) { $0 is ConnectionState.Disconnected } is ConnectionState.Disconnected)

        runtime.emitState(.connected)
        let finalState = waitForState(timeout: 2, client: client) { $0 is ConnectionState.Connected }
        print("FRAME_ADAPTER_SPONTANEOUS_RECONNECT_STATE=\(describe(finalState))")
        XCTAssertTrue(finalState is ConnectionState.Connected)
    }

    func testFrameRuntimeSpontaneousDropDisconnectsAndEndsMicrophone() throws {
        let runtime = FakeFrameRuntime()
        let client = FrameGlassesClient(runtime: runtime, connectTimeoutSeconds: 1)
        self.client = client

        try connect(client)
        let session = try startMicrophone(client)
        let eos = expectEndOfStream(from: session, description: "spontaneous drop emits EOS")

        runtime.emitState(.disconnected)

        wait(for: [eos], timeout: 3)
        let finalState = waitForState(timeout: 2, client: client) { $0 is ConnectionState.Disconnected }
        print("FRAME_ADAPTER_SPONTANEOUS_DROP_STATE=\(describe(finalState))")
        XCTAssertTrue(finalState is ConnectionState.Disconnected)
    }

    func testFrameDisconnectEndsMicrophone() throws {
        let runtime = FakeFrameRuntime()
        let client = FrameGlassesClient(runtime: runtime, connectTimeoutSeconds: 1)
        self.client = client

        try connect(client)
        let session = try startMicrophone(client)
        let counter = AudioEosCounter()
        collectAudio(from: session, counter: counter)
        let disconnected = expectation(description: "disconnect completes")

        client.disconnect { error in
            XCTAssertNil(error)
            disconnected.fulfill()
        }

        wait(for: [disconnected], timeout: 3)
        XCTAssertTrue(counter.waitForCount(1, timeout: 3))
        print("FRAME_ADAPTER_DISCONNECT_EOS_COUNT=\(counter.count)")
        XCTAssertEqual(runtime.disconnectCalls, 1)
        XCTAssertEqual(counter.count, 1)
    }

    func testFrameMicrophoneEndOfStreamIsIdempotentAcrossDropAndDisconnect() throws {
        let runtime = FakeFrameRuntime()
        let client = FrameGlassesClient(runtime: runtime, connectTimeoutSeconds: 1)
        self.client = client

        try connect(client)
        let session = try startMicrophone(client)
        let counter = AudioEosCounter()
        collectAudio(from: session, counter: counter)

        runtime.emitState(.disconnected)
        XCTAssertTrue(counter.waitForCount(1, timeout: 3))

        let disconnected = expectation(description: "disconnect after spontaneous drop completes")
        client.disconnect { error in
            XCTAssertNil(error)
            disconnected.fulfill()
        }
        wait(for: [disconnected], timeout: 3)
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))

        print("FRAME_ADAPTER_DOUBLE_EOS_COUNT=\(counter.count)")
        XCTAssertEqual(counter.count, 1)
    }

    func testFrameAdapterConnectFailsHonestlyOnSimulatorAndMapsDisconnectedOperations() throws {
        let client = FrameGlassesClient(connectTimeoutSeconds: 3)
        self.client = client

        print("FRAME_ADAPTER_INITIAL_STATE=\(describe(client.state.value))")

        let connectExpectation = expectation(description: "Frame connect returns honest simulator failure")
        var connectResult: Any?
        var connectCallbackError: Error?
        client.connect { result, error in
            connectResult = result
            connectCallbackError = error
            connectExpectation.fulfill()
        }

        let observedConnecting = waitForState(timeout: 2, client: client) { state in
            state is ConnectionState.Connecting || state is ConnectionState.Error
        }
        print("FRAME_ADAPTER_CONNECT_OBSERVED_STATE=\(describe(observedConnecting))")

        wait(for: [connectExpectation], timeout: 8)

        let finalState = client.state.value
        let connectStateError = (finalState as? ConnectionState.Error)?.error
        guard connectCallbackError != nil || connectStateError != nil else {
            throw FrameAdapterTestFailure("Frame connect unexpectedly succeeded on the simulator")
        }
        let connectCallbackKotlinError = connectCallbackError.flatMap { ($0 as NSError).kotlinException }
        let connectError = connectStateError ?? connectCallbackKotlinError ?? connectCallbackError
        print("FRAME_ADAPTER_CONNECT_RESULT=\(describe(connectResult))")
        print("FRAME_ADAPTER_CONNECT_ERROR=\(describe(connectError))")
        print("FRAME_ADAPTER_CONNECT_FINAL_STATE=\(describe(finalState))")
        XCTAssertFalse(finalState is ConnectionState.Connected)
        XCTAssertTrue(connectStateError is GlassesError.Transport)

        let captureError = waitForCaptureError(client)
        let captureKotlinError = (captureError as NSError).kotlinException
        print("FRAME_ADAPTER_CAPTURE_ERROR=\(describe(captureKotlinError ?? captureError))")
        XCTAssertTrue(captureKotlinError is GlassesError.NotConnected)

        let displayError = waitForDisplayError(client)
        let displayKotlinError = (displayError as NSError).kotlinException
        print("FRAME_ADAPTER_DISPLAY_ERROR=\(describe(displayKotlinError ?? displayError))")
        XCTAssertTrue(displayKotlinError is GlassesError.NotConnected)
    }

    func testFrameMicrophoneRoutesThroughBridgeAndFailsHonestlyOnSimulator() throws {
        let client = FrameGlassesClient(connectTimeoutSeconds: 3)
        self.client = client

        let expectation = expectation(description: "startMicrophone returns mapped simulator error")
        let options = MicrophoneOptions(
            preferredEncoding: .pcmS16Le,
            preferredSampleRateHz: KotlinInt(int: 16_000),
            preferredChannelCount: KotlinInt(int: 1),
            audioHint: .default_
        )
        var micError: Error?
        client.startMicrophone(options: options) { _, error in
            micError = error
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 5)

        guard let micError else {
            throw FrameAdapterTestFailure("startMicrophone unexpectedly succeeded on the simulator")
        }
        let micKotlinError = (micError as NSError).kotlinException
        print("FRAME_ADAPTER_MIC_ERROR=\(describe(micKotlinError ?? micError))")
        XCTAssertFalse(micKotlinError is GlassesError.Unsupported)
        XCTAssertTrue(
            micKotlinError is GlassesError.NotConnected || micKotlinError is GlassesError.Transport,
            "Expected NotConnected or Transport, got \(describe(micKotlinError ?? micError))"
        )
    }

    private func waitForCaptureError(_ client: FrameGlassesClient) -> Error {
        let expectation = expectation(description: "capturePhoto returns mapped error")
        let options = CaptureOptions(
            photoQuality: .high,
            targetWidth: nil,
            targetHeight: nil,
            timeoutMs: 1_000
        )
        var capturedError: Error?
        client.capturePhoto(options: options) { _, error in
            capturedError = error
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 5)
        return capturedError ?? FrameAdapterTestFailure("capturePhoto unexpectedly succeeded")
    }

    private func waitForDisplayError(_ client: FrameGlassesClient) -> Error {
        let expectation = expectation(description: "display returns mapped error")
        let options = DisplayOptions(mode: .replace, force: true)
        var displayError: Error?
        client.display(text: "Frame simulator proof", options: options) { _, error in
            displayError = error
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 5)
        return displayError ?? FrameAdapterTestFailure("display unexpectedly succeeded")
    }

    private func connect(_ client: FrameGlassesClient) throws {
        let expectation = expectation(description: "Frame fake runtime connects")
        var callbackError: Error?
        client.connect { _, error in
            callbackError = error
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 3)
        if let callbackError {
            throw callbackError
        }
        _ = waitForState(timeout: 2, client: client) { $0 is ConnectionState.Connected }
    }

    private func startMicrophone(_ client: FrameGlassesClient) throws -> MicrophoneSession {
        let expectation = expectation(description: "Frame fake runtime starts microphone")
        var session: MicrophoneSession?
        var callbackError: Error?
        client.startMicrophone(options: MicrophoneOptions(
            preferredEncoding: .pcmS16Le,
            preferredSampleRateHz: KotlinInt(int: 16_000),
            preferredChannelCount: KotlinInt(int: 1),
            audioHint: .default_
        )) { result, error in
            session = result as? MicrophoneSession
            callbackError = error
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 3)
        if let callbackError {
            throw callbackError
        }
        guard let session else {
            throw FrameAdapterTestFailure("startMicrophone returned no session")
        }
        return session
    }

    private func expectEndOfStream(from session: MicrophoneSession, description: String) -> XCTestExpectation {
        let expectation = expectation(description: description)
        let collector = AudioChunkCollector { chunk in
            if chunk.endOfStream {
                expectation.fulfill()
            }
        }
        audioCollectors.append(collector)
        session.audio.collect(collector: collector) { error in
            if let error {
                XCTFail("audio flow collection failed: \(error)")
            }
        }
        return expectation
    }

    private func collectAudio(from session: MicrophoneSession, counter: AudioEosCounter) {
        let collector = AudioChunkCollector { chunk in
            if chunk.endOfStream {
                counter.increment()
            }
        }
        audioCollectors.append(collector)
        session.audio.collect(collector: collector) { error in
            if let error {
                XCTFail("audio flow collection failed: \(error)")
            }
        }
    }

    private func waitForState(
        timeout: TimeInterval,
        client: FrameGlassesClient,
        matching predicate: (Any?) -> Bool
    ) -> Any? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let state = client.state.value
            if predicate(state) {
                return state
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        return client.state.value
    }

    private func describe(_ value: Any?) -> String {
        if let kotlin = value as? KotlinThrowable {
            return String(describing: kotlin)
        }
        return String(describing: value)
    }
}

private struct FrameAdapterTestFailure: LocalizedError {
    let message: String

    init(_ message: String) {
        self.message = message
    }

    var errorDescription: String? {
        message
    }
}

private final class FakeFrameRuntime: FrameRuntime {
    private let lock = NSLock()
    private var eventObservers: [UUID: ([String: Any]) -> Void] = [:]
    private var stateObservers: [UUID: (FrameFlutterState) -> Void] = [:]

    private(set) var latestState: FrameFlutterState = .disconnected
    private(set) var disconnectCalls = 0
    private(set) var stopMicrophoneCalls = 0

    func addEventObserver(_ observer: @escaping ([String: Any]) -> Void) -> UUID {
        let id = UUID()
        lock.lock()
        eventObservers[id] = observer
        lock.unlock()
        return id
    }

    func removeEventObserver(_ id: UUID) {
        lock.lock()
        eventObservers.removeValue(forKey: id)
        lock.unlock()
    }

    func addStateObserver(_ observer: @escaping (FrameFlutterState) -> Void) -> UUID {
        let id = UUID()
        lock.lock()
        stateObservers[id] = observer
        let state = latestState
        lock.unlock()
        observer(state)
        return id
    }

    func removeStateObserver(_ id: UUID) {
        lock.lock()
        stateObservers.removeValue(forKey: id)
        lock.unlock()
    }

    func emitState(_ state: FrameFlutterState) {
        lock.lock()
        latestState = state
        let observers = Array(stateObservers.values)
        lock.unlock()
        for observer in observers {
            observer(state)
        }
    }

    func invoke(method: String, arguments: [String: Any]?, completion: @escaping (Any?, FlutterError?) -> Void) {
        switch method {
        case "startMicrophone":
            completion([
                "encoding": "pcm_s16_le",
                "sampleRateHz": 16_000,
                "channelCount": 1,
            ], nil)
        case "stopMicrophone":
            stopMicrophoneCalls += 1
            completion(true, nil)
        default:
            completion(true, nil)
        }
    }

    func connect(completion: @escaping (Bool, FlutterError?) -> Void) {
        completion(true, nil)
    }

    func disconnect(completion: @escaping (Bool, FlutterError?) -> Void) {
        disconnectCalls += 1
        completion(true, nil)
    }

    func capturePhoto(arguments: [String: Any], completion: @escaping (FlutterStandardTypedData?, FlutterError?) -> Void) {
        completion(FlutterStandardTypedData(bytes: Data([1, 2, 3])), nil)
    }

    func displayText(arguments: [String: Any], completion: @escaping (Bool, FlutterError?) -> Void) {
        completion(true, nil)
    }

    func shutdown() {}
}

private final class AudioChunkCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onChunk: (AudioChunk) -> Void

    init(onChunk: @escaping (AudioChunk) -> Void) {
        self.onChunk = onChunk
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let chunk = value as? AudioChunk {
            onChunk(chunk)
        }
        completionHandler(nil)
    }
}

private final class AudioEosCounter {
    private let lock = NSLock()
    private var value = 0

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func increment() {
        lock.lock()
        value += 1
        lock.unlock()
    }

    func waitForCount(_ expected: Int, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if count >= expected {
                return true
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.02))
        }
        return count >= expected
    }
}
