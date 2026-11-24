package com.droidbridge.app.input

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.droidbridge.app.net.BridgeEventBus
import com.droidbridge.app.net.BridgeLog
import com.droidbridge.app.net.ClipboardPayload
import com.droidbridge.app.net.IncomingControlEvent
import com.droidbridge.app.net.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal IME ("DroidBridge Keyboard") that delivers Mac keystrokes cleanly via
 * InputConnection.commitText() and KeyEvent / deleteSurroundingText(), and forwards
 * phone clipboard changes to the Mac while the IME is active (Android 10+ clipboard rule).
 */
class BridgeKeyboard : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastSentClipText: String? = null

    override fun onCreate() {
        super.onCreate()
        observeRemoteInput()
        registerClipboardObserver()
    }

    override fun onDestroy() {
        isActive = false
        unregisterClipboardObserver()
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1C1E"))
            setPadding(32, 24, 32, 24)
        }

        val label = TextView(this).apply {
            text = "DroidBridge Keyboard active — type from your Mac"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val switchBtn = Button(this).apply {
            text = "Switch"
            setOnClickListener {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }

        container.addView(label)
        container.addView(switchBtn)
        return container
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        isActive = true
    }

    override fun onFinishInput() {
        isActive = false
        super.onFinishInput()
    }

    private fun observeRemoteInput() {
        scope.launch {
            BridgeEventBus.incomingControl.collect { event ->
                if (!isActive) return@collect
                val ic = currentInputConnection ?: return@collect
                when (event) {
                    is IncomingControlEvent.InputText -> {
                        if (event.payload.text.isNotEmpty()) {
                            ic.commitText(event.payload.text, 1)
                        }
                    }
                    is IncomingControlEvent.InputKey -> {
                        handleSpecialKey(event.payload.key)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun handleSpecialKey(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "backspace" -> {
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            "enter" -> {
                val actionId = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE
                ) {
                    ic.performEditorAction(actionId)
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }
            "tab" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
            "escape" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ESCAPE)
            "left" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            "up" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    private fun registerClipboardObserver() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (!isActive) return@OnPrimaryClipChangedListener
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!text.isNullOrEmpty() && text != lastSentClipText) {
                lastSentClipText = text
                BridgeEventBus.sendControl(
                    Protocol.MSG_CLIPBOARD,
                    Protocol.encodeJson(ClipboardPayload(text))
                )
                BridgeLog.i("Clipboard", "Synced phone clipboard to Mac from active IME")
            }
        }
        clipboardListener = listener
        runCatching { cm.addPrimaryClipChangedListener(listener) }
    }

    private fun unregisterClipboardObserver() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardListener?.let { runCatching { cm.removePrimaryClipChangedListener(it) } }
        clipboardListener = null
    }

    companion object {
        @Volatile
        var isActive: Boolean = false
            private set
    }
}
