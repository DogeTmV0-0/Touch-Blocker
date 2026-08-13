package com.example.touchblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val repository = ZoneRepository(context)
        val wasEnabled = repository.isBlockingEnabled()
        val hasPermission = Settings.canDrawOverlays(context)

        if (wasEnabled && hasPermission) {
            OverlayService.start(context)
        }
    }
}
