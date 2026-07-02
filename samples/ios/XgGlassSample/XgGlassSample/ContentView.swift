import SwiftUI
import UIKit
import XgGlassKit

enum ActiveClientKind: String, CaseIterable, Identifiable {
    case kotlinSimulator
    case swiftStub
    case frame
    case meta

    var id: String { rawValue }

    var title: String {
        switch self {
        case .kotlinSimulator:
            return "Kotlin Sim"
        case .swiftStub:
            return "Swift Stub"
        case .frame:
            return "Frame (Flutter)"
        case .meta:
            return "Meta"
        }
    }
}

struct ContentView: View {
    @StateObject private var model = SampleModel()

    var body: some View {
        NavigationView {
            List {
                Section("Client") {
                    Picker("Client", selection: $model.selectedClient) {
                        ForEach(ActiveClientKind.allCases) { client in
                            Text(client.title).tag(client)
                        }
                    }
                    .pickerStyle(SegmentedPickerStyle())
                    if model.selectedClient == .meta {
                        Button("Start Meta Registration") {
                            model.startMetaRegistration()
                        }
                        Button("Enable Meta Mock Device") {
                            model.enableMetaMockDevice()
                        }
                    }
                }

                Section("Device") {
                    InfoRow(title: "Implementation", value: model.selectedClient.title)
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
    @Published var selectedClient: ActiveClientKind = .kotlinSimulator
    @Published var status = "Idle"
    @Published var displayText = ""
    @Published var image: UIImage?

    private let kotlinClient: GlassesClient
    private let swiftStubClient: GlassesClient
    private let frameClient: FrameGlassesClient
    private let metaClient: MetaGlassesClient

    init() {
        var displayUpdate: ((ActiveClientKind, String) -> Void)?
        self.kotlinClient = SimulatorIosGlassesClient(displaySink: { text in
            displayUpdate?(.kotlinSimulator, text)
        })
        self.swiftStubClient = StubSwiftGlassesClient(displaySink: { text in
            displayUpdate?(.swiftStub, text)
        })
        self.frameClient = FrameGlassesClient()
        self.metaClient = MetaGlassesClient()
        displayUpdate = { [weak self] source, text in
            Task { @MainActor in
                self?.displayText = "\(source.title): \(text)"
            }
        }
    }

    private var client: GlassesClient {
        switch selectedClient {
        case .kotlinSimulator:
            return kotlinClient
        case .swiftStub:
            return swiftStubClient
        case .frame:
            return frameClient
        case .meta:
            return metaClient
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
        let client = self.client
        let source = selectedClient
        status = "Connecting \(source.title)"
        client.connect { [weak self] _, error in
            Task { @MainActor in
                if let error {
                    self?.status = "\(source.title) connect failed: \(error.localizedDescription)"
                } else if let stateError = client.state.value as? ConnectionState.Error {
                    self?.status = "\(source.title) connect failed: \(stateError.error)"
                } else {
                    self?.status = "\(source.title) connected"
                }
            }
        }
    }

    func startMetaRegistration() {
        status = "Starting Meta registration"
        metaClient.startRegistration { [weak self] error in
            Task { @MainActor in
                self?.status = error.map { "Meta registration failed: \($0.localizedDescription)" }
                    ?? "Meta registration started"
            }
        }
    }

    func enableMetaMockDevice() {
        status = "Enabling Meta mock device"
        do {
            status = try metaClient.enableMockDevice()
        } catch {
            status = "Meta mock failed: \(error.localizedDescription)"
        }
    }

    func display() {
        let client = self.client
        let source = selectedClient
        let options = DisplayOptions(mode: .replace, force: true)
        client.display(text: "Hello from \(source.title)", options: options) { [weak self] _, error in
            Task { @MainActor in
                if let error {
                    self?.status = "\(source.title) display failed: \(error.localizedDescription)"
                } else {
                    self?.status = "\(source.title) displayed text"
                }
            }
        }
    }

    func capture() {
        let client = self.client
        let source = selectedClient
        status = "Capturing \(source.title)"
        let options = CaptureOptions(
            photoQuality: .high,
            targetWidth: nil,
            targetHeight: nil,
            timeoutMs: 30_000
        )
        client.capturePhoto(options: options) { [weak self] result, error in
            Task { @MainActor in
                if let error {
                    self?.status = "\(source.title) capture failed: \(error.localizedDescription)"
                    return
                }
                guard let captured = result as? CapturedImage else {
                    self?.status = "\(source.title) capture returned an unexpected result"
                    return
                }
                self?.image = UIImage(data: captured.jpegBytes.toData())
                self?.status = "\(source.title) captured \(captured.width?.int32Value ?? 0)x\(captured.height?.int32Value ?? 0)"
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
