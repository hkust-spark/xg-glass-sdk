import AVFoundation
import MWDATCore
import MWDATMockDevice
import UIKit
import XCTest
import XgGlassMeta
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

        let mockDevice = try seedRayBanMetaMockDevice(client: client)
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

    private func seedRayBanMetaMockDevice(client: MetaGlassesClient) throws -> (identifier: String, imageURL: URL) {
        let feedURL = try writeMockCameraFeedVideo()
        let imageURL = try writeMockCaptureImage()
        _ = try client.enableMockDevice(cameraFeedURL: feedURL, capturedImageURL: imageURL)
        guard let glasses = MockDeviceKit.shared.pairedDevices.compactMap({ $0 as? MockGlasses }).first else {
            throw TestFailure("Meta mock device was not paired")
        }
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

    private func writeMockCaptureImage() throws -> URL {
        let size = CGSize(width: 640, height: 360)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            UIColor(red: 0.05, green: 0.08, blue: 0.12, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))

            UIColor(red: 0.02, green: 0.54, blue: 0.70, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: size.width, height: 72))

            let title = "Meta Mock Capture"
            let subtitle = "Ray-Ban Meta DAT simulator"
            title.draw(
                at: CGPoint(x: 32, y: 112),
                withAttributes: [
                    .font: UIFont.boldSystemFont(ofSize: 34),
                    .foregroundColor: UIColor.white
                ]
            )
            subtitle.draw(
                at: CGPoint(x: 32, y: 164),
                withAttributes: [
                    .font: UIFont.systemFont(ofSize: 22),
                    .foregroundColor: UIColor(white: 1, alpha: 0.82)
                ]
            )
        }

        guard let data = image.jpegData(compressionQuality: 0.92) else {
            throw TestFailure("Could not create Meta mock capture JPEG")
        }

        let url = FileManager.default.temporaryDirectory.appendingPathComponent("xgglass-meta-mock-capture.jpg")
        try data.write(to: url, options: .atomic)
        return url
    }

    private func writeMockCameraFeedVideo() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("xgglass-meta-mock-feed.mp4")
        try? FileManager.default.removeItem(at: url)

        let width = 320
        let height = 180
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: width,
                AVVideoHeightKey: height
            ]
        )
        input.expectsMediaDataInRealTime = false

        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height
            ]
        )

        guard writer.canAdd(input) else {
            throw TestFailure("Could not add mock camera feed writer input")
        }
        writer.add(input)
        guard writer.startWriting() else {
            throw writer.error ?? TestFailure("Could not start mock camera feed writer")
        }
        writer.startSession(atSourceTime: .zero)

        let frameDuration = CMTime(value: 1, timescale: 12)
        for frameIndex in 0..<36 {
            while !input.isReadyForMoreMediaData {
                Thread.sleep(forTimeInterval: 0.005)
            }
            let pixelBuffer = try mockCameraFeedFrame(width: width, height: height, frameIndex: frameIndex)
            let timestamp = CMTimeMultiply(frameDuration, multiplier: Int32(frameIndex))
            guard adaptor.append(pixelBuffer, withPresentationTime: timestamp) else {
                throw writer.error ?? TestFailure("Could not append mock camera feed frame")
            }
        }

        input.markAsFinished()
        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting {
            semaphore.signal()
        }
        semaphore.wait()

        guard writer.status == .completed else {
            throw writer.error ?? TestFailure("Could not finish mock camera feed writer")
        }
        return url
    }

    private func mockCameraFeedFrame(width: Int, height: Int, frameIndex: Int) throws -> CVPixelBuffer {
        var pixelBuffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32ARGB,
            nil,
            &pixelBuffer
        )
        guard status == kCVReturnSuccess, let pixelBuffer else {
            throw TestFailure("Could not create mock camera feed frame")
        }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }

        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else {
            throw TestFailure("Could not access mock camera feed frame memory")
        }

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: baseAddress,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue
        ) else {
            throw TestFailure("Could not draw mock camera feed frame")
        }

        context.setFillColor(UIColor(red: 0.04, green: 0.06, blue: 0.10, alpha: 1).cgColor)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.setFillColor(UIColor(red: 0.02, green: 0.55, blue: 0.72, alpha: 1).cgColor)
        context.fill(CGRect(x: 0, y: 0, width: width, height: 34))
        context.setFillColor(UIColor(red: 0.93, green: 0.34, blue: 0.20, alpha: 1).cgColor)
        context.fill(CGRect(x: 24 + frameIndex * 4 % 240, y: 74, width: 52, height: 52))

        return pixelBuffer
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
