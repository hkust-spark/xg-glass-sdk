import Foundation

private struct TestFailure: Error {
    let message: String
}

@main
private struct MetaAsyncOperationTests {
    @MainActor
    static func main() async throws {
        // Fail a broken cancellation regression instead of leaving CI waiting indefinitely.
        DispatchQueue.global().asyncAfter(deadline: .now() + 15) {
            fputs("FAIL: Swift operation regression suite exceeded 15 seconds\n", stderr)
            exit(1)
        }
        try await callbackSuccessAndDuplicateCompletion()
        try await cancellationBeforeRegistration()
        try await cancelledTaskDoesNotRegister()
        try await timeoutWithoutVendorCallback()
        try await cancellationDuringCapture()
        try await exclusiveCaptureAndRetryAfterCleanup()
        try await cancellationRacingVendorCallback()
        try await oversizedTimeoutDoesNotOverflow()
        print("PASS: 8 Swift operation regression tests (including 100 cancellation races)")
    }

    @MainActor
    private static func callbackSuccessAndDuplicateCompletion() async throws {
        var cleanupCount = 0
        let value: Int = try await awaitMetaCallback { resolver in
            resolver.resume(returning: 42)
            resolver.resume(throwing: TestFailure(message: "late failure"))
        } cleanup: {
            cleanupCount += 1
        }
        precondition(value == 42 && cleanupCount == 1)
    }

    private static func cancellationBeforeRegistration() async throws {
        let resolver = MetaCallbackAwaiter<Int>()
        resolver.cancel()
        do {
            let _: Int = try await withCheckedThrowingContinuation { continuation in
                precondition(!resolver.install(continuation))
            }
            throw TestFailure(message: "a pre-cancelled registration succeeded")
        } catch is CancellationError {
            resolver.resume(returning: 99)
        }
    }

    @MainActor
    private static func cancelledTaskDoesNotRegister() async throws {
        var registered = false
        var cleaned = false
        let task = Task { @MainActor in
            let _: Int = try await awaitMetaCallback { _ in
                registered = true
            } cleanup: {
                cleaned = true
            }
        }
        task.cancel()
        do {
            try await task.value
            throw TestFailure(message: "pre-cancelled task succeeded")
        } catch is CancellationError {
            precondition(!registered && cleaned)
        }
    }

    @MainActor
    private static func timeoutWithoutVendorCallback() async throws {
        var cleanupCount = 0
        var lateCallback: MetaCallbackAwaiter<Int>?
        let started = Date()
        do {
            let _: Int = try await withMetaTimeout(timeoutMs: 25) {
                try await awaitMetaCallback { resolver in
                    lateCallback = resolver
                } cleanup: {
                    cleanupCount += 1
                }
            }
            throw TestFailure(message: "an unresponsive vendor did not time out")
        } catch is MetaOperationTimeout {
            precondition(Date().timeIntervalSince(started) < 1)
            precondition(cleanupCount == 1)
            lateCallback?.resume(returning: 7)
        }
    }

    @MainActor
    private static func cancellationDuringCapture() async throws {
        var registered = false
        var cleaned = false
        let task = Task { @MainActor in
            let _: Int = try await awaitMetaCallback { _ in
                registered = true
            } cleanup: {
                cleaned = true
            }
        }
        while !registered { await Task.yield() }
        task.cancel()
        do {
            try await task.value
            throw TestFailure(message: "cancelled capture succeeded")
        } catch is CancellationError {
            precondition(cleaned)
        }
    }

    @MainActor
    private static func exclusiveCaptureAndRetryAfterCleanup() async throws {
        let coordinator = MetaPhotoCaptureCoordinator()
        var firstCallback: MetaCallbackAwaiter<Int>?
        var cleanupCount = 0
        var completions = 0
        var retrySucceeded = false
        precondition(coordinator.start {
            try await awaitMetaCallback { resolver in
                firstCallback = resolver
            } cleanup: {
                await Task.yield()
                cleanupCount += 1
            }
        } completion: { result in
            guard case .failure(let error) = result, error is CancellationError else {
                preconditionFailure("first capture should have been cancelled")
            }
            precondition(cleanupCount == 1)
            completions += 1
            // A completion handler may immediately start the next capture.
            precondition(coordinator.start { 42 } completion: { result in
                precondition((try? result.get()) == 42)
                completions += 1
                retrySucceeded = true
            })
        })
        precondition(!coordinator.start { 1 } completion: { _ in
            preconditionFailure("concurrent capture must be rejected")
        })
        while firstCallback == nil { await Task.yield() }
        coordinator.cancel()
        // Until cancellation cleanup finishes, no new caller may own the camera.
        precondition(!coordinator.start { 2 } completion: { _ in
            preconditionFailure("capture started before old listeners were removed")
        })
        while !retrySucceeded { await Task.yield() }
        firstCallback?.resume(returning: 123)
        precondition(cleanupCount == 1 && completions == 2)
    }

    @MainActor
    private static func cancellationRacingVendorCallback() async throws {
        for _ in 0..<100 {
            var registered: MetaCallbackAwaiter<Int>?
            var cleanupCount = 0
            let task = Task { @MainActor in
                try await awaitMetaCallback { resolver in
                    registered = resolver
                } cleanup: {
                    cleanupCount += 1
                }
            }
            while registered == nil { await Task.yield() }
            let resolver = registered!
            let callback = Task.detached { resolver.resume(returning: 42) }
            task.cancel()
            do {
                let value = try await task.value
                precondition(value == 42)
            } catch is CancellationError {
                // Either cancellation or the vendor result may win the race.
            }
            await callback.value
            precondition(cleanupCount == 1)
        }
    }

    private static func oversizedTimeoutDoesNotOverflow() async throws {
        let value = try await withMetaTimeout(timeoutMs: Int64.max) { 42 }
        precondition(value == 42)
    }
}
