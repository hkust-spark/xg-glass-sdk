import Foundation
import UIKit
import XgGlassKit

final class StubSwiftGlassesClient: BaseGlassesClient {
    private let displaySink: (String) -> Void

    init(displaySink: @escaping (String) -> Void = { _ in }) {
        self.displaySink = displaySink
        super.init(
            initialCapabilities: DeviceCapabilities(
                canCapturePhoto: true,
                canDisplayText: true,
                canRecordAudio: false,
                canPlayTts: false,
                canPlayAudioBytes: false,
                supportsTapEvents: false,
                supportsStreamingTextUpdates: false
            ),
            eventBufferOverflow: .dropOldest
        )
    }

    override var model: GlassesModel {
        .simulator
    }

    override func doConnect(completionHandler: @escaping @Sendable (Error?) -> Void) {
        emitLog(message: "Swift stub: connect (no-op)")
        completionHandler(nil)
    }

    override func mapConnectError(error: KotlinException) -> GlassesError {
        GlassesError.Transport(detail: "Swift stub connect failed: \(error.message ?? "unknown error")", raw: error)
    }

    override func disconnect(completionHandler: @escaping @Sendable (Error?) -> Void) {
        _state.setValue(ConnectionState.Disconnected.shared)
        completionHandler(nil)
    }

    override func capturePhoto(options: CaptureOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        guard let jpegData = Self.makePlaceholderJpeg(options: options) else {
            completionHandler(nil, GlassesError.Transport(detail: "Swift stub could not create JPEG data", raw: nil).asError())
            return
        }

        let width = options.targetWidth?.int32Value ?? 320
        let height = options.targetHeight?.int32Value ?? 180
        let image = CapturedImage(
            jpegBytes: jpegData.toKotlinByteArray(),
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            width: KotlinInt(int: width),
            height: KotlinInt(int: height),
            rotationDegrees: nil,
            sourceModel: model
        )
        completionHandler(image, nil)
    }

    override func display(text: String, options: DisplayOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        displaySink(text)
        emitLog(message: "Swift stub display: \(text)")
        completionHandler(nil, nil)
    }

    override func playAudio(source: AudioSource, options: PlayAudioOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        completionHandler(nil, GlassesError.Unsupported(detail: "Swift stub audio not implemented").asError())
    }

    override func startMicrophone(options: MicrophoneOptions, completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        completionHandler(nil, GlassesError.Unsupported(detail: "Swift stub mic not implemented").asError())
    }

    private static func makePlaceholderJpeg(options: CaptureOptions) -> Data? {
        let width = CGFloat(options.targetWidth?.int32Value ?? 320)
        let height = CGFloat(options.targetHeight?.int32Value ?? 180)
        let size = CGSize(width: max(width, 1), height: max(height, 1))
        let renderer = UIGraphicsImageRenderer(size: size)

        let image = renderer.image { context in
            UIColor(red: 0.10, green: 0.13, blue: 0.18, alpha: 1.0).setFill()
            context.fill(CGRect(origin: .zero, size: size))

            UIColor(red: 0.94, green: 0.32, blue: 0.20, alpha: 1.0).setFill()
            context.fill(CGRect(x: 0, y: 0, width: size.width, height: min(56, size.height)))

            let title = "Swift Stub"
            let subtitle = "\(Int(size.width))x\(Int(size.height)) placeholder"
            title.draw(
                at: CGPoint(x: 20, y: 72),
                withAttributes: [
                    .font: UIFont.boldSystemFont(ofSize: 24),
                    .foregroundColor: UIColor.white
                ]
            )
            subtitle.draw(
                at: CGPoint(x: 20, y: 108),
                withAttributes: [
                    .font: UIFont.systemFont(ofSize: 16),
                    .foregroundColor: UIColor(white: 1.0, alpha: 0.8)
                ]
            )
        }

        return image.jpegData(compressionQuality: options.photoQuality.jpegCompressionQuality)
    }
}

private extension PhotoQuality {
    var jpegCompressionQuality: CGFloat {
        switch self {
        case .lowest:
            return 0.35
        case .low:
            return 0.55
        case .medium:
            return 0.75
        case .high:
            return 0.90
        case .highest:
            return 0.98
        default:
            return 0.90
        }
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
