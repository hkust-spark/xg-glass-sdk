import MWDATCore
import MWDATMockDevice
import UIKit
import XCTest
import XgGlassKit
@testable import XgGlassSample

@MainActor
final class MetaMockRuntimeTests: XCTestCase {
    private var client: MetaGlassesClient?

    override func tearDown() {
        if let client {
            let expectation = expectation(description: "disconnect Meta client")
            client.disconnect { _ in
                expectation.fulfill()
            }
            wait(for: [expectation], timeout: 5)
        }
        MockDeviceKit.shared.disable()
        client = nil
        super.tearDown()
    }

    func testMetaMockConnectAndCapturePhoto() async throws {
        let client = MetaGlassesClient()
        self.client = client

        let mockDevice = try seedRayBanMetaMockDevice()
        try MetaDATRuntime.configureIfNeeded()
        print("T30_META_MOCK_DEVICE=\(mockDevice.identifier)")
        try await Task.sleep(nanoseconds: 1_000_000_000)
        print("T30_META_WEARABLES_DEVICES=\(Wearables.shared.devices)")

        try await withTimeout(seconds: 30) {
            try await self.connect(client)
        }

        let state = client.state.value
        print("T30_META_CONNECT_STATE=\(String(describing: state))")
        XCTAssertTrue(state is ConnectionState.Connected, "Expected connected state, got \(String(describing: state))")

        let image = try await withTimeout(seconds: 30) {
            try await self.capturePhoto(client)
        }
        let jpegData = image.jpegBytes.toData()
        print("T30_META_CAPTURE_BYTES=\(jpegData.count)")
        XCTAssertGreaterThan(jpegData.count, 0)
        XCTAssertNotNil(UIImage(data: jpegData), "Captured bytes should decode as JPEG")

        let displayError = try await withTimeout(seconds: 10) {
            await self.displayText(client)
        }
        if let displayError {
            let kotlinException = (displayError as NSError).kotlinException
            print("T30_META_DISPLAY_ERROR=\(String(describing: kotlinException))")
            XCTAssertTrue(
                kotlinException is GlassesError.Unsupported,
                "Expected display to be unsupported on Ray-Ban Meta mock, got \(String(describing: kotlinException))"
            )
        } else {
            print("T30_META_DISPLAY_RESULT=success")
        }
    }

    private func seedRayBanMetaMockDevice() throws -> (identifier: String, imageURL: URL) {
        let mockDeviceKit = MockDeviceKit.shared
        mockDeviceKit.enable()

        let glasses = try mockDeviceKit.pairedDevices.compactMap { $0 as? MockGlasses }.first
            ?? mockDeviceKit.pairGlasses(model: .rayBanMeta)
        glasses.powerOn()
        glasses.unfold()
        glasses.don()

        let feedURL = try MetaGlassesClient.writeMockCameraFeedVideo()
        glasses.services.camera.setCameraFeed(fileURL: feedURL)
        let imageURL = try MetaGlassesClient.writeMockCaptureImage()
        glasses.services.camera.setCapturedImage(fileURL: imageURL)
        return (glasses.deviceIdentifier, imageURL)
    }

    private func connect(_ client: MetaGlassesClient) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let resolver = TestContinuationResolver(continuation)
            client.connect { _, error in
                if let error {
                    resolver.resume(throwing: error)
                } else {
                    resolver.resume(returning: ())
                }
            }
        }
    }

    private func capturePhoto(_ client: MetaGlassesClient) async throws -> CapturedImage {
        let options = CaptureOptions(
            photoQuality: .high,
            targetWidth: nil,
            targetHeight: nil,
            timeoutMs: 30_000
        )

        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<CapturedImage, Error>) in
            let resolver = TestContinuationResolver(continuation)
            client.capturePhoto(options: options) { result, error in
                if let error {
                    resolver.resume(throwing: error)
                    return
                }

                guard let image = result as? CapturedImage else {
                    resolver.resume(throwing: TestFailure("capturePhoto returned \(String(describing: result))"))
                    return
                }

                resolver.resume(returning: image)
            }
        }
    }

    private func displayText(_ client: MetaGlassesClient) async -> Error? {
        let options = DisplayOptions(mode: .replace, force: true)
        return await withCheckedContinuation { (continuation: CheckedContinuation<Error?, Never>) in
            let resolver = TestOptionalContinuationResolver(continuation)
            client.display(text: "hello from XCTest", options: options) { _, error in
                resolver.resume(returning: error)
            }
        }
    }

    private func withTimeout<T>(
        seconds: UInt64,
        operation: @escaping () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask {
                try await operation()
            }
            group.addTask {
                try await Task.sleep(nanoseconds: seconds * 1_000_000_000)
                throw TestFailure("Timed out after \(seconds) seconds")
            }

            guard let value = try await group.next() else {
                throw TestFailure("Timed operation did not complete")
            }
            group.cancelAll()
            return value
        }
    }

}

private struct TestFailure: LocalizedError {
    let message: String

    init(_ message: String) {
        self.message = message
    }

    var errorDescription: String? {
        message
    }
}

private extension KotlinByteArray {
    func toData() -> Data {
        var bytes = [UInt8]()
        bytes.reserveCapacity(Int(size))
        for index in 0..<Int(size) {
            bytes.append(UInt8(bitPattern: get(index: Int32(index))))
        }
        return Data(bytes)
    }
}

private final class TestContinuationResolver<T>: @unchecked Sendable {
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

private final class TestOptionalContinuationResolver<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Never>?

    init(_ continuation: CheckedContinuation<T, Never>) {
        self.continuation = continuation
    }

    func resume(returning value: T) {
        lock.lock()
        let current = continuation
        continuation = nil
        lock.unlock()
        current?.resume(returning: value)
    }
}
