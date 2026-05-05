package com.streamshield.live

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.*
import android.view.View.OnTouchListener
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID   = 2001
        const val ACTION_PICK_IMAGE  = "action_pick_image"
        const val EXTRA_IMAGE_URI    = "extra_image_uri"
    }

    private lateinit var windowManager: WindowManager
    private val maskViews     = mutableListOf<View>()
    private val imageViews    = mutableListOf<ImageView>()
    private var controlPanel: View? = null
    private var fabView: View?      = null
    private var isMuted             = false
    private var controlVisible      = true

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning     = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        showControlPanel()
        showFab()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PICK_IMAGE) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_IMAGE_URI, Uri::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_IMAGE_URI)
            uri?.let { addImageOverlay(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        maskViews.forEach  { try { windowManager.removeViewImmediate(it) } catch (_: Exception) {} }
        imageViews.forEach { try { windowManager.removeViewImmediate(it) } catch (_: Exception) {} }
        try { controlPanel?.let { windowManager.removeViewImmediate(it) } } catch (_: Exception) {}
        try { fabView?.let      { windowManager.removeViewImmediate(it) } } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Control Panel ─────────────────────────────────────────────────────────

    private fun showControlPanel() {
        val panel = LinearLayout(this).apply {
            orientation  = LinearLayout.HORIZONTAL
            gravity      = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(220, 20, 20, 20))
            setPadding(16, 10, 16, 10)
        }

        fun iconBtn(emoji: String, label: String, action: () -> Unit): Button =
            Button(this).apply {
                text        = emoji
                contentDescription = label
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                textSize    = 22f
                setPadding(12, 8, 12, 8)
                setOnClickListener { vibrate(30); action() }
            }

        panel.addView(iconBtn("⬛", "إضافة قناع") { addMask() })
        panel.addView(iconBtn("🖼", "إضافة صورة")  { openImagePicker() })
        panel.addView(iconBtn("🔇", "كتم الصوت")   { toggleMute(panel) })
        panel.addView(iconBtn("🗑", "مسح الكل")    { clearAll() })
        panel.addView(iconBtn("✕", "إخفاء اللوحة") { hideControlPanel() })

        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y       = 60
        }

        makeDraggable(panel, params)

        windowManager.addView(panel, params)
        controlPanel   = panel
        controlVisible = true
    }

    private fun hideControlPanel() {
        controlPanel?.let {
            try { windowManager.removeViewImmediate(it) } catch (_: Exception) {}
        }
        controlPanel   = null
        controlVisible = false
        // Update FAB icon
        (fabView as? TextView)?.text = "☰"
    }

    private fun showFab() {
        val fab = TextView(this).apply {
            text      = "✕"
            textSize  = 20f
            gravity   = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 187, 134, 252))
            setPadding(20, 20, 20, 20)
        }

        val params = overlayParams(dp(52), dp(52)).apply {
            gravity = Gravity.TOP or Gravity.END
            y       = 140
            x       = 16
        }

        makeDraggable(fab, params)

        fab.setOnClickListener {
            vibrate(30)
            if (controlVisible) {
                hideControlPanel()
            } else {
                showControlPanel()
                fab.text   = "✕"
                controlVisible = true
            }
        }

        windowManager.addView(fab, params)
        fabView = fab
    }

    // ── Mask (hide zone) ──────────────────────────────────────────────────────

    private fun addMask() {
        val mask = LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            orientation = LinearLayout.VERTICAL
        }

        // Long-press to cycle colors; double-tap to delete
        var lastTap   = 0L
        var tapCount  = 0
        var colorIdx  = 0
        val colors    = listOf(
            Color.BLACK,
            Color.argb(255, 30,  30,  30),
            Color.argb(255, 0,   0,   80),
            Color.argb(200, 0,   0,   0),
            Color.DKGRAY
        )

        val longPressRunnable = Runnable {
            colorIdx = (colorIdx + 1) % colors.size
            mask.setBackgroundColor(colors[colorIdx])
            vibrate(60)
        }

        mask.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mask.postDelayed(longPressRunnable, 500)
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 300) {
                        tapCount++
                        if (tapCount >= 2) {
                            removeMask(mask)
                            tapCount = 0
                        }
                    } else {
                        tapCount = 1
                    }
                    lastTap = now
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    mask.removeCallbacks(longPressRunnable)
            }
            false
        }

        val params = overlayParams(dp(160), dp(120)).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80; y = 300
        }

        makeDraggable(mask, params)
        makeResizable(mask, params)

        windowManager.addView(mask, params)
        maskViews.add(mask)
        vibrate(40)
    }

    private fun removeMask(mask: View) {
        try { windowManager.removeViewImmediate(mask) } catch (_: Exception) {}
        maskViews.remove(mask)
        vibrate(50)
    }

    // ── Image Overlay ─────────────────────────────────────────────────────────

    private fun openImagePicker() {
        val intent = Intent(this, ImagePickerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    fun addImageOverlay(uri: Uri) {
        val bmp = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
            else
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
        } catch (_: Exception) { return }

        val imageView = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha     = 1f
        }

        // Double-tap to delete; long-press to cycle opacity
        var lastTap  = 0L
        var tapCount = 0
        var alphaIdx = 0
        val alphas   = listOf(1f, 0.75f, 0.5f, 0.25f)

        val longPressRunnable = Runnable {
            alphaIdx = (alphaIdx + 1) % alphas.size
            imageView.alpha = alphas[alphaIdx]
            vibrate(60)
        }

        imageView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    imageView.postDelayed(longPressRunnable, 500)
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 300) {
                        tapCount++
                        if (tapCount >= 2) {
                            removeImage(imageView)
                            tapCount = 0
                        }
                    } else {
                        tapCount = 1
                    }
                    lastTap = now
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    imageView.removeCallbacks(longPressRunnable)
            }
            false
        }

        val w = min(bmp.width, dp(200))
        val h = (bmp.height.toFloat() / bmp.width * w).toInt()

        val params = overlayParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80; y = 500
        }

        makeDraggable(imageView, params)
        makeResizable(imageView, params)

        windowManager.addView(imageView, params)
        imageViews.add(imageView)
        vibrate(40)
    }

    private fun removeImage(view: ImageView) {
        try { windowManager.removeViewImmediate(view) } catch (_: Exception) {}
        imageViews.remove(view)
        vibrate(50)
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    private fun toggleMute(panel: LinearLayout) {
        isMuted = !isMuted
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (isMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
        // Update mute button text
        val btn = panel.getChildAt(2) as? Button
        btn?.text = if (isMuted) "🔈" else "🔇"
        vibrate(if (isMuted) 80 else 40)
    }

    // ── Clear All ─────────────────────────────────────────────────────────────

    private fun clearAll() {
        maskViews.toList().forEach { removeMask(it) }
        imageViews.toList().forEach { removeImage(it) }
        vibrate(100)
    }

    // ── Draggable helper ──────────────────────────────────────────────────────

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var dX = 0f; var dY = 0f
        var isDragging = false

        view.setOnTouchListener(object : OnTouchListener {
            // Preserve any existing listener by chaining
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = params.x - event.rawX
                        dY = params.y - event.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newX = (event.rawX + dX).toInt()
                        val newY = (event.rawY + dY).toInt()
                        if (abs(newX - params.x) > 5 || abs(newY - params.y) > 5) {
                            isDragging = true
                        }
                        params.x = newX
                        params.y = newY
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) return false
                    }
                }
                return isDragging
            }
        })
    }

    // ── Resize helper (pinch/scale) ───────────────────────────────────────────

    private fun makeResizable(view: View, params: WindowManager.LayoutParams) {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor
                    params.width  = max(dp(60), (params.width  * factor).toInt())
                    params.height = max(dp(40), (params.height * factor).toInt())
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    return true
                }
            })

        val existing = view.getTag(R.id.tag_touch_listener) as? OnTouchListener
        view.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            existing?.onTouch(v, event) ?: false
        }
        view.setTag(R.id.tag_touch_listener, view.getTag(R.id.tag_touch_listener))
    }

    // ── WindowManager params ──────────────────────────────────────────────────

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "StreamShield Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("StreamShield")
            .setContentText("الغطاء يعمل فوق الشاشة")
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "إيقاف", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
                    .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        it.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                    else
                        @Suppress("DEPRECATION") it.vibrate(ms)
                }
            }
        } catch (_: Exception) {}
    }
}
