package com.example.touchblocker

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Foreground service that owns one transparent, touch-eating View per
 * enabled zone. Each view is a real WindowManager overlay window
 * (TYPE_APPLICATION_OVERLAY) positioned exactly over its zone's rectangle.
 *
 * The "ignore the tap" behavior is just this: the overlay view's
 * OnTouchListener returns true (consumed) and does nothing else. Because
 * the overlay sits above every other app in the window stack, the touch
 * never reaches whatever app is visually underneath.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: ZoneRepository

    // zoneId -> the live overlay View currently on screen for it
    private val activeOverlays = mutableMapOf<String, View>()

    // The draggable floating pause/resume button, and the layout params
    // WindowManager needs to reposition it as the user drags.
    private var controlButtonView: ImageView? = null
    private var controlButtonParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repository = ZoneRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> refreshZones()
            ACTION_TOGGLE_PAUSE -> togglePause()
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (activeOverlays.isEmpty()) refreshZones()
        if (controlButtonView == null) addControlButton()
        return START_STICKY
    }

    /** Single source of truth for flipping paused state, called from the notification, the floating button, or the app UI's service call. */
    private fun togglePause() {
        repository.setPaused(!repository.isPaused())
        refreshZones()
        updateNotification()
        updateControlButtonIcon()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes which zones apply and where; redraw everything.
        refreshZones()
        clampControlButtonToScreen()
    }

    /**
     * Removes every existing overlay and re-adds the ones that match the current
     * orientation — unless blocking is paused, in which case it just stays empty.
     */
    private fun refreshZones() {
        clearAllOverlays()

        if (repository.isPaused()) return

        if (!Settings.canDrawOverlays(this)) {
            // Permission was revoked mid-use; stop gracefully instead of crashing.
            stopSelf()
            return
        }

        val currentOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Zone.Orientation.LANDSCAPE
        } else {
            Zone.Orientation.PORTRAIT
        }

        repository.getZones()
            .filter { it.enabled && it.orientation == currentOrientation }
            .forEach { addOverlayForZone(it) }
    }

    private fun addOverlayForZone(zone: Zone) {
        val params = WindowManager.LayoutParams(
            zone.width,
            zone.height,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = zone.x
            y = zone.y
        }

        val blockerView = View(this).apply {
            // This is the entire "make taps not count" mechanism:
            // consume the event (return true) and do nothing with it.
            setOnTouchListener { _, _ -> true }

            // Debug aid only: paint the zone so you can see exactly where it
            // sits on screen. Purely cosmetic — has no effect on the actual
            // blocking behavior above, which works identically either way.
            if (repository.isDebugVisibleZones()) {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#33FF0000"))
                    setStroke(dpToPx(2), Color.parseColor("#FF0000"))
                }
            }
        }

        try {
            windowManager.addView(blockerView, params)
            activeOverlays[zone.id] = blockerView
        } catch (e: WindowManager.BadTokenException) {
            // Overlay permission problem on this device/OEM; skip this zone.
        }
    }

    private fun clearAllOverlays() {
        activeOverlays.values.forEach { view ->
            try {
                windowManager.removeView(view)
            } catch (e: IllegalArgumentException) {
                // Already removed; ignore.
            }
        }
        activeOverlays.clear()
    }

    /**
     * A small, draggable floating button — always on top, independent of any
     * dead zone — so pause/resume is reachable without pulling down the
     * notification shade. Distinguishes a tap (toggle pause) from a drag
     * (reposition) by touch-slop distance, same technique apps like
     * Messenger's chat heads use.
     */
    private fun addControlButton() {
        if (!Settings.canDrawOverlays(this)) return

        val size = dpToPx(56)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val saved = repository.getControlButtonPosition()

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved?.first ?: (resources.displayMetrics.widthPixels - size - dpToPx(16))
            y = saved?.second ?: dpToPx(120)
        }
        controlButtonParams = params

        val button = ImageView(this).apply {
            setImageResource(iconFor(repository.isPaused()))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D91565C0"))
            }
            val pad = dpToPx(14)
            setPadding(pad, pad, pad, pad)
            setColorFilter(Color.WHITE)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var isDragging = false

        button.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        repository.setControlButtonPosition(params.x, params.y)
                    } else {
                        // A genuine tap (didn't move past touch slop): toggle pause.
                        togglePause()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(button, params)
            controlButtonView = button
        } catch (e: WindowManager.BadTokenException) {
            // Overlay permission problem on this device/OEM; skip the button.
        }
    }

    private fun removeControlButton() {
        controlButtonView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                // Already removed; ignore.
            }
        }
        controlButtonView = null
        controlButtonParams = null
    }

    private fun updateControlButtonIcon() {
        controlButtonView?.setImageResource(iconFor(repository.isPaused()))
    }

    /** Keeps the button on screen after a rotation instead of drifting off the new, smaller edge. */
    private fun clampControlButtonToScreen() {
        val params = controlButtonParams ?: return
        val view = controlButtonView ?: return
        val metrics = resources.displayMetrics
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - params.height).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(view, params)
            repository.setControlButtonPosition(params.x, params.y)
        } catch (e: IllegalArgumentException) {
            // View not attached; ignore.
        }
    }

    private fun iconFor(paused: Boolean): Int =
        if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun overlayWindowType(): Int =
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun buildNotification(): Notification {
        val disableIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val disablePendingIntent = PendingIntent.getService(
            this, 0, disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE_PAUSE }
        val pausePendingIntent = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val paused = repository.isPaused()
        val pauseLabel = getString(if (paused) R.string.action_resume else R.string.action_pause)
        val statusText = getString(if (paused) R.string.notif_text_paused else R.string.notif_text)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(openPendingIntent)
            .addAction(0, pauseLabel, pausePendingIntent)
            .addAction(0, getString(R.string.action_disable), disablePendingIntent)
            .setOngoing(true)
            .build()
    }

    /** Rebuilds and re-posts the notification, e.g. after the pause state changes. */
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        clearAllOverlays()
        removeControlButton()
        repository.setBlockingEnabled(false)
        repository.setPaused(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.example.touchblocker.ACTION_STOP"
        const val ACTION_REFRESH = "com.example.touchblocker.ACTION_REFRESH"
        const val ACTION_TOGGLE_PAUSE = "com.example.touchblocker.ACTION_TOGGLE_PAUSE"
        private const val CHANNEL_ID = "touch_blocker_channel"
        private const val NOTIFICATION_ID = 1001

        /** Call this after zones are added/edited/removed while the service is running. */
        fun refresh(context: android.content.Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_REFRESH }
            context.startForegroundService(intent)
        }

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        /** Toggles pause state: overlays removed/restored, but the service and your saved zones stay intact. */
        fun togglePause(context: android.content.Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_TOGGLE_PAUSE }
            context.startForegroundService(intent)
        }
    }
}
