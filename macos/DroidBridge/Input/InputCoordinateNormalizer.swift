import Foundation
import CoreGraphics

public enum InputCoordinateNormalizer {

    /// Maps a point in AppKit view coordinates (origin bottom-left) into Android normalized [0, 1]
    /// coordinates (origin top-left) inside the aspect-fit video rect.
    /// Returns nil when the point falls inside letterbox padding.
    public static func normalize(
        pointInView: CGPoint,
        viewBounds: CGRect,
        videoSize: CGSize
    ) -> (x: Double, y: Double)? {
        let videoRect = AnnexBConverter.aspectFitRect(videoSize: videoSize, in: viewBounds)
        guard videoRect.width > 0, videoRect.height > 0, videoRect.contains(pointInView) else {
            return nil
        }
        let nx = Double((pointInView.x - videoRect.minX) / videoRect.width)
        // Flip Y: AppKit origin is bottom-left; Android touch origin is top-left
        let ny = Double(1.0 - (pointInView.y - videoRect.minY) / videoRect.height)
        return (min(max(nx, 0.0), 1.0), min(max(ny, 0.0), 1.0))
    }

    /// Maps macOS virtual key codes to DroidBridge protocol `input.key` identifiers.
    public static func mapSpecialKey(keyCode: UInt16) -> String? {
        switch keyCode {
        case 36, 76: return "enter"       // Return / Keypad Enter
        case 51:     return "backspace"   // Delete (Backspace)
        case 48:     return "tab"         // Tab
        case 53:     return "escape"      // Escape
        case 123:    return "left"        // Left Arrow
        case 124:    return "right"       // Right Arrow
        case 125:    return "down"        // Down Arrow
        case 126:    return "up"          // Up Arrow
        default:     return nil
        }
    }
}
