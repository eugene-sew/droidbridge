package com.droidbridge.app.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.droidbridge.app.net.BridgeEventBus
import com.droidbridge.app.net.BridgeLog
import com.droidbridge.app.net.IncomingControlEvent
import com.droidbridge.app.net.InputGlobalPayload
import com.droidbridge.app.net.InputKeyPayload
import com.droidbridge.app.net.InputScrollPayload
import com.droidbridge.app.net.InputTextPayload
import com.droidbridge.app.net.InputTouchPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Injects touches, smooth continuous drags, scrolls, global navigation actions, and fallback
 * text input requested by the user's paired Mac using only public Android AccessibilityService APIs.
 *
 * Uses a GestureResultCallback-driven state machine so continued strokes chain smoothly on
 * onCompleted without Android cancelling overlapping gestures.
 */
class ControlAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastDispatchedPoint: PixelPoint? = null
    private var pendingMovePoint: PixelPoint? = null
    private var pendingUpPoint: PixelPoint? = null
    private var gestureInFlight: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        BridgeLog.i("Input", "ControlAccessibilityService connected")
        observeInputEvents()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not inspecting screen content unless handling ACTION_SET_TEXT fallback
    }

    override fun onInterrupt() {
        resetTouchState()
    }

    override fun onDestroy() {
        isConnected = false
        resetTouchState()
        scope.cancel()
        super.onDestroy()
    }

    private fun resetTouchState() {
        activeStroke = null
        lastDispatchedPoint = null
        pendingMovePoint = null
        pendingUpPoint = null
        gestureInFlight = false
    }

    private fun observeInputEvents() {
        scope.launch {
            BridgeEventBus.incomingControl.collect { event ->
                when (event) {
                    is IncomingControlEvent.InputTouch -> handleTouch(event.payload)
                    is IncomingControlEvent.InputScroll -> handleScroll(event.payload)
                    is IncomingControlEvent.InputGlobal -> handleGlobal(event.payload)
                    is IncomingControlEvent.InputText -> {
                        if (!BridgeKeyboard.isActive) {
                            handleTextFallback(event.payload)
                        }
                    }
                    is IncomingControlEvent.InputKey -> {
                        if (!BridgeKeyboard.isActive) {
                            handleKeyFallback(event.payload)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun handleTouch(touch: InputTouchPayload) {
        val (w, h) = currentDisplaySize()
        val pt = InputCoordinateMapper.toPixels(touch.x, touch.y, w, h)

        when (touch.phase) {
            "down" -> {
                resetTouchState()
                val path = Path().apply { moveTo(pt.x, pt.y) }
                // API 26+: willContinue = true lets stroke timing naturally decide tap vs long-press
                val stroke = GestureDescription.StrokeDescription(path, 0L, SEGMENT_MS, true)
                activeStroke = stroke
                lastDispatchedPoint = pt
                dispatchChainedStroke(stroke)
            }
            "move" -> {
                if (activeStroke == null) return
                pendingMovePoint = pt
                if (!gestureInFlight) {
                    flushPendingStrokeSegment()
                }
            }
            "up" -> {
                if (activeStroke == null) return
                pendingUpPoint = pt
                if (!gestureInFlight) {
                    flushPendingStrokeSegment()
                }
            }
        }
    }

    private fun flushPendingStrokeSegment() {
        val prevStroke = activeStroke ?: return
        val prevPt = lastDispatchedPoint ?: return

        val upPt = pendingUpPoint
        if (upPt != null) {
            pendingUpPoint = null
            pendingMovePoint = null
            val path = Path().apply {
                moveTo(prevPt.x, prevPt.y)
                lineTo(upPt.x, upPt.y)
            }
            val finalStroke = runCatching {
                prevStroke.continueStroke(path, 0L, SEGMENT_MS, false)
            }.getOrNull()
            activeStroke = null
            lastDispatchedPoint = null
            if (finalStroke != null) {
                dispatchChainedStroke(finalStroke, isFinal = true)
            }
            return
        }

        val movePt = pendingMovePoint
        if (movePt != null) {
            pendingMovePoint = null
            val path = Path().apply {
                moveTo(prevPt.x, prevPt.y)
                lineTo(movePt.x, movePt.y)
            }
            val nextStroke = runCatching {
                prevStroke.continueStroke(path, 0L, SEGMENT_MS, true)
            }.getOrNull()
            if (nextStroke != null) {
                activeStroke = nextStroke
                lastDispatchedPoint = movePt
                dispatchChainedStroke(nextStroke)
            } else {
                resetTouchState()
            }
        }
    }

    private fun dispatchChainedStroke(stroke: GestureDescription.StrokeDescription, isFinal: Boolean = false) {
        gestureInFlight = true
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    if (!isFinal) {
                        flushPendingStrokeSegment()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    resetTouchState()
                }
            },
            null
        )
        if (!ok) {
            resetTouchState()
        }
    }

    private fun handleScroll(scroll: InputScrollPayload) {
        if (gestureInFlight && activeStroke != null) return
        resetTouchState()

        val (w, h) = currentDisplaySize()
        val seg = InputCoordinateMapper.scrollToSwipe(scroll, w, h) ?: return
        val path = Path().apply {
            moveTo(seg.start.x, seg.start.y)
            lineTo(seg.end.x, seg.end.y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, seg.durationMs, false)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun handleGlobal(global: InputGlobalPayload) {
        val actionId = when (global.action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN // API 28+
            else -> return
        }
        performGlobalAction(actionId)
    }

    private fun handleTextFallback(payload: InputTextPayload) {
        if (payload.text.isEmpty()) return
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        try {
            if (!focused.isEditable) return
            BridgeLog.w(
                "Input",
                "DroidBridge Keyboard is not active; using ACTION_SET_TEXT fallback (replaces full field)"
            )
            val existing = if (focused.isShowingHintText) "" else (focused.text?.toString() ?: "")
            val updated = existing + payload.text
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            @Suppress("DEPRECATION")
            focused.recycle()
        }
    }

    private fun handleKeyFallback(payload: InputKeyPayload) {
        when (payload.key) {
            "escape" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "backspace" -> {
                val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
                try {
                    if (!focused.isEditable || focused.isShowingHintText) return
                    val existing = focused.text?.toString() ?: ""
                    if (existing.isEmpty()) return
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            existing.dropLast(1)
                        )
                    }
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } finally {
                    @Suppress("DEPRECATION")
                    focused.recycle()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplaySize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    companion object {
        private const val SEGMENT_MS = 16L

        @Volatile
        var isConnected: Boolean = false
            private set
    }
}
