import XCTest
@testable import DroidBridge

final class InputControlTests: XCTestCase {

    func testNormalizesAppKitPointsToAndroidTopLeftAndIgnoresLetterbox() {
        let bounds = CGRect(x: 0, y: 0, width: 400, height: 400)
        let video = CGSize(width: 200, height: 400) // aspect-fit rect is x:[100..300], y:[0..400]

        // Left letterbox -> nil
        XCTAssertNil(InputCoordinateNormalizer.normalize(pointInView: CGPoint(x: 50, y: 200), viewBounds: bounds, videoSize: video))
        // Right letterbox -> nil
        XCTAssertNil(InputCoordinateNormalizer.normalize(pointInView: CGPoint(x: 350, y: 200), viewBounds: bounds, videoSize: video))

        // Top-left of video in AppKit is (100, 399.9) -> Android (0, ~0)
        let topLeft = InputCoordinateNormalizer.normalize(pointInView: CGPoint(x: 100, y: 399.9), viewBounds: bounds, videoSize: video)
        XCTAssertNotNil(topLeft)
        XCTAssertEqual(topLeft!.x, 0.0, accuracy: 0.01)
        XCTAssertEqual(topLeft!.y, 0.0, accuracy: 0.01)

        // Bottom-right of video in AppKit is (299.9, 0) -> Android (~1, 1)
        let bottomRight = InputCoordinateNormalizer.normalize(pointInView: CGPoint(x: 299.9, y: 0), viewBounds: bounds, videoSize: video)
        XCTAssertNotNil(bottomRight)
        XCTAssertEqual(bottomRight!.x, 1.0, accuracy: 0.01)
        XCTAssertEqual(bottomRight!.y, 1.0, accuracy: 0.01)
    }

    func testMapsSpecialKeysCorrectly() {
        XCTAssertEqual(InputCoordinateNormalizer.mapSpecialKey(keyCode: 36), "enter")
        XCTAssertEqual(InputCoordinateNormalizer.mapSpecialKey(keyCode: 51), "backspace")
        XCTAssertEqual(InputCoordinateNormalizer.mapSpecialKey(keyCode: 48), "tab")
        XCTAssertEqual(InputCoordinateNormalizer.mapSpecialKey(keyCode: 53), "escape")
        XCTAssertEqual(InputCoordinateNormalizer.mapSpecialKey(keyCode: 123), "left")
        XCTAssertEqual(InputCoordinateNormalizer.mapSpecialKey(keyCode: 124), "right")
        XCTAssertEqual(InputCoordinateNormalizer.mapSpecialKey(keyCode: 125), "down")
        XCTAssertEqual(InputCoordinateNormalizer.mapSpecialKey(keyCode: 126), "up")
        XCTAssertNil(InputCoordinateNormalizer.mapSpecialKey(keyCode: 0)) // 'a' handled via insertText
    }
}
