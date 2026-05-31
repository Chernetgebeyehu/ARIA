package com.cherinet.aria.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cherinet.aria.databinding.ActivityApiKeyBinding

class ApiKeyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiKeyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApiKeyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("aria_prefs", Context.MODE_PRIVATE)

        // Load existing key if exists
        binding.etApiKey.setText(prefs.getString("api_key", ""))

        binding.btnSave.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()

            if (key.isBlank()) {
                Toast.makeText(this, "Paste API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putString("api_key", key).apply()

            Toast.makeText(this, "API Key Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}