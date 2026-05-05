package com.streamshield.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpDisplay
import kotlin.math.abs
import kotlin.math.min

class StreamService : Service(), ConnectCheckerRtmp {

    companion object {
        const val EXTRA_RTMP_URL        = "extra_rtmp_url"
        const val EXTRA_WIDTH           = "extra_width"
        const val EXTRA_HEIGHT          = "extra_height"
        const val EXTRA_FPS             = "extra_fps"
        const val EXTRA_BITRATE_KBPS    = "extra_bitrate_kbps"
        const val EXTRA_RESULT_CODE     = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val ACTION_STOP           = "action_stop_stream"

        private const val NOTIF_ID      = 1001
        private const val CHANNEL_ID    = "stream_service_channel"

        private const val LONG_PRESS_MIC_MS    = 400L
        private const val LONG_PRESS_COLOR_MS  = 500L
        private const val SWIPE_MIN_DISTANCE   = 30f
        private const val DOUBLE_TAP_TIMEOUT   = 300L

        private val OPACITY_CYCLE = intArrayOf(255, 180, 120, 60, 0)
        private val COLOR_CYCLE   = intArrayOf(
            Color.argb(255, 0,  0,  0),
            Color.argb(255, 40, 40, 40),
            Color.argb(255, 0,  0,  60),
            Color.argb(255, 40, 0,  60)
        )
    }

    private lateinit var windowManager: WindowManager
    private var notificationManager: NotificationManager? = null

    private var rtmpDisplay: RtmpDisplay? = null
    private var maskView: View? = null
    private var controlView: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var rtmpUrl     = ""
    private var videoWidth  = 1280
    private var videoHeight = 720
    private var videoFps    = 30
    private var bitrateKbps = 3000

    private var reconnectDelayMs = 2_000L
    private var reconnectRunnable: Runnable? = null
    private var viewerRunnable: Runnable? = null
    private var viewerCount = 0

    private var maskOpacityIndex = 0
    private var maskColorIndex   = 0
    private var maskVisible      = true
    private var isMuted          = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager      = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            tearDown(); stopSelf(); return START_NOT_STICKY
        }

        val notification = buildNotification("Connecting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        rtmpUrl     = intent?.getStringExtra(EXTRA_RTMP_URL)     ?: return START_NOT_STICKY
        videoWidth  = intent.getIntExtra(EXTRA_WIDTH,  1280)
        videoHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
        videoFps    = intent.getIntExtra(EXTRA_FPS,    30)
        bitrateKbps = intent.getIntExtra(EXTRA_BITRATE_KBPS, 3000)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }

        if (data == null) { stopSelf(); return START_NOT_STICKY }

        initRtmpDisplay()
        rtmpDisplay?.setIntentResult(resultCode, data)

        initOverlays()
        startStream()
        startViewerSimulation()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        tearDown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Tear-down ─────────────────────────────────────────────────────────────

    private fun tearDown() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        viewerRunnable?.let   { mainHandler.removeCallbacks(it)  }

        try {
            if (rtmpDisplay?.isStreaming == true) rtmpDisplay?.stopStream()
        } catch (_: Exception) {}

        mainHandler.post {
            try { maskView?.let    { windowManager.removeViewImmediate(it) } } catch (_: Exception) {}
            try { controlView?.let { windowManager.removeViewImmediate(it) } } catch (_: Exception) {}
            maskView    = null
            controlView = null
        }
    }

    // ── RTMP ──────────────────────────────────────────────────────────────────

    private fun initRtmpDisplay() {
        rtmpDisplay = RtmpDisplay(applicationContext, true, this)
    }

    private fun startStream() {
        val display = rtmpDisplay ?: return
        try {
            val audioPrepared = display.prepareAudio(
                128 * 1024, 44100, true, false, false
            )
            val videoPrepared = display.prepareVideo(
                videoWidth, videoHeight, videoFps, bitrateKbps * 1000, 0
            )
            if (audioPrepared && videoPrepared) {
                display.startStream(rtmpUrl)
            } else {
                updateNotification("Encoder error — retrying…")
                scheduleReconnect()
            }
        } catch (e: Exception) {
            updateNotification("Error: ${e.message}")
            scheduleReconnect()
        }
    }

    // ── ConnectCheckerRtmp ────────────────────────────────────────────────────

    override fun onConnectionSuccessRtmp() {
        reconnectDelayMs = 2_000L
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        updateNotification("● LIVE — 0 viewers")
    }

    override fun onConnectionFailedRtmp(reason: String) {
        updateNotification("Failed — Reconnecting…")
        scheduleReconnect()
    }

    override fun onNewBitrateRtmp(bitrate: Long) {}

    override fun onDisconnectRtmp() {
        updateNotification("Disconnected — Reconnecting…")
        scheduleReconnect()
    }

    override fun onAuthErrorRtmp() {
        updateNotification("Auth error — check stream key")
    }

    override fun onAuthSuccessRtmp() {
        updateNotification("Auth OK — connecting…")
    }

    // ── Auto-reconnect ────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val delay = reconnectDelayMs
        reconnectDelayMs = min(delay * 2, 30_000L)
        reconnectRunnable = Runnable {
            try {
                if (rtmpDisplay?.isStreaming == true) rtmpDisplay?.stopStream()
                startStream()
            } catch (_: Exception) {
                scheduleReconnect()
            }
        }
        mainHandler.postDelayed(reconnectRunnable!!, delay)
    }

    // ── Overlay windows ───────────────────────────────────────────────────────

    private fun initOverlays() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        // MaskView — draggable coloured rectangle
        val mask = View(this).apply {
            setBackgroundColor(COLOR_CYCLE[maskColorIndex])
            alpha = OPACITY_CYCLE[maskOpacityIndex] / 255f
        }
        val maskParams = WindowManager.LayoutParams(
            300, 200, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 200 }

        // ControlView — transparent full-screen gesture layer
        val control = buildControlView(overlayType)
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        mainHandler.post {
            try { windowManager.addView(mask,    maskParams);    maskView    = mask    } catch (_: Exception) {}
            try { windowManager.addView(control, controlParams); controlView = control } catch (_: Exception) {}
        }
    }

    private fun buildControlView(overlayType: Int): View = object : View(this) {

        private var touchDownTime   = 0L
        private var touchDownX      = 0f
        private var touchDownY      = 0f
        private var swipeStartY     = 0f
        private var longPressFired  = false
        private var lastTapTime     = 0L
        private var tapCount        = 0

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val sw = resources.displayMetrics.widthPixels.toFloat()
            val sh = resources.displayMetrics.heightPixels.toFloat()

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    touchDownTime  = System.currentTimeMillis()
                    touchDownX     = event.x
                    touchDownY     = event.y
                    swipeStartY    = event.y
                    longPressFired = false

                    // Long-press mic (top-right 10%)
                    if (event.x > sw * 0.9f && event.y < sh * 0.1f) {
                        mainHandler.postDelayed({
                            if (System.currentTimeMillis() - touchDownTime >= LONG_PRESS_MIC_MS) {
                                longPressFired = true; toggleMic()
                            }
                        }, LONG_PRESS_MIC_MS)
                    }

                    // Long-press center for color cycle
                    if (abs(event.x - sw / 2f) < sw * 0.1f &&
                        abs(event.y - sh / 2f) < sh * 0.1f) {
                        mainHandler.postDelayed({
                            if (System.currentTimeMillis() - touchDownTime >= LONG_PRESS_COLOR_MS) {
                                longPressFired = true; cycleMaskColor()
                            }
                        }, LONG_PRESS_COLOR_MS)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    // Right-edge swipe → volume
                    if (touchDownX > sw * 0.9f) {
                        val dy = swipeStartY - event.y
                        if (abs(dy) > SWIPE_MIN_DISTANCE) {
                            adjustVolume(dy > 0); swipeStartY = event.y
                        }
                    } else {
                        // Drag mask if touch started on it
                        dragMaskIfNeeded(event)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (longPressFired) return true
                    val elapsed  = System.currentTimeMillis() - touchDownTime
                    val dx       = abs(event.x - touchDownX)
                    val dy       = abs(event.y - touchDownY)
                    val isStill  = dx < 20f && dy < 20f && elapsed < 300

                    if (isStill) {
                        // Tap on mask → toggle visibility
                        val mv = maskView
                        if (mv != null) {
                            val loc = IntArray(2)
                            mv.getLocationOnScreen(loc)
                            if (event.x >= loc[0] && event.x <= loc[0] + mv.width &&
                                event.y >= loc[1] && event.y <= loc[1] + mv.height) {
                                toggleMaskVisibility(); return true
                            }
                        }

                        // Double-tap center → opacity cycle
                        if (abs(event.x - sw / 2f) < sw * 0.1f &&
                            abs(event.y - sh / 2f) < sh * 0.1f) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                                tapCount++
                                if (tapCount >= 2) { cycleMaskOpacity(); tapCount = 0 }
                            } else {
                                tapCount = 1
                            }
                            lastTapTime = now
                            return true
                        }
                    }
                    return false
                }
            }
            return false
        }

        private fun dragMaskIfNeeded(event: MotionEvent) {
            val mv = maskView ?: return
            val lp = mv.layoutParams as? WindowManager.LayoutParams ?: return
            val loc = IntArray(2); mv.getLocationOnScreen(loc)
            if (touchDownX >= loc[0] && touchDownX <= loc[0] + mv.width &&
                touchDownY >= loc[1] && touchDownY <= loc[1] + mv.height) {
                lp.x = (lp.x + (event.x - touchDownX)).toInt()
                lp.y = (lp.y + (event.y - touchDownY)).toInt()
                touchDownX = event.x; touchDownY = event.y
                mainHandler.post { try { windowManager.updateViewLayout(mv, lp) } catch (_: Exception) {} }
            }
        }
    }

    // ── Gesture helpers ───────────────────────────────────────────────────────

    private fun toggleMaskVisibility() {
        maskVisible = !maskVisible
        mainHandler.post {
            maskView?.visibility = if (maskVisible) View.VISIBLE else View.INVISIBLE
        }
        vibrate(40)
    }

    private fun cycleMaskOpacity() {
        maskOpacityIndex = (maskOpacityIndex + 1) % OPACITY_CYCLE.size
        mainHandler.post { maskView?.alpha = OPACITY_CYCLE[maskOpacityIndex] / 255f }
        vibrate(60)
    }

    private fun cycleMaskColor() {
        maskColorIndex = (maskColorIndex + 1) % COLOR_CYCLE.size
        mainHandler.post { maskView?.setBackgroundColor(COLOR_CYCLE[maskColorIndex]) }
        vibrate(80)
    }

    private fun toggleMic() {
        isMuted = !isMuted
        if (isMuted) rtmpDisplay?.disableAudio() else rtmpDisplay?.enableAudio()
        vibrate(if (isMuted) 100 else 50)
    }

    private fun adjustVolume(increase: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0
        )
        vibrate(20)
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
                    .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(ms)
                }
            }
        } catch (_: Exception) {}
    }

    // ── Viewer simulation ─────────────────────────────────────────────────────

    private fun startViewerSimulation() {
        viewerRunnable = object : Runnable {
            override fun run() {
                viewerCount = maxOf(0, viewerCount + (-2..8).random())
                if (rtmpDisplay?.isStreaming == true) {
                    updateNotification("● LIVE — $viewerCount viewers")
                }
                mainHandler.postDelayed(this, 5_000)
            }
        }
        mainHandler.postDelayed(viewerRunnable!!, 5_000)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Stream Service", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, StreamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("StreamShieldLive")
            .setContentText(status)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        notificationManager?.notify(NOTIF_ID, buildNotification(status))
    }
}
