import Foundation

/// A callback may finish, fail, or race cancellation, including cancellation before registration.
/// Exactly one result wins; late vendor callbacks are harmless.
internal final class MetaCallbackAwaiter<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?
    private var result: Result<Value, Error>?

    func install(_ continuation: CheckedContinuation<Value, Error>) -> Bool {
        lock.lock()
        if let result {
            lock.unlock()
            continuation.resume(with: result)
            return false
        }
        self.continuation = continuation
        lock.unlock()
        return true
    }

    func resume(returning value: Value) {
        finish(.success(value))
    }

    func resume(throwing error: Error) {
        finish(.failure(error))
    }

    func cancel() {
        resume(throwing: CancellationError())
    }

    private func finish(_ result: Result<Value, Error>) {
        lock.lock()
        guard self.result == nil else {
            lock.unlock()
            return
        }
        self.result = result
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
    }
}

/// Registration and listener disposal stay on the main actor; cancellation can arrive anywhere.
@MainActor
internal func awaitMetaCallback<Value>(
    start: (MetaCallbackAwaiter<Value>) -> Void,
    cleanup: () async -> Void
) async throws -> Value {
    let resolver = MetaCallbackAwaiter<Value>()
    let result: Result<Value, Error>
    do {
        result = .success(try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                if resolver.install(continuation) {
                    start(resolver)
                }
            }
        } onCancel: {
            resolver.cancel()
        })
    } catch {
        result = .failure(error)
    }
    await cleanup()
    return try result.get()
}

internal struct MetaOperationTimeout: LocalizedError {
    let milliseconds: Int64

    var errorDescription: String? {
        "Meta operation timed out after \(milliseconds) ms"
    }
}

/// Operations passed here must cooperate with cancellation (use awaitMetaCallback for vendor APIs).
internal func withMetaTimeout<Value>(
    timeoutMs: Int64,
    operation: @escaping () async throws -> Value
) async throws -> Value {
    let safeTimeoutMs = max(timeoutMs, 1)
    // Saturate extremely large caller values instead of overflowing the nanosecond conversion.
    let nanoseconds = UInt64(min(safeTimeoutMs, Int64(UInt64.max / 1_000_000))) * 1_000_000
    return try await withThrowingTaskGroup(of: Value.self) { group in
        defer { group.cancelAll() }
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: nanoseconds)
            throw MetaOperationTimeout(milliseconds: safeTimeoutMs)
        }
        guard let value = try await group.next() else {
            throw CancellationError()
        }
        return value
    }
}

/// The active slot is released only after the old capture's listener/stream cleanup has finished.
@MainActor
internal final class MetaPhotoCaptureCoordinator {
    private var task: Task<Void, Never>?

    nonisolated init() {}

    func start<Value>(
        operation: @escaping @MainActor () async throws -> Value,
        completion: @escaping @MainActor (Result<Value, Error>) -> Void
    ) -> Bool {
        guard task == nil else { return false }
        task = Task { @MainActor in
            let result: Result<Value, Error>
            do {
                try Task.checkCancellation()
                result = .success(try await operation())
            } catch {
                result = .failure(error)
            }
            task = nil
            completion(result)
        }
        return true
    }

    func cancel() {
        task?.cancel()
    }
}
