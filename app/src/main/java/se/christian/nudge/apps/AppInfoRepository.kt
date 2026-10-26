package se.christian.nudge.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(val packageName: String, val label: String, val icon: Bitmap)

/** Launchable apps with labels and icons. Loads off the main thread and caches per package. */
class AppInfoRepository(context: Context) {
    private val context = context.applicationContext
    private val packageManager = context.packageManager
    private val cache = ConcurrentHashMap<String, AppInfo>()
    private val iconSizePx = (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt()

    /** Launchable apps sorted by name, excluding this app and the default launcher. */
    suspend fun launchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val excluded = setOfNotNull(context.packageName, defaultLauncherPackage())
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivitiesCompat(launcherIntent)
            .map { it.activityInfo.packageName }
            .distinct()
            .filterNot { it in excluded }
            .mapNotNull { load(it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    suspend fun get(packageName: String): AppInfo? = withContext(Dispatchers.IO) { load(packageName) }

    /** Packages that are never tracked and count as "away". */
    fun ignoredPackages(): Set<String> =
        setOfNotNull(context.packageName, defaultLauncherPackage(), SYSTEM_UI_PACKAGE)

    fun defaultLauncherPackage(): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivityCompat(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        // With no default chosen, the resolver activity ("android") is returned; that's not a launcher.
        return resolved?.activityInfo?.packageName?.takeUnless { it == "android" }
    }

    private fun load(packageName: String): AppInfo? {
        cache[packageName]?.let { return it }
        return try {
            val appInfo = packageManager.getApplicationInfoCompat(packageName)
            AppInfo(
                packageName = packageName,
                label = appInfo.loadLabel(packageManager).toString(),
                icon = appInfo.loadIcon(packageManager).toBitmap(iconSizePx, iconSizePx),
            ).also { cache[packageName] = it }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private companion object {
        const val ICON_SIZE_DP = 48
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

private fun PackageManager.queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, 0)
    }

private fun PackageManager.resolveActivityCompat(intent: Intent, flags: Int): ResolveInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, flags)
    }

private fun PackageManager.getApplicationInfoCompat(packageName: String): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, 0)
    }
