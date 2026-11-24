package com.droidbridge.app.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidbridge.app.pairing.KeyStoreManager

/**
 * Restarts BridgeConnectionService after device reboot if a paired Mac is configured.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val keyStore = KeyStoreManager(context)
            if (keyStore.isPaired()) {
                BridgeLog.i("Boot", "Device booted/updated while paired; starting BridgeConnectionService")
                runCatching { BridgeConnectionService.start(context) }
            }
        }
    }
}
