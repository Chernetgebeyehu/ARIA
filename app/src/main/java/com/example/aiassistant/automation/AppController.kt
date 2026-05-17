package com.example.aiassistant.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

class AppController(private val context: Context) {

    data class AppInfo(
        val name: String,
        val packageName: String,
        val intent: Intent
    )

    /**
     * MAIN FUNCTION
     */
    fun openApp(appName: String): Boolean {
        val apps = getLaunchableApps()
        val match = findBestMatch(appName, apps)

        return if (match != null) {
            context.startActivity(match.intent)
            true
        } else {
            false
        }
    }

    /**
     * STEP 1: GET ONLY LAUNCHABLE APPS (CRITICAL FIX)
     */
    private fun getLaunchableApps(): List<AppInfo> {
        val pm = context.packageManager

        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = pm.queryIntentActivities(intent, 0)

        return resolveInfos.mapNotNull { resolveInfo ->
            try {
                val label = resolveInfo.loadLabel(pm).toString()
                val packageName = resolveInfo.activityInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(packageName)

                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    AppInfo(label, packageName, launchIntent)
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * STEP 2: SMART MATCHING (THIS FIXES YOUR ISSUE)
     */
    private fun findBestMatch(
        query: String,
        apps: List<AppInfo>
    ): AppInfo? {

        val normalizedQuery = normalize(query)

        // 1. EXACT MATCH
        apps.firstOrNull {
            normalize(it.name) == normalizedQuery
        }?.let { return it }

        // 2. STARTS WITH
        apps.firstOrNull {
            normalize(it.name).startsWith(normalizedQuery)
        }?.let { return it }

        // 3. CONTAINS
        apps.firstOrNull {
            normalize(it.name).contains(normalizedQuery)
        }?.let { return it }

        // 4. PACKAGE NAME MATCH
        apps.firstOrNull {
            it.packageName.lowercase().contains(normalizedQuery)
        }?.let { return it }

        // 5. FUZZY MATCH (fallback)
        val scored = apps.map { app ->
            val score = similarity(normalizedQuery, normalize(app.name))
            app to score
        }

        return scored
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0.4 }   // threshold
            ?.first
    }

    /**
     * NORMALIZATION (important)
     */
    private fun normalize(text: String): String {
        return text.lowercase()
            .replace("[^a-z0-9]".toRegex(), "")
            .trim()
    }

    /**
     * SIMPLE SIMILARITY (LEVENSHTEIN-BASED)
     */
    private fun similarity(a: String, b: String): Double {
        val longer = if (a.length > b.length) a else b
        val shorter = if (a.length > b.length) b else a

        if (longer.isEmpty()) return 1.0

        val distance = levenshtein(longer, shorter)
        return (longer.length - distance).toDouble() / longer.length
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val costs = IntArray(s2.length + 1) { it }

        for (i in 1..s1.length) {
            var last = i
            for (j in 1..s2.length) {
                val newVal = if (s1[i - 1] == s2[j - 1]) {
                    costs[j - 1]
                } else {
                    1 + minOf(costs[j - 1], last, costs[j])
                }
                costs[j - 1] = last
                last = newVal
            }
            costs[s2.length] = last
        }

        return costs[s2.length]
    }

    // ==========================
    // OTHER FUNCTIONS (UNCHANGED)
    // ==========================

    fun makeCall(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: SecurityException) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
        }
    }

    fun sendSms(phoneNumber: String, message: String = "") {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$phoneNumber")
            putExtra("sms_body", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openUrl(url: String) {
        val fullUrl =
            if (!url.startsWith("http")) "https://$url" else url

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(fullUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openSettings() {
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun search(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}