import Flutter
@_implementationOnly import FlutterPluginRegistrant
import Foundation

enum FrameFlutterState: Equatable, CustomStringConvertible {
    case disconnected
    case connecting
    case connected
    case error(String)

    init?(payload: [String: Any]) {
        guard payload["type"] as? String == "state" else {
            return nil
        }

        switch payload["value"] as? String {
        case "disconnected":
            self = .disconnected
        case "connecting":
            self = .connecting
        case "connected":
            self = .connected
        case "error":
            self = .error((payload["error"] as? String) ?? "unknown Frame error")
        default:
            return nil
        }
    }

    var description: String {
        switch self {
        case .disconnected:
            return "disconnected"
        case .connecting:
            return "connecting"
        case .connected:
            return "connected"
        case .error(let message):
            return "error(\(message))"
        }
    }
}

final class FrameFlutterRuntime {
    enum RuntimeError: Error {
        case engineRunFailed
    }

    private let lock = NSLock()
    private var eventObservers: [UUID: ([String: Any]) -> Void] = [:]
    private var stateObservers: [UUID: (FrameFlutterState) -> Void] = [:]
    private var latestFrameState: FrameFlutterState = .disconnected

    private let engine: FlutterEngine
    private let channel: FlutterMethodChannel

    var latestState: FrameFlutterState {
        lock.lock()
        defer { lock.unlock() }
        return latestFrameState
    }

    init(engineName: String = "xgglass.frame.runtime") throws {
        // FlutterEngine.run / plugin registration / channel handlers must be set up on the
        // platform (main) thread.
        precondition(Thread.isMainThread, "FrameFlutterRuntime must be created on the main thread")
        let createdEngine = FlutterEngine(name: engineName, project: nil, allowHeadlessExecution: true)
        guard createdEngine.run() else {
            throw RuntimeError.engineRunFailed
        }
        GeneratedPluginRegistrant.register(with: createdEngine)

        engine = createdEngine
        channel = FlutterMethodChannel(
            name: "xgglass/frame/methods",
            binaryMessenger: createdEngine.binaryMessenger
        )
        channel.setMethodCallHandler { [weak self] call, result in
            guard call.method == "onEvent" else {
                result(FlutterMethodNotImplemented)
                return
            }

            let payload = call.arguments as? [String: Any] ?? [
                "type": "unknown",
                "raw": String(describing: call.arguments),
            ]
            self?.record(payload)
            result(nil)
        }
    }

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

    func removeStateObserver(_ id: UUID) {
        lock.lock()
        stateObservers.removeValue(forKey: id)
        lock.unlock()
    }

    func addStateObserver(_ observer: @escaping (FrameFlutterState) -> Void) -> UUID {
        let id = UUID()
        lock.lock()
        stateObservers[id] = observer
        let currentState = latestFrameState
        lock.unlock()
        observer(currentState)
        return id
    }

    func invoke(
        method: String,
        arguments: [String: Any]? = nil,
        completion: @escaping (Any?, FlutterError?) -> Void
    ) {
        channel.invokeMethod(method, arguments: arguments) { response in
            if let error = response as? FlutterError {
                completion(nil, error)
            } else if let object = response as AnyObject?, object === FlutterMethodNotImplemented {
                completion(nil, FlutterError(
                    code: "not_implemented",
                    message: "Frame Flutter method \(method) is not implemented",
                    details: nil
                ))
            } else {
                completion(response, nil)
            }
        }
    }

    func connect(completion: @escaping (Bool, FlutterError?) -> Void) {
        invoke(method: "connect") { response, error in
            completion((response as? Bool) ?? false, error)
        }
    }

    func disconnect(completion: @escaping (Bool, FlutterError?) -> Void) {
        invoke(method: "disconnect") { response, error in
            completion((response as? Bool) ?? false, error)
        }
    }

    func capturePhoto(
        arguments: [String: Any],
        completion: @escaping (FlutterStandardTypedData?, FlutterError?) -> Void
    ) {
        invoke(method: "capturePhoto", arguments: arguments) { response, error in
            if let error {
                completion(nil, error)
            } else if let data = response as? FlutterStandardTypedData {
                completion(data, nil)
            } else {
                completion(nil, FlutterError(
                    code: "unexpected_response",
                    message: "Frame capturePhoto returned \(String(describing: response))",
                    details: nil
                ))
            }
        }
    }

    func displayText(
        arguments: [String: Any],
        completion: @escaping (Bool, FlutterError?) -> Void
    ) {
        invoke(method: "displayText", arguments: arguments) { response, error in
            completion((response as? Bool) ?? false, error)
        }
    }

    func shutdown() {
        // Flutter channel/engine teardown must run on the main thread; deinit may release us
        // on a background thread. Capture engine/channel so the async block keeps them alive.
        let engine = self.engine
        let channel = self.channel
        let work = {
            channel.setMethodCallHandler(nil)
            engine.destroyContext()
        }
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.async(execute: work)
        }
    }

    private func record(_ payload: [String: Any]) {
        let state = FrameFlutterState(payload: payload)

        lock.lock()
        let currentEventObservers = Array(eventObservers.values)
        let currentStateObservers: [(FrameFlutterState) -> Void]
        if let state {
            latestFrameState = state
            currentStateObservers = Array(stateObservers.values)
        } else {
            currentStateObservers = []
        }
        lock.unlock()

        for observer in currentEventObservers {
            observer(payload)
        }
        if let state {
            for observer in currentStateObservers {
                observer(state)
            }
        }
    }

    static func describe(_ payload: [String: Any]) -> String {
        let sanitized = payload.mapValues(sanitize)
        if JSONSerialization.isValidJSONObject(sanitized),
           let data = try? JSONSerialization.data(withJSONObject: sanitized, options: [.sortedKeys]),
           let json = String(data: data, encoding: .utf8) {
            return json
        }
        return String(describing: sanitized)
    }

    static func describe(_ payloads: [[String: Any]]) -> String {
        let sanitized = payloads.map { $0.mapValues(sanitize) }
        if JSONSerialization.isValidJSONObject(sanitized),
           let data = try? JSONSerialization.data(withJSONObject: sanitized, options: [.sortedKeys]),
           let json = String(data: data, encoding: .utf8) {
            return json
        }
        return String(describing: sanitized)
    }

    private static func sanitize(_ value: Any) -> Any {
        switch value {
        case let value as FlutterStandardTypedData:
            return "FlutterStandardTypedData(length:\(value.data.count))"
        case let value as [String: Any]:
            return value.mapValues(sanitize)
        case let value as [Any]:
            return value.map(sanitize)
        case Optional<Any>.none:
            return NSNull()
        default:
            return value
        }
    }
}
