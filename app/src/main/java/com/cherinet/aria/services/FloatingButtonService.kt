package com.cherinet.aria.services

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.view.animation.OvershootInterpolator
import com.cherinet.aria.MainActivity
import com.cherinet.aria.R
import com.cherinet.aria.ui.ChatActivity
import kotlin.math.abs

class FloatingButtonService : Service() {

    companion object {
        const val CHANNEL_ID = "aria_foreground_channel"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        isRunning = true

        startForeground(NOTIFICATION_ID, createNotification())

        // 🔥 SAFETY CHECK (CRITICAL FIX)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (floatingView == null) {
            setupFloatingButton()
        }

        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false

        try {
            floatingView?.let {
                windowManager.removeView(it)
            }
        } catch (_: Exception) {}

        floatingView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ARIA Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ARIA is active")
            .setContentText("Tap floating button to chat")
            .setSmallIcon(R.drawable.ic_assistant)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val view = LayoutInflater.from(this)
            .inflate(R.layout.layout_floating_button, null)

        floatingView = view

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            stopSelf()
            return
        }

        view.scaleX = 0f
        view.scaleY = 0f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setInterpolator(OvershootInterpolator())
            .start()

        setupTouch(view)
    }

    private fun setupTouch(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()

                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {}

                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()

                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)

                    if (dx < 10 && dy < 10) {
                        openChat()
                    } else {
                        snapToEdge(view)
                    }

                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge(view: View) {
        val screenWidth = resources.displayMetrics.widthPixels
        val middle = screenWidth / 2

        val targetX = if (params.x < middle) 0 else screenWidth - view.width
        val startX = params.x

        view.animate()
            .setDuration(200)
            .setUpdateListener {
                val fraction = it.animatedFraction
                params.x = (startX + (targetX - startX) * fraction).toInt()

                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
            .start()
    }

    private fun openChat() {
        val intent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }
}