package com.example.aiassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.aiassistant.databinding.ActivityMainBinding
import com.example.aiassistant.service.FloatingButtonService
import com.example.aiassistant.service.ScreenReaderService
import com.example.aiassistant.service.WakeWordService
import com.example.aiassistant.ui.ChatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val OVERLAY_PERMISSION_CODE = 1000
        private const val RUNTIME_PERMISSION_CODE = 1001
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedApiKey()
        setupButtons()
        updateStatus()

        // FIX: Only start WakeWordService if RECORD_AUDIO permission is already granted.
        // Starting it unconditionally causes a crash on first install before permissions
        // are granted, because the foreground service requires the microphone permission.
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startWakeWordService()
        }
    }

    private fun startWakeWordService() {
        if (!WakeWordService.isRunning) {
            val intent = Intent(this, WakeWordService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Wake word will start after permissions granted",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            updateStatus()
        }
    }

    private fun loadSavedApiKey() {
        val prefs = getSharedPreferences(
            "aria_prefs", MODE_PRIVATE
        )
        val savedKey = prefs.getString("api_key", "") ?: ""
        if (savedKey.isNotBlank()) {
            binding.etApiKey.setText(savedKey)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun setupButtons() {

        // Save API Key
        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(
                    this,
                    "Enter an API key",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            getSharedPreferences("aria_prefs", MODE_PRIVATE)
                .edit()
                .putString("api_key", key)
                .apply()
            Toast.makeText(
                this,
                "✅ API key saved!",
                Toast.LENGTH_SHORT
            ).show()
            updateStatus()
        }

        // Overlay Permission
        binding.btnOverlayPerm.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(
                    intent,
                    OVERLAY_PERMISSION_CODE
                )
            } else {
                Toast.makeText(
                    this,
                    "✅ Already granted",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Accessibility Permission
        binding.btnAccessibilityPerm.setOnClickListener {
            if (!ScreenReaderService.isServiceActive) {
                Toast.makeText(
                    this,
                    "Find 'ARIA' in the list and enable it",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(
                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                    )
                )
            } else {
                Toast.makeText(
                    this,
                    "✅ Already enabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Runtime Permissions
        binding.btnRuntimePerms.setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            ActivityCompat.requestPermissions(
                this,
                perms.toTypedArray(),
                RUNTIME_PERMISSION_CODE
            )
        }

        // Start ARIA
        binding.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "⚠️ Grant overlay permission first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val apiKey = getSharedPreferences(
                "aria_prefs", MODE_PRIVATE
            ).getString("api_key", "") ?: ""

            if (apiKey.isBlank()) {
                Toast.makeText(
                    this,
                    "⚠️ Enter your API key first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            startAriaService()

            Toast.makeText(
                this,
                "🚀 ARIA is now active!",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(this, ChatActivity::class.java)
            )
        }

        // Stop ARIA
        binding.btnStop.setOnClickListener {
            stopService(
                Intent(this, FloatingButtonService::class.java)
            )
            stopService(
                Intent(this, WakeWordService::class.java)
            )
            Toast.makeText(
                this,
                "ARIA stopped",
                Toast.LENGTH_SHORT
            ).show()
            updateStatus()
        }
    }

    private fun startAriaService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(
                    Intent(this, FloatingButtonService::class.java)
                )
            } else {
                startService(
                    Intent(this, FloatingButtonService::class.java)
                )
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Error starting floating button: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }

        // FIX: Only start WakeWordService if RECORD_AUDIO is granted
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startWakeWordService()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateStatus() {
        if (!::binding.isInitialized) return

        val apiKeySet = getSharedPreferences(
            "aria_prefs", MODE_PRIVATE
        ).getString("api_key", "")?.isNotBlank() == true

        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = ScreenReaderService.isServiceActive
        val serviceRunning = FloatingButtonService.isRunning
        val wakeWordRunning = WakeWordService.isRunning

        val status = buildString {
            appendLine("Status Dashboard:")
            appendLine(
                "  API Key:       " +
                        if (apiKeySet) "✅ Set" else "❌ Not set"
            )
            appendLine(
                "  Overlay:       " +
                        if (overlayOk) "✅ Granted" else "❌ Needed"
            )
            appendLine(
                "  Accessibility: " +
                        if (accessibilityOk) "✅ Active" else "⚠️ Disabled"
            )
            appendLine(
                "  Service:       " +
                        if (serviceRunning) "🟢 Running" else "🔴 Stopped"
            )
            appendLine(
                "  Wake Word:     " +
                        if (wakeWordRunning) "🟢 Active" else "🔴 Off"
            )
        }

        binding.tvStatus.text = status
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )
        if (requestCode == RUNTIME_PERMISSION_CODE) {
            val allGranted = grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }
            Toast.makeText(
                this,
                if (allGranted) "✅ All permissions granted!"
                else "Some permissions denied",
                Toast.LENGTH_SHORT
            ).show()
            updateStatus()

            // FIX: Start wake word service now that permissions may be granted
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startWakeWordService()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Deprecated("Use Activity Result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_CODE) {
            updateStatus()
        }
    }
}