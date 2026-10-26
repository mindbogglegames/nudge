package se.christian.nudge

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.net.toUri

data class PermissionState(
    val notifications: Boolean = true,
    val usageAccess: Boolean = true,
    val overlay: Boolean = true,
    val battery: Boolean = true,
) {
    val allGranted get() = notifications && usageAccess && overlay && battery
}

object Permissions {
    fun state(context: Context) = PermissionState(
        notifications = hasNotifications(context),
        usageAccess = hasUsageAccess(context),
        overlay = Settings.canDrawOverlays(context),
        battery = isIgnoringBatteryOptimizations(context),
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        // unsafeCheckOpNoThrow is deprecated from API 36 in favor of checkOpNoThrow (which was deprecated before 36).
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun hasNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun usageAccessIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun overlayIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())

    fun notificationSettingsIntent(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    // Sideloaded personal app: the Play policy this lint check guards doesn't apply, and the spec asks for it.
    @SuppressLint("BatteryLife")
    fun batteryIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
}
