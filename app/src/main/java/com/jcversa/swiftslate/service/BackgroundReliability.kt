package com.jcversa.swiftslate.service

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jcversa.swiftslate.R

/**
 * Best-effort recovery for the system-managed accessibility service.
 *
 * Android deliberately does not allow an app to silently enable its own accessibility service.
 * The system will reconnect an enabled service after boot, while this helper guides the user when
 * the service was disabled or the device is applying aggressive battery restrictions.
 */
object BackgroundReliability {
    private const val CHANNEL_ID = "service_reliability"
    private const val NOTIFICATION_ID = 9002

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { info -> info.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    fun isBatteryOptimizationExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Opens the app-specific settings page. It is safer than requesting a special exemption
     * permission and lets the user choose "Unrestricted" / "Allow background activity" using
     * the wording exposed by their Android version and manufacturer.
     */
    fun openBatterySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun refreshRecoveryNotification(context: Context) {
        val serviceEnabled = isAccessibilityServiceEnabled(context)
        val batteryExempt = isBatteryOptimizationExempt(context)
        if (serviceEnabled && batteryExempt) {
            cancelRecoveryNotification(context)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.service_reliability_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.service_reliability_channel_desc)
                }
            )
        }

        val accessibilityIntent = PendingIntent.getActivity(
            context,
            9003,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val batteryIntent = PendingIntent.getActivity(
            context,
            9004,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.service_reliability_notification_title)
        val message = when {
            !serviceEnabled -> context.getString(R.string.service_reliability_accessibility_message)
            !batteryExempt -> context.getString(R.string.service_reliability_battery_message)
            else -> return
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(if (!serviceEnabled) accessibilityIntent else batteryIntent)
            .setAutoCancel(serviceEnabled)
            .setOnlyAlertOnce(true)
            .setOngoing(!serviceEnabled)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!serviceEnabled) {
            builder.addAction(
                0,
                context.getString(R.string.service_reliability_open_accessibility),
                accessibilityIntent
            )
        }
        if (!batteryExempt) {
            builder.addAction(
                0,
                context.getString(R.string.service_reliability_open_battery),
                batteryIntent
            )
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelRecoveryNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID)
    }
}
