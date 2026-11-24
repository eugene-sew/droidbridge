package com.droidbridge.app.net

import android.util.Log
import com.droidbridge.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BridgeLogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String
)

object BridgeLog {
    private const val MAX_ENTRIES = 500
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private val buffer = ArrayDeque<BridgeLogEntry>(MAX_ENTRIES)
    private val _entries = MutableStateFlow<List<BridgeLogEntry>>(emptyList())
    val entries: StateFlow<List<BridgeLogEntry>> = _entries.asStateFlow()

    fun i(tag: String, message: String) {
        record("I", tag, message)
        runCatching { Log.i("DB/$tag", message) }
    }

    fun w(tag: String, message: String) {
        record("W", tag, message)
        runCatching { Log.w("DB/$tag", message) }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message: ${throwable.message}" else message
        record("E", tag, full)
        runCatching { Log.e("DB/$tag", message, throwable) }
    }

    /**
     * Sensitive diagnostic logging (e.g. notification metadata or input diagnostics).
     * Never emits content in release builds.
     */
    fun dSensitive(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        record("D", tag, message)
        runCatching { Log.d("DB/$tag", message) }
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        _entries.value = emptyList()
    }

    private fun record(level: String, tag: String, message: String) = synchronized(lock) {
        if (buffer.size >= MAX_ENTRIES) {
            buffer.removeFirst()
        }
        buffer.addLast(BridgeLogEntry(timeFmt.format(Date()), level, tag, message))
        _entries.value = buffer.toList()
    }
}
