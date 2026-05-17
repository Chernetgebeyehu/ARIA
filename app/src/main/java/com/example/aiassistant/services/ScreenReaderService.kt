package com.example.aiassistant.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenReaderService : AccessibilityService() {

    companion object {
        var lastCapturedText: String = ""
            private set
        var lastPackageName: String = ""
            private set
        var isServiceActive: Boolean = false
            private set

        fun getScreenContent(): String {
            return if (isServiceActive && lastCapturedText.isNotBlank()) {
                "App: $lastPackageName\n\nContent:\n$lastCapturedText"
            } else ""
        }
    }

    override fun onServiceConnected() {
        isServiceActive = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        // 🔥 1. FILTER FIRST (MOST IMPORTANT)
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        // 🔥 2. GET SOURCE (prefer event.source)
        val source = event.source ?: rootInActiveWindow ?: return

        // 🔥 3. IGNORE OUR OWN APP
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        lastPackageName = pkg

        // 🔥 4. EXTRACT TEXT
        val builder = StringBuilder()
        extractText(source, builder, 0)

        val text = builder.toString().trim()
        if (text.length > 15) {
            lastCapturedText = text.take(5000)
        }
    }

    private fun extractText(
        node: AccessibilityNodeInfo?,
        builder: StringBuilder,
        depth: Int
    ) {
        if (node == null || depth > 12) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (!text.isNullOrBlank()) builder.appendLine(text)
        else if (!desc.isNullOrBlank()) builder.appendLine(desc)

        for (i in 0 until node.childCount) {
            extractText(node.getChild(i), builder, depth + 1)
        }
    }

    override fun onInterrupt() {
        isServiceActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceActive = false
    }
}