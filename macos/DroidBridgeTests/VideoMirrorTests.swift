import XCTest
@testable import DroidBridge

final class VideoMirrorTests: XCTestCase {

    func testAnnexBToAVCCStripsParameterSetsAndAdds4ByteLengths() {
        // SPS (type 7) + PPS (type 8) + IDR slice (type 5) + P-slice (type 1)
        let sps: [UInt8] = [0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xc0]
        let pps: [UInt8] = [0x00, 0x00, 0x01, 0x68, 0xce, 0x38]
        let idr: [UInt8] = [0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x21]
        let slice: [UInt8] = [0x00, 0x00, 0x01, 0x41, 0x9a]
        let input = Data(sps + pps + idr + slice)

        let avcc = AnnexBConverter.annexBToAVCC(input)
        // Expected: [0,0,0,4, 0x65,0x88,0x84,0x21] + [0,0,0,2, 0x41,0x9a]
        let expected = Data([0, 0, 0, 4, 0x65, 0x88, 0x84, 0x21, 0, 0, 0, 2, 0x41, 0x9a])
        XCTAssertEqual(avcc, expected)
    }

    func testAspectFitRectCalculatesLetterboxAccurately() {
        // Portrait 1:2 video inside a 400x400 square bounds -> 200x400 centered at x=100
        let rect = AnnexBConverter.aspectFitRect(
            videoSize: CGSize(width: 720, height: 1440),
            in: CGRect(x: 0, y: 0, width: 400, height: 400)
        )
        XCTAssertEqual(rect.origin.x, 100, accuracy: 0.01)
        XCTAssertEqual(rect.origin.y, 0, accuracy: 0.01)
        XCTAssertEqual(rect.size.width, 200, accuracy: 0.01)
        XCTAssertEqual(rect.size.height, 400, accuracy: 0.01)
    }

    func testWindowSizePresetsPreserveAspectRatio() {
        let med = AnnexBConverter.windowSize(videoWidth: 720, videoHeight: 1440, preset: .medium)
        XCTAssertEqual(med.height, 720, accuracy: 0.5)
        XCTAssertEqual(med.width, 360, accuracy: 0.5)
    }
}
