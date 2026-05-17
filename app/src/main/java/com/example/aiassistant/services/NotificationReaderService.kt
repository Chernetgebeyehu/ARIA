package com.example.aiassistant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.aiassistant.ai.SecurityAnalyzer

class NotificationReaderService : NotificationListenerService() {

    companion object {
        // Store recent notifications for ARIA to read
        val recentNotifications =
            mutableListOf<String>()
        private const val MAX_NOTIFICATIONS = 20
        var isActive = false
            private set
    }

    private val securityAnalyzer = SecurityAnalyzer()

    override fun onListenerConnected() {
        super.onListenerConnected()
        isActive = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isActive = false
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification?
    ) {
        sbn ?: return

        // Skip our own app notifications
        if (sbn.packageName ==
            "com.example.aiassistant"
        ) return

        val extras = sbn.notification.extras
        val title = extras.getString(
            android.app.Notification.EXTRA_TITLE
        ) ?: ""
        val text = extras.getCharSequence(
            android.app.Notification.EXTRA_TEXT
        )?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val notifText = "From: ${sbn.packageName}\n" +
                "Title: $title\n" +
                "Message: $text"

        // Store it
        recentNotifications.add(0, notifText)
        if (recentNotifications.size > MAX_NOTIFICATIONS) {
            recentNotifications.removeAt(
                recentNotifications.size - 1
            )
        }

        // AUTO SCAN for scams
        val combined = "$title $text"
        val result = securityAnalyzer.analyzeText(combined)

        if (!result.isSafe) {
            // Show a system notification warning
            showSecurityAlert(title)
        }
    }

    private fun showSecurityAlert(
        originalTitle: String
    ) {
        // Create warning notification
        val notifManager = getSystemService(
            NOTIFICATION_SERVICE
        ) as android.app.NotificationManager

        val channel = android.app.NotificationChannel(
            "aria_security",
            "ARIA Security Alerts",
            android.app.NotificationManager.IMPORTANCE_HIGH
        )
        notifManager.createNotificationChannel(channel)

        val notification =
            android.app.Notification.Builder(
                this, "aria_security"
            )
                .setContentTitle(
                    "🚨 ARIA Security Alert"
                )
                .setContentText(
                    "Suspicious notification from: " +
                            "$originalTitle — Tap ARIA to check"
                )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_alert
                )
                .setAutoCancel(true)
                .build()

        notifManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }
}