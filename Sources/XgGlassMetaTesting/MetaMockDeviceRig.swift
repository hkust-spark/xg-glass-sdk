import Foundation
import MWDATMockDevice
@_exported import XgGlassMeta

@MainActor
public enum MetaMockDeviceRig {
    private static var mockGlasses: MockGlasses?

    @discardableResult
    public static func enable(cameraFeedURL: URL? = nil, capturedImageURL: URL? = nil) throws -> String {
        let mockDeviceKit = MockDeviceKit.shared
        mockDeviceKit.enable()
        try MetaDATRuntime.configureIfNeeded()

        let glasses = try existingMockGlasses(in: mockDeviceKit) ?? mockDeviceKit.pairGlasses(model: .rayBanMeta)
        glasses.powerOn()
        glasses.unfold()
        glasses.don()

        if let cameraFeedURL {
            glasses.services.camera.setCameraFeed(fileURL: cameraFeedURL)
        }
        if let capturedImageURL {
            glasses.services.camera.setCapturedImage(fileURL: capturedImageURL)
        }

        mockGlasses = glasses
        return glasses.deviceIdentifier
    }

    public static func disable() {
        mockGlasses = nil
        MockDeviceKit.shared.disable()
    }

    public static func reset() {
        disable()
    }

    private static func existingMockGlasses(in mockDeviceKit: MockDeviceKitInterface) -> MockGlasses? {
        mockDeviceKit.pairedDevices.compactMap { $0 as? MockGlasses }.first
    }
}
