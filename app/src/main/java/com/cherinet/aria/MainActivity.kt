package com.cherinet.aria

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.cherinet.aria.ai.AiManager
import com.cherinet.aria.databinding.ActivityMainBinding
import com.cherinet.aria.security.SecurityGuard
import com.cherinet.aria.services.FloatingButtonService
import com.cherinet.aria.services.ScreenReaderService
import com.cherinet.aria.services.WakeWordService
import com.cherinet.aria.ui.ChatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val OVERLAY_PERMISSION_CODE = 1000
        private const val RUNTIME_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Phase 7: Security check
        val report = SecurityGuard(this).runSecurityCheck()
        if (report.riskLevel == SecurityGuard.RiskLevel.HIGH && report.warnings.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Security Warning")
                .setMessage(report.warnings.joinToString("\n\n"))
                .setPositiveButton("I Understand") { d, _ -> d.dismiss() }
                .setCancelable(true).show()
        }

        setupButtons()
        updateStatus()

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startWakeWordService()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) updateStatus()
    }

    private fun setupButtons() {
        binding.btnSaveKey.setOnClickListener { /* hidden — no-op */ }

        binding.btnOverlayPerm.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")),
                    OVERLAY_PERMISSION_CODE
                )
            } else Toast.makeText(this, "✅ Already granted", Toast.LENGTH_SHORT).show()
        }

        binding.btnAccessibilityPerm.setOnClickListener {
            if (!ScreenReaderService.isServiceActive) {
                Toast.makeText(this, "Find 'ARIA' and enable it", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else Toast.makeText(this, "✅ Already enabled", Toast.LENGTH_SHORT).show()
        }

        binding.btnRuntimePerms.setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), RUNTIME_PERMISSION_CODE)
        }

        binding.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "⚠️ Grant overlay permission first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startAriaService()
            Toast.makeText(this, "🚀 ARIA is now active!", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, ChatActivity::class.java))
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, FloatingButtonService::class.java))
            stopService(Intent(this, WakeWordService::class.java))
            Toast.makeText(this, "ARIA stopped", Toast.LENGTH_SHORT).show()
            updateStatus()
        }
    }

    private fun startAriaService() {
        try {
            val intent = Intent(this, FloatingButtonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startWakeWordService()
    }

    private fun startWakeWordService() {
        if (!WakeWordService.isRunning) {
            try {
                val intent = Intent(this, WakeWordService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(intent) else startService(intent)
            } catch (_: Exception) {}
        }
    }

    private fun updateStatus() {
        if (!::binding.isInitialized) return
        val online = AiManager.getInstance(this).isInternetAvailable()
        binding.tvStatus.text = buildString {
            appendLine("Status Dashboard:")
            appendLine("  Internet:      ${if (online) "🌐 Online" else "📵 Offline"}")
            appendLine("  Overlay:       ${if (Settings.canDrawOverlays(this@MainActivity)) "✅ Granted" else "❌ Needed"}")
            appendLine("  Accessibility: ${if (ScreenReaderService.isServiceActive) "✅ Active" else "⚠️ Disabled"}")
            appendLine("  Service:       ${if (FloatingButtonService.isRunning) "🟢 Running" else "🔴 Stopped"}")
            append("  Wake Word:     ${if (WakeWordService.isRunning) "🟢 Active" else "🔴 Off"}")
        }
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_CODE) updateStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RUNTIME_PERMISSION_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(this,
                if (allGranted) "✅ All permissions granted!" else "Some permissions denied",
                Toast.LENGTH_SHORT).show()
            updateStatus()
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startWakeWordService()
        }
    }
}