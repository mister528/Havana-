package com.luxury.mobile.launcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Disabled by default — see AndroidManifest. The receiver exists so that a
 * future iteration can opt the launcher into auto-resume after device reboot
 * without changing the manifest. When enabled, it relaunches
 * [DownloadService] which then picks up any persisted `.part` files.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        DownloadService.start(context)
    }
}
