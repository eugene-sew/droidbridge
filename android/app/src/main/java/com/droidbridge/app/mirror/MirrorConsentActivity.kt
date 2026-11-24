package com.droidbridge.app.mirror

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.droidbridge.app.net.BridgeConnectionService
import com.droidbridge.app.net.BridgeEventBus
import com.droidbridge.app.net.BridgeLog
import com.droidbridge.app.net.IncomingControlEvent

/**
 * Transparent activity that requests MediaProjection consent locked directly to the
 * ENTIRE screen on Android 14+ (MediaProjectionConfig.createConfigForDefaultDisplay),
 * eliminating the "Single app vs Entire screen" picker.
 *
 * If MirrorService is already armed in standby, resumes immediately with zero prompt.
 */
class MirrorConsentActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            BridgeLog.i("Mirror", "Entire-screen MediaProjection consent granted")
            MirrorService.startWithConsent(this, result.resultCode, result.data!!)
        } else {
            BridgeLog.i("Mirror", "MediaProjection consent declined")
            BridgeEventBus.setMirrorState("idle", "Screen capture permission was cancelled")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dismiss any pending mirror prompt notification
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(BridgeConnectionService.NOTIF_ID_MIRROR_PROMPT)

        // If MirrorService already holds an active MediaProjection in standby, resume seamlessly!
        if (MirrorService.isReadyInStandby || MirrorService.isStreaming) {
            BridgeLog.i("Mirror", "MirrorService already armed; resuming without consent prompt")
            BridgeEventBus.emitIncoming(IncomingControlEvent.MirrorRequest)
            finish()
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        BridgeEventBus.setMirrorState("awaiting_consent")

        // Android 14+ (API 34+): Force entire default display so the user is never asked
        // to choose between "Share a single app" and "Share entire screen".
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
    }
}
