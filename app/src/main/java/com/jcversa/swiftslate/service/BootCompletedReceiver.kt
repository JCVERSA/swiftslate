package com.jcversa.swiftslate.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs after boot and after an app update. It does not try to start an accessibility service
 * directly: Android owns that lifecycle and only reconnects it when the user has enabled it.
 * Instead, it gives the user an actionable recovery notification when a setting needs attention.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                BackgroundReliability.refreshRecoveryNotification(context.applicationContext)
        }
    }
}
