package com.droidbridge.app.net

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Lock-free live video/crypto telemetry counters updated by MirrorService and sampled 1x/sec.
 */
object VideoMetricsTracker {
    private val frameCount = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val encryptNanosSum = AtomicLong(0)
    private val socketWriteMicrosSum = AtomicLong(0)
    private val droppedFramesTotal = AtomicLong(0)
    private val currentWidth = AtomicInteger(0)
    private val currentHeight = AtomicInteger(0)
    private var lastSampleElapsedMs: Long = SystemClock.elapsedRealtime()

    fun setDimensions(width: Int, height: Int) {
        currentWidth.set(width)
        currentHeight.set(height)
    }

    fun recordFrameSent(wireBytes: Int, encryptNanos: Long, writeMicros: Long) {
        frameCount.incrementAndGet()
        bytesSent.addAndGet(wireBytes.toLong())
        encryptNanosSum.addAndGet(encryptNanos)
        socketWriteMicrosSum.addAndGet(writeMicros)
    }

    fun recordDroppedFrame() {
        droppedFramesTotal.incrementAndGet()
    }

    fun resetActiveStream() {
        frameCount.set(0)
        bytesSent.set(0)
        encryptNanosSum.set(0)
        socketWriteMicrosSum.set(0)
        currentWidth.set(0)
        currentHeight.set(0)
        lastSampleElapsedMs = SystemClock.elapsedRealtime()
    }

    data class Snapshot(
        val width: Int,
        val height: Int,
        val encodedFps: Double,
        val bitrateKbps: Double,
        val avgEncryptUs: Double,
        val avgWriteMs: Double,
        val droppedFrames: Long
    )

    fun snapshotAndResetWindow(): Snapshot {
        val now = SystemClock.elapsedRealtime()
        val dtSec = ((now - lastSampleElapsedMs).coerceAtLeast(1L)) / 1000.0
        lastSampleElapsedMs = now

        val frames = frameCount.getAndSet(0)
        val bytes = bytesSent.getAndSet(0)
        val encNs = encryptNanosSum.getAndSet(0)
        val writeUs = socketWriteMicrosSum.getAndSet(0)

        val fps = frames / dtSec
        val kbps = (bytes * 8.0) / 1000.0 / dtSec
        val avgEncUs = if (frames > 0) (encNs / 1000.0) / frames else 0.0
        val avgWriteMs = if (frames > 0) (writeUs / 1000.0) / frames else 0.0

        return Snapshot(
            width = currentWidth.get(),
            height = currentHeight.get(),
            encodedFps = fps,
            bitrateKbps = kbps,
            avgEncryptUs = avgEncUs,
            avgWriteMs = avgWriteMs,
            droppedFrames = droppedFramesTotal.get()
        )
    }
}

/**
 * Collects real Android device hardware utilization metrics using 100% public Android APIs (no ADB):
 * - App CPU utilization via Process.getElapsedCpuTime()
 * - Physical memory footprint (PSS MB) via Debug.getPss()
 * - Battery level, temperature (°C), instantaneous current (mA), estimated power (W)
 * - OS thermal status via PowerManager.currentThermalStatus
 */
class TelemetryCollector(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    private val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val totalRamMb: Int by lazy {
        val mi = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(mi)
        (mi.totalMem / (1024L * 1024L)).toInt()
    }
    private val deviceModel: String = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ").trim().ifBlank { "Android" }
    private val socModel: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOfNotNull(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }
            .joinToString(" ")
            .ifBlank { Build.HARDWARE }
    } else {
        Build.HARDWARE
    }
    private val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private var lastCpuTimeMs: Long = Process.getElapsedCpuTime()
    private var lastWallTimeMs: Long = SystemClock.elapsedRealtime()

    fun sample(): DeviceTelemetryPayload {
        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        val wallDelta = (nowWall - lastWallTimeMs).coerceAtLeast(1L)
        val cpuDelta = (nowCpu - lastCpuTimeMs).coerceAtLeast(0L)
        lastWallTimeMs = nowWall
        lastCpuTimeMs = nowCpu

        // Single-core equivalent % (like Android Studio / top) and normalized total-device %
        val singleCorePct = (cpuDelta.toDouble() / wallDelta.toDouble()) * 100.0
        val totalDevicePct = singleCorePct / cpuCores.toDouble()

        val pssKb = runCatching { Debug.getPss() }.getOrDefault(0L)
        val pssMb = pssKb / 1024.0

        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else null
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1

        val currentUa = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull()
        // Some Android OEMs report current in mA instead of uA if magnitude < 10000
        val currentMa: Double? = when {
            currentUa == null || currentUa == Int.MIN_VALUE || currentUa == 0 -> null
            abs(currentUa) > 10_000 -> currentUa / 1000.0
            else -> currentUa.toDouble()
        }
        val powerW: Double? = if (currentMa != null && voltageMv > 0) {
            (abs(currentMa) / 1000.0) * (voltageMv / 1000.0)
        } else null

        val thermalStr = when (powerManager?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }

        val vid = VideoMetricsTracker.snapshotAndResetWindow()

        return DeviceTelemetryPayload(
            timestampMs = System.currentTimeMillis(),
            deviceModel = deviceModel,
            socModel = socModel,
            androidVersion = androidVersion,
            cpuCores = cpuCores,
            totalRamMb = totalRamMb,
            appCpuPercent = totalDevicePct,
            appCpuCorePercent = singleCorePct,
            appPssMb = pssMb,
            batteryPercent = batteryPct,
            isCharging = charging,
            batteryTempC = tempC,
            batteryCurrentMa = currentMa,
            batteryPowerW = powerW,
            thermalStatus = thermalStr,
            mirrorState = BridgeEventBus.mirrorState.value,
            videoWidth = vid.width,
            videoHeight = vid.height,
            encodedFps = vid.encodedFps,
            videoBitrateKbps = vid.bitrateKbps,
            avgEncryptUs = vid.avgEncryptUs,
            avgSocketWriteMs = vid.avgWriteMs,
            droppedFrames = vid.droppedFrames
        )
    }
}
