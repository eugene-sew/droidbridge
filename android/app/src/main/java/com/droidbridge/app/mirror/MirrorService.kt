package com.droidbridge.app.mirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.droidbridge.app.net.BridgeConnectionService
import com.droidbridge.app.net.BridgeEventBus
import com.droidbridge.app.net.BridgeLog
import com.droidbridge.app.net.EncryptedChannel
import com.droidbridge.app.net.IncomingControlEvent
import com.droidbridge.app.net.MirrorQualityPayload
import com.droidbridge.app.net.Protocol
import com.droidbridge.app.net.VideoConfigPayload
import com.droidbridge.app.net.VideoFramePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance low-latency screen mirroring service with:
 * - Zero B-frames (KEY_MAX_B_FRAMES=0), real-time priority (KEY_PRIORITY=0), KEY_LOW_LATENCY=1, VBR
 * - Dedicated THREAD_PRIORITY_DISPLAY encoder & sender threads
 * - Dynamic adaptive bitrate responding to real-time Wi-Fi write latency
 * - Seamless Standby Mode: when Mac closes the mirror window (mirror.pause) or Wi-Fi blips,
 *   the encoder stops (0% CPU/network) while keeping MediaProjection + single VirtualDisplay alive
 *   so reopening "Open Phone" resumes streaming in <100ms with ZERO re-prompt!
 */
class MirrorService : LifecycleService() {

    private lateinit var displayManager: DisplayManager
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var videoChannel: EncryptedChannel? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val backpressureQueue = VideoFrameBackpressureQueue(maxQueuedFrames = 2)
    private val frameSignal = LinkedBlockingQueue<Unit>(1)

    private val isReconfiguring = AtomicBoolean(false)
    private val isDestroying = AtomicBoolean(false)
    private val streamActive = AtomicBoolean(false)
    private val streamGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private var encoderThread: Thread? = null
    private var senderThread: Thread? = null

    private var lastRotation: Int = -1
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentRotationDegrees: Int = 0

    // Adaptive quality settings (default: Balanced 1280p @ 4 Mbps @ 60 fps)
    @Volatile
    private var targetMaxLongSide: Int = 1280
    @Volatile
    private var targetBitrateBps: Int = 4_000_000
    @Volatile
    private var targetFps: Int = 60
    @Volatile
    private var currentAdaptiveBitrate: Int = 4_000_000

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            BridgeLog.i("Mirror", "MediaProjection stopped by system")
            endProjectionCompletely("Screen capture stopped by system")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || isDestroying.get()) return
            val rotation = getDisplayRotationSurface()
            if (rotation != lastRotation && lastRotation != -1) {
                lastRotation = rotation
                if (streamActive.get()) {
                    BridgeLog.i("Mirror", "Display rotated ($rotation); rebuilding encoder")
                    rebuildEncoderAsync()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createMirrorNotificationChannel()
        observeControlEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                endProjectionCompletely(null)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseToStandby()
                return START_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            BridgeEventBus.setMirrorState("error", "Missing screen capture consent")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ (API 34+): MUST promote to mediaProjection foreground service BEFORE getMediaProjection()
        startAsForeground(streaming = true)
        initializeProjectionAndStream(resultCode, resultData)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        isReadyInStandby = false
        isStreaming = false
        cleanupEverything()
        super.onDestroy()
    }

    private fun initializeProjectionAndStream(resultCode: Int, resultData: Intent) {
        isDestroying.set(false)
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = try {
                pm.getMediaProjection(resultCode, resultData)
            } catch (e: Exception) {
                BridgeLog.e("Mirror", "getMediaProjection failed", e)
                BridgeEventBus.setMirrorState("error", "Failed to start screen capture: ${e.message}")
                stopSelf()
                return@launch
            }
            if (projection == null) {
                BridgeEventBus.setMirrorState("error", "MediaProjection returned null")
                stopSelf()
                return@launch
            }

            mediaProjection = projection
            // Android 14+ (API 34+): MUST registerCallback BEFORE createVirtualDisplay
            projection.registerCallback(projectionCallback, mainHandler)

            lastRotation = getDisplayRotationSurface()
            displayManager.registerDisplayListener(displayListener, mainHandler)

            isReadyInStandby = true
            startOrResumeStreaming()
        }
    }

    /**
     * Starts or resumes live encoding and streaming using the existing MediaProjection
     * (zero re-prompt!). Safe to call repeatedly when Mac opens the mirror window or reconnects.
     */
    private fun startOrResumeStreaming() {
        if (isDestroying.get() || mediaProjection == null) return
        if (streamActive.get()) {
            // Already streaming; just force an immediate keyframe so Mac renders immediately
            requestSyncFrame()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val connService = BridgeConnectionService.instance
            if (connService == null) {
                BridgeEventBus.setMirrorState("error", "Bridge connection service is not running")
                return@launch
            }

            val ch = connService.openVideoChannel()
            if (ch == null) {
                BridgeLog.w("Mirror", "Could not open video channel; staying in standby")
                BridgeEventBus.setMirrorState("standby", "Video channel unavailable; retrying on next open")
                return@launch
            }

            tearDownEncoderAndSocket()
            videoChannel = ch
            currentAdaptiveBitrate = targetBitrateBps
            backpressureQueue.clear()

            try {
                configureEncoderAndDisplay()
            } catch (e: Exception) {
                BridgeLog.e("Mirror", "Encoder initialization failed", e)
                BridgeEventBus.setMirrorState("error", "Video encoder failed: ${e.message}")
                tearDownEncoderAndSocket()
                return@launch
            }

            acquireWakeLock()
            val gen = streamGeneration.incrementAndGet()
            streamActive.set(true)
            isStreaming = true
            isReadyInStandby = true
            updateForegroundNotification(streaming = true)
            BridgeEventBus.setMirrorState("streaming")

            startDedicatedSenderThread(ch, gen)
            startDedicatedEncoderThread(gen)
        }
    }

    /**
     * Pauses encoding & closes the video socket while keeping MediaProjection + VirtualDisplay
     * alive in zero-CPU standby so future "Open Phone" clicks resume instantly with zero prompt.
     */
    private fun pauseToStandby() {
        if (!streamActive.compareAndSet(true, false)) return
        streamGeneration.incrementAndGet()
        isStreaming = false
        BridgeLog.i("Mirror", "Pausing mirror to zero-CPU standby (MediaProjection kept ready)")
        lifecycleScope.launch(Dispatchers.IO) {
            tearDownEncoderAndSocket()
            releaseWakeLock()
            updateForegroundNotification(streaming = false)
            BridgeEventBus.setMirrorState("standby")
        }
    }

    private fun configureEncoderAndDisplay() {
        val (rawW, rawH, dpi, rotDeg) = getCurrentDisplayMetrics()
        currentRotationDegrees = rotDeg

        val sizeCandidates = listOf(
            VideoEncoderUtils.computeScaledSize(rawW, rawH, maxLongSide = targetMaxLongSide, alignment = 16),
            VideoEncoderUtils.computeScaledSize(rawW, rawH, maxLongSide = targetMaxLongSide, alignment = 2)
        ).distinct()

        val fpsCandidates = listOf(targetFps, 30).distinct()
        var configuredCodec: MediaCodec? = null
        var chosenSize: EncodedSize? = null

        outer@ for (size in sizeCandidates) {
            for (fps in fpsCandidates) {
                for (useAdvancedLowLatency in listOf(true, false)) {
                    val candidate = runCatching {
                        val mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
                            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                            setInteger(MediaFormat.KEY_BIT_RATE, currentAdaptiveBitrate)
                            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
                            // Critical: disable B-frames to eliminate 2-4 frames of reorder latency & stutter!
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                            // VBR uses ~0 kbps on static screens and bursts cleanly during scrolling
                            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L) // 250 ms
                            if (useAdvancedLowLatency) {
                                // Real-time hardware codec priority
                                setInteger(MediaFormat.KEY_PRIORITY, 0)
                                // Android 11+ (API 30+): hardware low-latency pipeline
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                                }
                            }
                        }
                        mc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        mc
                    }.getOrNull()

                    if (candidate != null) {
                        configuredCodec = candidate
                        chosenSize = size
                        BridgeLog.i(
                            "Mirror",
                            "H.264 encoder ready: ${size.width}x${size.height} @ ${fps}fps " +
                                "${currentAdaptiveBitrate / 1000}kbps VBR B=0 lowLat=$useAdvancedLowLatency"
                        )
                        break@outer
                    }
                }
            }
        }

        val activeCodec = configuredCodec ?: error("Unable to configure H.264 MediaCodec encoder")
        val finalSize = chosenSize!!
        currentWidth = finalSize.width
        currentHeight = finalSize.height

        val surface = activeCodec.createInputSurface()
        activeCodec.start()

        codec = activeCodec
        inputSurface = surface

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "DroidBridgeMirror",
                finalSize.width,
                finalSize.height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                mainHandler
            )
        } else {
            // Android 14+: always reuse the single VirtualDisplay per MediaProjection
            virtualDisplay?.resize(finalSize.width, finalSize.height, dpi)
            virtualDisplay?.setSurface(surface)
        }
    }

    private fun rebuildEncoderAsync() {
        if (!isReconfiguring.compareAndSet(false, true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!streamActive.get() || isDestroying.get()) return@launch
                val ch = videoChannel ?: return@launch
                val gen = streamGeneration.incrementAndGet()
                joinStreamingThreads()

                virtualDisplay?.setSurface(null)
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { inputSurface?.release() }
                codec = null
                inputSurface = null
                backpressureQueue.clear()

                Thread.sleep(100)
                if (streamActive.get() && !isDestroying.get()) {
                    configureEncoderAndDisplay()
                    startDedicatedSenderThread(ch, gen)
                    startDedicatedEncoderThread(gen)
                }
            } catch (e: Exception) {
                BridgeLog.e("Mirror", "Encoder reconfigure failed", e)
                pauseToStandby()
            } finally {
                isReconfiguring.set(false)
            }
        }
    }

    private fun startDedicatedEncoderThread(gen: Int) {
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            val bufferInfo = MediaCodec.BufferInfo()
            var configSent = false

            while (streamActive.get() && streamGeneration.get() == gen && !isDestroying.get()) {
                val activeCodec = codec ?: break
                val outIndex = try {
                    activeCodec.dequeueOutputBuffer(bufferInfo, 10_000L)
                } catch (e: Exception) {
                    if (!isReconfiguring.get() && streamActive.get()) {
                        BridgeLog.w("Mirror", "dequeueOutputBuffer error: ${e.message}")
                    }
                    break
                }

                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = activeCodec.outputFormat
                        val csd0 = fmt.getByteBuffer("csd-0")?.let { bb ->
                            ByteArray(bb.remaining()).also { bb.get(it); bb.rewind() }
                        }
                        val csd1 = fmt.getByteBuffer("csd-1")?.let { bb ->
                            ByteArray(bb.remaining()).also { bb.get(it); bb.rewind() }
                        }
                        if (csd0 != null && csd1 != null) {
                            VideoEncoderUtils.extractSpsAndPps(csd0 + csd1)?.let { (sps, pps) ->
                                sendVideoConfig(sps, pps)
                                configSent = true
                            }
                        }
                    }
                    outIndex >= 0 -> {
                        val outBuf = activeCodec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val data = ByteArray(bufferInfo.size)
                            outBuf.get(data)

                            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (isCodecConfig) {
                                VideoEncoderUtils.extractSpsAndPps(data)?.let { (sps, pps) ->
                                    sendVideoConfig(sps, pps)
                                    configSent = true
                                }
                            } else if (configSent) {
                                val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                val flags = if (isKey) VideoFramePayload.FLAG_KEYFRAME else 0
                                val frame = VideoFramePayload(
                                    ptsMicros = bufferInfo.presentationTimeUs,
                                    flags = flags,
                                    nalData = data
                                )
                                val needKeyframe = backpressureQueue.offer(frame)
                                frameSignal.offer(Unit)
                                if (needKeyframe) {
                                    com.droidbridge.app.net.VideoMetricsTracker.recordDroppedFrame()
                                    requestSyncFrame()
                                    adjustBitrateForCongestion(congested = true)
                                }
                            }
                        }
                        runCatching { activeCodec.releaseOutputBuffer(outIndex, false) }
                    }
                }
            }
        }, "DroidBridge-Encoder-$gen")
        encoderThread = thread
        thread.start()
    }

    private fun startDedicatedSenderThread(channel: EncryptedChannel, gen: Int) {
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            var fastSendStreak = 0
            try {
                while (streamActive.get() && streamGeneration.get() == gen && !isDestroying.get() && !channel.isClosed) {
                    var frame = backpressureQueue.poll()
                    if (frame == null) {
                        frameSignal.poll(50, TimeUnit.MILLISECONDS)
                        frame = backpressureQueue.poll() ?: continue
                    }
                    val writeMs = channel.send(Protocol.MSG_VIDEO_FRAME, frame.encode(), recordVideoMetrics = true)
                    if (writeMs > 45L) {
                        fastSendStreak = 0
                        adjustBitrateForCongestion(congested = true)
                    } else if (writeMs < 12L) {
                        fastSendStreak++
                        if (fastSendStreak >= 90) { // ~1.5s of clean fast sends -> ramp bitrate back up
                            fastSendStreak = 0
                            adjustBitrateForCongestion(congested = false)
                        }
                    }
                }
            } catch (e: Exception) {
                if (streamActive.get() && streamGeneration.get() == gen && !isDestroying.get()) {
                    BridgeLog.w("Mirror", "Video socket dropped (${e.message}); attempting seamless video recovery")
                    recoverVideoChannel()
                }
            }
        }, "DroidBridge-VideoSend-$gen")
        senderThread = thread
        thread.start()
    }

    /**
     * Automatically re-opens the video socket after a transient Wi-Fi hiccup while keeping
     * MediaProjection alive — no user prompt required!
     */
    private fun recoverVideoChannel() {
        if (!streamActive.compareAndSet(true, false)) return
        streamGeneration.incrementAndGet()
        isStreaming = false
        lifecycleScope.launch(Dispatchers.IO) {
            tearDownEncoderAndSocket()
            val connService = BridgeConnectionService.instance
            if (connService == null || isDestroying.get() || mediaProjection == null) {
                updateForegroundNotification(streaming = false)
                BridgeEventBus.setMirrorState("standby")
                return@launch
            }
            // Wait briefly and try to seamlessly resume if control is still connected
            Thread.sleep(350)
            if (BridgeEventBus.connectionState.value == com.droidbridge.app.net.BridgeConnectionState.CONNECTED) {
                startOrResumeStreaming()
            } else {
                releaseWakeLock()
                updateForegroundNotification(streaming = false)
                BridgeEventBus.setMirrorState("standby")
            }
        }
    }

    private fun adjustBitrateForCongestion(congested: Boolean) {
        val activeCodec = codec ?: return
        val newBitrate = if (congested) {
            (currentAdaptiveBitrate * 75 / 100).coerceAtLeast(MIN_ADAPTIVE_BITRATE_BPS)
        } else {
            (currentAdaptiveBitrate * 115 / 100).coerceAtMost(targetBitrateBps)
        }
        if (newBitrate != currentAdaptiveBitrate) {
            currentAdaptiveBitrate = newBitrate
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
            }
            runCatching { activeCodec.setParameters(params) }
        }
    }

    private fun sendVideoConfig(sps: ByteArray, pps: ByteArray) {
        val ch = videoChannel ?: return
        val cfg = VideoConfigPayload(
            width = currentWidth,
            height = currentHeight,
            rotation = currentRotationDegrees,
            sps = sps,
            pps = pps
        )
        runCatching {
            ch.send(Protocol.MSG_VIDEO_CONFIG, cfg.encode())
            com.droidbridge.app.net.VideoMetricsTracker.setDimensions(currentWidth, currentHeight)
            BridgeLog.i("Mirror", "Sent video.config ${currentWidth}x${currentHeight} rot=$currentRotationDegrees")
        }.onFailure {
            BridgeLog.w("Mirror", "Failed sending video.config: ${it.message}")
            recoverVideoChannel()
        }
    }

    private fun requestSyncFrame() {
        val activeCodec = codec ?: return
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        runCatching { activeCodec.setParameters(params) }
    }

    private fun observeControlEvents() {
        lifecycleScope.launch {
            BridgeEventBus.incomingControl.collect { event ->
                when (event) {
                    IncomingControlEvent.MirrorRequest -> startOrResumeStreaming()
                    IncomingControlEvent.MirrorPause -> pauseToStandby()
                    IncomingControlEvent.MirrorStop -> endProjectionCompletely(null)
                    IncomingControlEvent.VideoKeyframeRequest -> requestSyncFrame()
                    is IncomingControlEvent.MirrorQuality -> applyQualityPreset(event.payload)
                    else -> Unit
                }
            }
        }
    }

    private fun applyQualityPreset(q: MirrorQualityPayload) {
        val newLong = q.maxLongSide.coerceIn(720, 1920)
        val newBitrate = q.bitrateBps.coerceIn(1_000_000, 10_000_000)
        val newFps = q.fps.coerceIn(24, 60)
        val resolutionChanged = newLong != targetMaxLongSide || newFps != targetFps
        targetMaxLongSide = newLong
        targetBitrateBps = newBitrate
        targetFps = newFps
        currentAdaptiveBitrate = newBitrate
        BridgeLog.i("Mirror", "Quality updated: ${newLong}px ${newBitrate / 1000}kbps @ ${newFps}fps")

        if (streamActive.get()) {
            if (resolutionChanged) {
                rebuildEncoderAsync()
            } else {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                }
                runCatching { codec?.setParameters(params) }
            }
        }
    }

    private fun endProjectionCompletely(reason: String?) {
        if (!isDestroying.compareAndSet(false, true)) return
        streamActive.set(false)
        streamGeneration.incrementAndGet()
        isStreaming = false
        isReadyInStandby = false
        cleanupEverything()
        BridgeEventBus.setMirrorState("idle", reason)
        stopSelf()
    }

    private fun tearDownEncoderAndSocket() {
        joinStreamingThreads()
        virtualDisplay?.setSurface(null)
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        videoChannel?.close()
        videoChannel = null
        backpressureQueue.clear()
        com.droidbridge.app.net.VideoMetricsTracker.resetActiveStream()
    }

    private fun joinStreamingThreads() {
        frameSignal.offer(Unit)
        val enc = encoderThread
        val snd = senderThread
        encoderThread = null
        senderThread = null
        if (enc != null && enc !== Thread.currentThread()) {
            runCatching { enc.join(400) }
        }
        if (snd != null && snd !== Thread.currentThread()) {
            runCatching { snd.join(400) }
        }
    }

    private fun cleanupEverything() {
        tearDownEncoderAndSocket()
        releaseWakeLock()
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidBridge:MirrorStream").apply {
            setReferenceCounted(false)
            runCatching { acquire(4 * 60 * 60 * 1000L) } // 4h safety cap
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private data class DisplayInfo(val width: Int, val height: Int, val dpi: Int, val rotationDegrees: Int)

    @Suppress("DEPRECATION")
    private fun getCurrentDisplayMetrics(): DisplayInfo {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rot = getDisplayRotationSurface()
        val rotDeg = when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val dpi = resources.displayMetrics.densityDpi
            DisplayInfo(bounds.width(), bounds.height(), dpi, rotDeg)
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            DisplayInfo(dm.widthPixels, dm.heightPixels, dm.densityDpi, rotDeg)
        }
    }

    @Suppress("DEPRECATION")
    private fun getDisplayRotationSurface(): Int {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.rotation ?: Surface.ROTATION_0
    }

    private fun createMirrorNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_MIRROR,
            "Screen Mirroring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while DroidBridge screen mirroring is active or ready in standby."
        }
        notificationManager.createNotificationChannel(ch)
    }

    private fun buildMirrorNotification(streaming: Boolean): Notification {
        val stopIntent = Intent(this, MirrorService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            3001,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_MIRROR)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(if (streaming) "DroidBridge is mirroring your screen" else "DroidBridge mirroring ready (standby)")
            .setContentText(
                if (streaming) "Low-latency encrypted stream active"
                else "Instant reopen enabled — tap End Capture to stop"
            )
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "End Capture", stopPi)
            .build()
    }

    private fun startAsForeground(streaming: Boolean) {
        val notif = buildMirrorNotification(streaming)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_MIRROR,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID_MIRROR, notif)
        }
    }

    private fun updateForegroundNotification(streaming: Boolean) {
        notificationManager.notify(NOTIF_ID_MIRROR, buildMirrorNotification(streaming))
    }

    companion object {
        private const val ACTION_START = "com.droidbridge.app.MIRROR_START"
        private const val ACTION_PAUSE = "com.droidbridge.app.MIRROR_PAUSE"
        private const val ACTION_STOP = "com.droidbridge.app.MIRROR_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_MIRROR = "droidbridge_mirror_active"
        private const val NOTIF_ID_MIRROR = 1003
        private const val MIN_ADAPTIVE_BITRATE_BPS = 1_500_000

        @Volatile
        var isReadyInStandby: Boolean = false
            private set

        @Volatile
        var isStreaming: Boolean = false
            private set

        fun startWithConsent(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MirrorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
