package com.cherinet.aria.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File

class SecurityGuard(private val context: Context) {

    enum class RiskLevel { LOW, MEDIUM, HIGH }

    data class SecurityReport(
        val isRooted: Boolean,
        val isDebuggable: Boolean,
        val isEmulator: Boolean,
        val riskLevel: RiskLevel,
        val warnings: List<String>
    )

    fun runSecurityCheck(): SecurityReport {
        val warnings = mutableListOf<String>()
        val rooted = detectRoot()
        val debuggable = detectDebugger()
        val emulator = detectEmulator()

        if (rooted) warnings.add(
            "This device appears to be rooted. Encrypted storage may be less secure.")
        if (debuggable && !isDebugBuild()) warnings.add("App is running in debug mode.")

        val risk = when {
            rooted && debuggable -> RiskLevel.HIGH
            rooted               -> RiskLevel.MEDIUM
            debuggable           -> RiskLevel.MEDIUM
            else                 -> RiskLevel.LOW
        }
        Log.i("SecurityGuard", "rooted=$rooted debuggable=$debuggable emulator=$emulator risk=$risk")
        return SecurityReport(rooted, debuggable, emulator, risk, warnings)
    }

    private fun detectRoot(): Boolean {
        var score = 0
        listOf("/system/bin/su","/system/xbin/su","/sbin/su","/su/bin/su")
            .forEach { if (File(it).exists()) score++ }
        if (Build.TAGS?.contains("test-keys") == true) score++
        if (File("/sbin/.magisk").exists() || File("/data/adb/magisk").exists()) score++
        return score >= 2
    }

    private fun detectDebugger(): Boolean {
        if (isDebugBuild()) return false
        return android.os.Debug.isDebuggerConnected()
    }

    private fun isDebugBuild(): Boolean = try {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    } catch (e: Exception) { false }

    private fun detectEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK") ||
                Build.MANUFACTURER.contains("Genymotion")
}