import XCTest
import XgGlassMetaTesting

final class MetaMicrophonePolicyTests: XCTestCase {
    func testBluetoothHfpRouteIsAccepted() {
        let decision = MetaBluetoothHfpAudioPolicy.evaluateActiveInputRoute([
            MetaBluetoothHfpAudioPolicy.RouteInput(
                portType: MetaBluetoothHfpAudioPolicy.bluetoothHfpPortType,
                portName: "Ray-Ban Meta",
                uid: "meta-hfp-1"
            )
        ])

        XCTAssertEqual(decision, .accepted(portName: "Ray-Ban Meta", uid: "meta-hfp-1"))
    }

    func testBluetoothHfpRouteIsPickedWhenBuiltInInputAppearsFirst() {
        let decision = MetaBluetoothHfpAudioPolicy.evaluateActiveInputRoute([
            MetaBluetoothHfpAudioPolicy.RouteInput(
                portType: "MicrophoneBuiltIn",
                portName: "iPhone Microphone",
                uid: "built-in"
            ),
            MetaBluetoothHfpAudioPolicy.RouteInput(
                portType: MetaBluetoothHfpAudioPolicy.bluetoothHfpPortType,
                portName: "Ray-Ban Meta",
                uid: "meta-hfp-1"
            )
        ])

        XCTAssertEqual(decision, .accepted(portName: "Ray-Ban Meta", uid: "meta-hfp-1"))
    }

    func testBluetoothHfpRouteIsPickedWhenBuiltInInputAppearsLast() {
        let decision = MetaBluetoothHfpAudioPolicy.evaluateActiveInputRoute([
            MetaBluetoothHfpAudioPolicy.RouteInput(
                portType: MetaBluetoothHfpAudioPolicy.bluetoothHfpPortType,
                portName: "Ray-Ban Meta",
                uid: "meta-hfp-1"
            ),
            MetaBluetoothHfpAudioPolicy.RouteInput(
                portType: "MicrophoneBuiltIn",
                portName: "iPhone Microphone",
                uid: "built-in"
            )
        ])

        XCTAssertEqual(decision, .accepted(portName: "Ray-Ban Meta", uid: "meta-hfp-1"))
    }

    func testBuiltInMicRouteIsRejectedInsteadOfFallingBackToPhoneMic() {
        let decision = MetaBluetoothHfpAudioPolicy.evaluateActiveInputRoute([
            MetaBluetoothHfpAudioPolicy.RouteInput(
                portType: "MicrophoneBuiltIn",
                portName: "iPhone Microphone",
                uid: "built-in"
            )
        ])

        guard case .rejected(let reason) = decision else {
            return XCTFail("Expected built-in mic route to be rejected, got \(decision)")
        }
        XCTAssertTrue(reason.contains("Bluetooth HFP microphone is not routed"))
        XCTAssertTrue(reason.contains("iPhone Microphone"))
    }

    func testEmptyRouteIsRejected() {
        let decision = MetaBluetoothHfpAudioPolicy.evaluateActiveInputRoute([])

        guard case .rejected(let reason) = decision else {
            return XCTFail("Expected empty route to be rejected, got \(decision)")
        }
        XCTAssertTrue(reason.contains("active input route: none"))
    }

    func testAudioFormatReportsActualSampleRateAsPcm16Mono() {
        let format = MetaBluetoothHfpAudioPolicy.audioFormat(sampleRate: 16_000)

        XCTAssertEqual(format.encoding, .pcmS16Le)
        XCTAssertEqual(format.sampleRateHz?.int32Value, 16_000)
        XCTAssertEqual(format.channelCount?.int32Value, 1)
    }

    func testAudioFormatClampsZeroAndNegativeSampleRates() {
        let zero = MetaBluetoothHfpAudioPolicy.audioFormat(sampleRate: 0)
        let negative = MetaBluetoothHfpAudioPolicy.audioFormat(sampleRate: -16_000)

        XCTAssertEqual(zero.sampleRateHz?.int32Value, 1)
        XCTAssertEqual(negative.sampleRateHz?.int32Value, 1)
        XCTAssertEqual(zero.channelCount?.int32Value, 1)
        XCTAssertEqual(negative.channelCount?.int32Value, 1)
    }

    func testRouteIdentityAcceptsSamePinnedBluetoothHfpUid() {
        let decision = MetaBluetoothHfpAudioPolicy.evaluateActiveInputRoute(
            [
                MetaBluetoothHfpAudioPolicy.RouteInput(
                    portType: MetaBluetoothHfpAudioPolicy.bluetoothHfpPortType,
                    portName: "Ray-Ban Meta",
                    uid: "meta-hfp-1"
                )
            ],
            expectedUID: "meta-hfp-1"
        )

        XCTAssertEqual(decision, .accepted(portName: "Ray-Ban Meta", uid: "meta-hfp-1"))
    }

    func testRouteIdentityRejectsDifferentBluetoothHfpUid() {
        let decision = MetaBluetoothHfpAudioPolicy.evaluateActiveInputRoute(
            [
                MetaBluetoothHfpAudioPolicy.RouteInput(
                    portType: MetaBluetoothHfpAudioPolicy.bluetoothHfpPortType,
                    portName: "Bluetooth Earbuds",
                    uid: "earbuds-hfp-9"
                )
            ],
            expectedUID: "meta-hfp-1"
        )

        guard case .rejected(let reason) = decision else {
            return XCTFail("Expected changed HFP uid to be rejected, got \(decision)")
        }
        XCTAssertTrue(reason.contains("route changed"))
        XCTAssertTrue(reason.contains("meta-hfp-1"))
        XCTAssertTrue(reason.contains("earbuds-hfp-9"))
    }
}
