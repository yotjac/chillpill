package com.chillpill.app

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * Apps that can appear in the blacklist picker and that we may intercept.
 * Excludes only: our app and launchers (HOME). Includes system apps used as applications (e.g. YouTube).
 */
object BlockableApps {

    /** Packages that are always considered blockable if installed (often hidden from generic LAUNCHER query on Android 11+). */
    private val alwaysBlockableIfInstalled = setOf(
        "app.revanced.android.youtube",   // ReVanced YouTube
        "com.google.android.youtube"      // Official YouTube
    )

    /**
     * Package names that are launchable and not us, not a launcher.
     * Includes system apps (e.g. YouTube). Only apps in the user's blacklist are blocked.
     */
    fun getBlockablePackageNames(context: Context): Set<String> {
        val pm = context.packageManager
        val ourPkg = context.packageName
        val launcherPackages = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0
        ).map { it.activityInfo.packageName }.toSet()
        val launchablePackages = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        ).map { it.activityInfo.packageName }.toMutableSet()
        launchablePackages -= ourPkg
        launchablePackages -= launcherPackages
        // Include known packages that may be filtered from LAUNCHER query (e.g. ReVanced YouTube)
        for (pkg in alwaysBlockableIfInstalled) {
            if (pkg != ourPkg && pkg !in launcherPackages) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    launchablePackages.add(pkg)
                } catch (_: PackageManager.NameNotFoundException) { }
            }
        }
        return launchablePackages.toSet()
    }

    /**
     * Same set as blockable package names, but as AppItem list for the picker UI.
     * Sorted by usage frequency (most used first) when usage stats permission is granted.
     */
    fun getBlockableAppItems(context: Context): List<AppItem> {
        val pm = context.packageManager
        val items = getBlockablePackageNames(context).mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                AppItem(pkg, info.loadLabel(pm).toString(), info.loadIcon(pm))
            } catch (_: Exception) {
                null
            }
        }
        val usageOrder = getUsageOrder(context)
        return if (usageOrder.isEmpty()) items.sortedBy { it.label.lowercase() }
        else items.sortedWith(
            compareByDescending<AppItem> { usageOrder[it.packageName] ?: 0L }
                .thenBy { it.label.lowercase() }
        )
    }

    /** Returns map of package -> total time in foreground (ms) when usage access is granted. */
    private fun getUsageOrder(context: Context): Map<String, Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyMap()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return emptyMap()
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        if (mode != AppOpsManager.MODE_ALLOWED) return emptyMap()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 7 * 24 * 60 * 60 * 1000L // 7 days
        val stats: List<UsageStats> = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            beginTime,
            endTime
        ) ?: return emptyMap()
        return stats
            .filter { it.totalTimeInForeground > 0 }
            .groupBy { it.packageName }
            .mapValues { (_, list) -> list.sumOf { it.totalTimeInForeground } }
    }
}
