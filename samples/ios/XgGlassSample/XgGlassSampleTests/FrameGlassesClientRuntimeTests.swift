import XCTest
import XgGlassMetaTesting
@testable import XgGlassSample

final class FrameGlassesClientTransitionTests: XCTestCase {
    func testConnectedWhileDisconnectedBecomesConnected() {
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: ConnectionState.Disconnected.shared,
            runtime: .connected
        )

        XCTAssertTrue(next is ConnectionState.Connected, "Expected Connected, got \(String(describing: next))")
    }

    func testConnectedWhileErrorBecomesConnected() {
        let current = ConnectionState.Error(error: GlassesError.Transport(detail: "previous failure", cause: nil))
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: current,
            runtime: .connected
        )

        XCTAssertTrue(next is ConnectionState.Connected, "Expected Connected, got \(String(describing: next))")
    }

    func testConnectedWhileConnectingIsIgnored() {
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: ConnectionState.Connecting.shared,
            runtime: .connected
        )

        XCTAssertNil(next)
    }

    func testDisconnectedWhileErrorIsIgnored() {
        let current = ConnectionState.Error(error: GlassesError.Transport(detail: "connect failed", cause: nil))
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: current,
            runtime: .disconnected
        )

        XCTAssertNil(next)
    }

    func testDisconnectedWhileConnectedBecomesDisconnected() {
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: ConnectionState.Connected.shared,
            runtime: .disconnected
        )

        XCTAssertTrue(next is ConnectionState.Disconnected, "Expected Disconnected, got \(String(describing: next))")
    }

    func testErrorBecomesConnectionStateError() {
        let next = FrameGlassesClient.resolveRuntimeStateTransition(
            current: ConnectionState.Connected.shared,
            runtime: .error("runtime failed")
        )

        let errorState = next as? ConnectionState.Error
        XCTAssertNotNil(errorState)
        XCTAssertTrue(errorState?.error is GlassesError.Transport)
    }
}

@MainActor
final class FrameGlassesClientRuntimeTests: XCTestCase {
    private var client: FrameGlassesClient?

    override func tearDown() {
        if let client {
            let expectation = expectation(description: "disconnect Frame client")
            client.disconnect { _ in
                expectation.fulfill()
            }
            wait(for: [expectation], timeout: 5)
        }
        client = nil
        super.tearDown()
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
