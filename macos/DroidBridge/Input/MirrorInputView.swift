import SwiftUI
import AppKit
import AVFoundation

/// SwiftUI bridge wrapping the AppKit `MirrorInputHostView` that renders the
/// `AVSampleBufferDisplayLayer` and captures pointer, scroll, keyboard, and IME events.
public struct MirrorInputView: NSViewRepresentable {
    @ObservedObject var viewModel: MirrorViewModel

    public init(viewModel: MirrorViewModel) {
        self.viewModel = viewModel
    }

    public func makeNSView(context: Context) -> MirrorInputHostView {
        let view = MirrorInputHostView(viewModel: viewModel)
        return view
    }

    public func updateNSView(_ nsView: MirrorInputHostView, context: Context) {
        nsView.updateVideoSize(viewModel.decoder.videoSize)
    }
}

public final class MirrorInputHostView: NSView, NSTextInputClient {
    private let viewModel: MirrorViewModel
    private let videoLayer: CALayer
    private let cursorDotLayer = CALayer()

    private var videoSize: CGSize
    private var isDragging = false
    private var lastMoveTime: TimeInterval = 0
    private var markedTextStorage = NSAttributedString()
    private var markedTextRange = NSRange(location: NSNotFound, length: 0)
    private var trackingArea: NSTrackingArea?

    public init(viewModel: MirrorViewModel) {
        self.viewModel = viewModel
        self.videoLayer = viewModel.decoder.renderLayer
        self.videoSize = viewModel.decoder.videoSize
        super.init(frame: .zero)

        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(videoLayer)

        // Subtle translucent touch cursor dot
        cursorDotLayer.bounds = CGRect(x: 0, y: 0, width: 16, height: 16)
        cursorDotLayer.cornerRadius = 8
        cursorDotLayer.backgroundColor = NSColor.white.withAlphaComponent(0.32).cgColor
        cursorDotLayer.borderColor = NSColor.white.withAlphaComponent(0.75).cgColor
        cursorDotLayer.borderWidth = 1.2
        cursorDotLayer.isHidden = true
        layer?.addSublayer(cursorDotLayer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) not supported")
    }

    public override var acceptsFirstResponder: Bool { true }

    public func updateVideoSize(_ size: CGSize) {
        self.videoSize = size
        needsLayout = true
    }

    public override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer.frame = bounds
        CATransaction.commit()
    }

    public override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let existing = trackingArea {
            removeTrackingArea(existing)
        }
        let area = NSTrackingArea(
            rect: bounds,
            options: [.mouseMoved, .mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(area)
        trackingArea = area
    }

    // MARK: - Pointer & Touch Events

    public override func mouseEntered(with event: NSEvent) {
        updateCursorDot(with: event)
    }

    public override func mouseExited(with event: NSEvent) {
        cursorDotLayer.isHidden = true
    }

    public override func mouseMoved(with event: NSEvent) {
        updateCursorDot(with: event)
    }

    public override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        let pt = convert(event.locationInWindow, from: nil)
        guard let norm = InputCoordinateNormalizer.normalize(pointInView: pt, viewBounds: bounds, videoSize: videoSize) else {
            return
        }
        isDragging = true
        lastMoveTime = event.timestamp
        updateCursorDot(at: pt, pressed: true)
        viewModel.onSendTouch?(InputTouchPayload(
            phase: "down",
            x: norm.x,
            y: norm.y,
            t: Int64(Date().timeIntervalSince1970 * 1000)
        ))
    }

    public override func mouseDragged(with event: NSEvent) {
        guard isDragging else { return }
        let pt = convert(event.locationInWindow, from: nil)
        updateCursorDot(at: pt, pressed: true)
        // Coalesce moves to ~60 Hz
        guard event.timestamp - lastMoveTime >= 0.015 else { return }
        lastMoveTime = event.timestamp
        guard let norm = InputCoordinateNormalizer.normalize(pointInView: pt, viewBounds: bounds, videoSize: videoSize) else {
            return
        }
        viewModel.onSendTouch?(InputTouchPayload(
            phase: "move",
            x: norm.x,
            y: norm.y,
            t: Int64(Date().timeIntervalSince1970 * 1000)
        ))
    }

    public override func mouseUp(with event: NSEvent) {
        guard isDragging else { return }
        isDragging = false
        let pt = convert(event.locationInWindow, from: nil)
        updateCursorDot(at: pt, pressed: false)
        let videoRect = AnnexBConverter.aspectFitRect(videoSize: videoSize, in: bounds)
        let clampedPt = CGPoint(
            x: min(max(pt.x, videoRect.minX), videoRect.maxX - 0.001),
            y: min(max(pt.y, videoRect.minY), videoRect.maxY - 0.001)
        )
        if let norm = InputCoordinateNormalizer.normalize(pointInView: clampedPt, viewBounds: bounds, videoSize: videoSize) {
            viewModel.onSendTouch?(InputTouchPayload(
                phase: "up",
                x: norm.x,
                y: norm.y,
                t: Int64(Date().timeIntervalSince1970 * 1000)
            ))
        }
    }

    public override func rightMouseDown(with event: NSEvent) {
        viewModel.onSendGlobal?("back")
    }

    public override func scrollWheel(with event: NSEvent) {
        let pt = convert(event.locationInWindow, from: nil)
        guard let norm = InputCoordinateNormalizer.normalize(pointInView: pt, viewBounds: bounds, videoSize: videoSize) else {
            return
        }
        let scale: Double = event.hasPreciseScrollingDeltas ? 1.0 : 12.0
        let dx = Double(event.scrollingDeltaX) * scale
        let dy = Double(event.scrollingDeltaY) * scale
        viewModel.onSendScroll?(InputScrollPayload(dx: dx, dy: dy, x: norm.x, y: norm.y))
    }

    // MARK: - Keyboard & Shortcuts

    public override func keyDown(with event: NSEvent) {
        let mods = event.modifierFlags.intersection(.deviceIndependentFlagsMask)

        // Command shortcuts
        if mods.contains(.command) {
            switch event.charactersIgnoringModifiers?.lowercased() {
            case "1":
                viewModel.onSendGlobal?("home")
                return
            case "2":
                viewModel.onSendGlobal?("recents")
                return
            case "[":
                viewModel.onSendGlobal?("back")
                return
            case "n":
                viewModel.onSendGlobal?("notifications")
                return
            case "v":
                viewModel.onPasteClipboard?()
                return
            default:
                break
            }
        }

        // Escape triggers Back per spec (§7.4)
        if event.keyCode == 53 {
            viewModel.onSendGlobal?("back")
            return
        }

        if let special = InputCoordinateNormalizer.mapSpecialKey(keyCode: event.keyCode) {
            viewModel.onSendKey?(special)
            return
        }

        // Use Cocoa text input system so dead keys & IME composition deliver via insertText(_:)
        interpretKeyEvents([event])
    }

    public override func doCommand(by selector: Selector) {
        switch selector {
        case #selector(insertNewline(_:)):
            viewModel.onSendKey?("enter")
        case #selector(deleteBackward(_:)):
            viewModel.onSendKey?("backspace")
        case #selector(insertTab(_:)):
            viewModel.onSendKey?("tab")
        case #selector(cancelOperation(_:)):
            viewModel.onSendGlobal?("back")
        case #selector(moveLeft(_:)):
            viewModel.onSendKey?("left")
        case #selector(moveRight(_:)):
            viewModel.onSendKey?("right")
        case #selector(moveUp(_:)):
            viewModel.onSendKey?("up")
        case #selector(moveDown(_:)):
            viewModel.onSendKey?("down")
        default:
            break
        }
    }

    // MARK: - NSTextInputClient (Dead Keys & Composition)

    public func insertText(_ string: Any, replacementRange: NSRange) {
        let text: String
        if let attr = string as? NSAttributedString {
            text = attr.string
        } else if let str = string as? String {
            text = str
        } else {
            return
        }
        markedTextStorage = NSAttributedString()
        markedTextRange = NSRange(location: NSNotFound, length: 0)
        if !text.isEmpty {
            viewModel.onSendText?(text)
        }
    }

    public func setMarkedText(_ string: Any, selectedRange: NSRange, replacementRange: NSRange) {
        if let attr = string as? NSAttributedString {
            markedTextStorage = attr
        } else if let str = string as? String {
            markedTextStorage = NSAttributedString(string: str)
        } else {
            markedTextStorage = NSAttributedString()
        }
        markedTextRange = markedTextStorage.length > 0
            ? NSRange(location: 0, length: markedTextStorage.length)
            : NSRange(location: NSNotFound, length: 0)
    }

    public func unmarkText() {
        markedTextStorage = NSAttributedString()
        markedTextRange = NSRange(location: NSNotFound, length: 0)
    }

    public func selectedRange() -> NSRange { NSRange(location: 0, length: 0) }
    public func markedRange() -> NSRange { markedTextRange }
    public func hasMarkedText() -> Bool { markedTextStorage.length > 0 }
    public func attributedSubstring(forProposedRange range: NSRange, actualRange: NSRangePointer?) -> NSAttributedString? { nil }
    public func validAttributesForMarkedText() -> [NSAttributedString.Key] { [] }
    public func firstRect(forCharacterRange range: NSRange, actualRange: NSRangePointer?) -> NSRect { .zero }
    public func characterIndex(for point: NSPoint) -> Int { 0 }

    // MARK: - Cursor Dot

    private func updateCursorDot(with event: NSEvent) {
        let pt = convert(event.locationInWindow, from: nil)
        updateCursorDot(at: pt, pressed: isDragging)
    }

    private func updateCursorDot(at pt: CGPoint, pressed: Bool) {
        let videoRect = AnnexBConverter.aspectFitRect(videoSize: videoSize, in: bounds)
        let inside = videoRect.contains(pt)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        cursorDotLayer.isHidden = !inside
        if inside {
            cursorDotLayer.position = pt
            let size: CGFloat = pressed ? 22 : 14
            cursorDotLayer.bounds = CGRect(x: 0, y: 0, width: size, height: size)
            cursorDotLayer.cornerRadius = size / 2
            cursorDotLayer.backgroundColor = NSColor.white.withAlphaComponent(pressed ? 0.55 : 0.28).cgColor
        }
        CATransaction.commit()
    }
}
