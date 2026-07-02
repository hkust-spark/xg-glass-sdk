import SwiftUI
import UIKit
import XgGlassKit

struct ContentView: View {
    @StateObject private var model = SampleModel()

    var body: some View {
        NavigationView {
            List {
                Section("Device") {
                    InfoRow(title: "Model", value: model.modelName)
                    InfoRow(title: "Can capture", value: model.canCapturePhoto ? "Yes" : "No")
                    InfoRow(title: "Can display", value: model.canDisplayText ? "Yes" : "No")
                    InfoRow(title: "Status", value: model.status)
                }

                Section("Actions") {
                    Button("Connect") {
                        model.connect()
                    }
                    Button("Display Text") {
                        model.display()
                    }
                    Button("Capture") {
                        model.capture()
                    }
                }

                Section("Display Sink") {
                    Text(model.displayText.isEmpty ? "No display text yet" : model.displayText)
                }

                if let image = model.image {
                    Section("Captured Placeholder") {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 260)
                    }
                }
            }
            .navigationTitle("XgGlassKit")
        }
    }
}

private struct InfoRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(value)
                .foregroundColor(.secondary)
        }
    }
}

@MainActor
final class SampleModel: ObservableObject {
    @Published var status = "Idle"
    @Published var displayText = ""
    @Published var image: UIImage?

    private let client: SimulatorIosGlassesClient

    init() {
        var displayUpdate: ((String) -> Void)?
        self.client = SimulatorIosGlassesClient(displaySink: { text in
            displayUpdate?(text)
        })
        displayUpdate = { [weak self] text in
            Task { @MainActor in
                self?.displayText = text
            }
        }
    }

    var modelName: String {
        client.model.name
    }

    var canCapturePhoto: Bool {
        client.capabilities.canCapturePhoto
    }

    var canDisplayText: Bool {
        client.capabilities.canDisplayText
    }

    func connect() {
        status = "Connecting"
        client.connect { [weak self] _, error in
            Task { @MainActor in
                self?.status = error.map { "Connect failed: \($0.localizedDescription)" } ?? "Connected"
            }
        }
    }

    func display() {
        let options = DisplayOptions(mode: .replace, force: true)
        client.display(text: "Hello from SwiftUI", options: options) { [weak self] _, error in
            Task { @MainActor in
                if let error {
                    self?.status = "Display failed: \(error.localizedDescription)"
                } else {
                    self?.status = "Displayed text"
                }
            }
        }
    }

    func capture() {
        status = "Capturing"
        let options = CaptureOptions(
            photoQuality: .high,
            targetWidth: nil,
            targetHeight: nil,
            timeoutMs: 30_000
        )
        client.capturePhoto(options: options) { [weak self] result, error in
            Task { @MainActor in
                if let error {
                    self?.status = "Capture failed: \(error.localizedDescription)"
                    return
                }
                guard let captured = result as? CapturedImage else {
                    self?.status = "Capture returned an unexpected result"
                    return
                }
                self?.image = UIImage(data: captured.jpegBytes.toData())
                self?.status = "Captured \(captured.width?.int32Value ?? 0)x\(captured.height?.int32Value ?? 0)"
            }
        }
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
