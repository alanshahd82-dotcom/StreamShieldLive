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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import net.ossrs.rtmp.ConnectCheckerRtmp
import net.ossrs.rtmp.SrsFlvMuxer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

class StreamService : Service(), ConnectCheckerRtmp {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        const val EXTRA_RTMP_URL         = "extra_rtmp_url"
        const val EXTRA_WIDTH            = "extra_width"
        const val EXTRA_HEIGHT           = "extra_height"
        const val EXTRA_FPS              = "extra_fps"
        const val EXTRA_BITRATE_KBPS     = "extra_bitrate_kbps"
        const val EXTRA_RESULT_CODE      = "extra_result_code"
        const val EXTRA_PROJECTION_DATA  = "extra_projection_data"
        const val ACTION_STOP            = "action_stop_stream"

        private const val NOTIF_ID       = 1001
        private const val CHANNEL_ID     = "stream_service_channel"

        // Audio settings
        private const val AUDIO_SAMPLE_RATE    = 44100
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT         = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_BITRATE        = 128_000

        // Reconnect
        private const val RECONNECT_INITIAL_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS     = 30_000L

        // Gesture thresholds
        private const val LONG_PRESS_MIC_MS     = 400L
        private const val LONG_PRESS_COLOR_MS   = 500L
        private const val SWIPE_MIN_DISTANCE    = 30f
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L

        // Mask opacity cycle (alpha 0-255)
        private val OPACITY_CYCLE = intArrayOf(255, 180, 120, 60, 0)

        // Mask color cycle
        private val COLOR_CYCLE = intArrayOf(
            Color.argb(255, 0,   0,   0),
            Color.argb(255, 40,  40,  40),
            Color.argb(255, 0,   0,   60),
            Color.argb(255, 40,  0,   60)
        )
    }

    // ── Service state ─────────────────────────────────────────────────────────

    private lateinit var windowManager: WindowManager
    private var maskView: View? = null
    private var controlView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var rtmpMuxer: SrsFlvMuxer? = null

    private val isRunning  = AtomicBoolean(false)
    private val isMuted    = AtomicBoolean(false)

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioReadThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var notificationManager: NotificationManager? = null

    // Stream config
    private var rtmpUrl      = ""
    private var videoWidth   = 1280
    private var videoHeight  = 720
    private var videoFps     = 30
    private var bitrateKbps  = 3000

    // Reconnect state
    private var reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS
    private var reconnectRunnable: Runnable? = null

    // Viewer count simulation
    private var viewerCount  = 0
    private var viewerRunnable: Runnable? = null

    // Overlay gesture state
    private var maskOpacityIndex = 0
    private var maskColorIndex   = 0
    private var maskVisible      = true

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            tearDown()
            stopSelf()
            return START_NOT_STICKY
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

        val resultCode    = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val projData      = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        } ?: run { stopSelf(); return START_NOT_STICKY }

        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projManager.getMediaProjection(resultCode, projData)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning.set(true)
        initOverlays()
        startEncoding()
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
        isRunning.set(false)
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        viewerRunnable?.let   { mainHandler.removeCallbacks(it) }

        try { virtualDisplay?.release() }  catch (_: Exception) {}
        try { videoEncoder?.stop(); videoEncoder?.release() } catch (_: Exception) {}
        try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) {}
        try { audioRecord?.stop();  audioRecord?.release()  } catch (_: Exception) {}
        try { rtmpMuxer?.stop()  } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}

        mainHandler.post {
            maskView?.let    { windowManager.removeViewImmediate(it) }
            controlView?.let { windowManager.removeViewImmediate(it) }
            maskView    = null
            controlView = null
        }
    }

    // ── Overlay windows ───────────────────────────────────────────────────────

    private fun initOverlays() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        // MaskView — draggable semi-transparent rectangle
        val mask = View(this).apply {
            setBackgroundColor(COLOR_CYCLE[maskColorIndex])
            alpha = OPACITY_CYCLE[maskOpacityIndex] / 255f
        }
        val maskParams = WindowManager.LayoutParams(
            300, 200,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 200
        }
        mainHandler.post {
            try {
                windowManager.addView(mask, maskParams)
                maskView = mask
            } catch (_: Exception) {}
        }

        // ControlView — transparent full-screen touch interceptor
        val control = object : View(this) {
            private val gestureDetector = GestureDetector(context, buildGestureListener())
            private var touchDownTime    = 0L
            private var touchDownX       = 0f
            private var touchDownY       = 0f
            private var swipeStartY      = 0f
            private var isMicLongPress   = false
            private var isColorLongPress = false
            private var lastTapTime      = 0L
            private var tapCount         = 0

            override fun onTouchEvent(event: MotionEvent): Boolean {
                val screenW = resources.displayMetrics.widthPixels
                val screenH = resources.displayMetrics.heightPixels

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownTime   = System.currentTimeMillis()
                        touchDownX      = event.x
                        touchDownY      = event.y
                        swipeStartY     = event.y
                        isMicLongPress  = false
                        isColorLongPress = false

                        // Schedule mic-mute long-press (top-right 10%)
                        if (event.x > screenW * 0.9f && event.y < screenH * 0.1f) {
                            mainHandler.postDelayed({
                                if (System.currentTimeMillis() - touchDownTime >= LONG_PRESS_MIC_MS) {
                                    isMicLongPress = true
                                    toggleMic()
                                }
                            }, LONG_PRESS_MIC_MS)
                        }

                        // Schedule color long-press (center 20%)
                        val cx = abs(event.x - screenW / 2f)
                        val cy = abs(event.y - screenH / 2f)
                        if (cx < screenW * 0.1f && cy < screenH * 0.1f) {
                            mainHandler.postDelayed({
                                if (System.currentTimeMillis() - touchDownTime >= LONG_PRESS_COLOR_MS) {
                                    isColorLongPress = true
                                    cycleMaskColor()
                                }
                            }, LONG_PRESS_COLOR_MS)
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // Right-edge swipe for volume (right 10%)
                        if (touchDownX > screenW * 0.9f) {
                            val deltaY = swipeStartY - event.y
                            if (abs(deltaY) > SWIPE_MIN_DISTANCE) {
                                adjustVolume(deltaY > 0)
                                swipeStartY = event.y
                            }
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        val elapsed = System.currentTimeMillis() - touchDownTime
                        val dx = abs(event.x - touchDownX)
                        val dy = abs(event.y - touchDownY)
                        val isStationary = dx < 20f && dy < 20f

                        if (isMicLongPress || isColorLongPress) return true

                        if (isStationary && elapsed < 250) {
                            // Handle tap on mask
                            val maskV = maskView
                            if (maskV != null) {
                                val loc = IntArray(2)
                                maskV.getLocationOnScreen(loc)
                                val inMask = event.x >= loc[0] && event.x <= loc[0] + maskV.width &&
                                             event.y >= loc[1] && event.y <= loc[1] + maskV.height
                                if (inMask) {
                                    toggleMaskVisibility()
                                    return true
                                }
                            }

                            // Double-tap center for opacity cycle
                            val cx = abs(event.x - screenW / 2f)
                            val cy = abs(event.y - screenH / 2f)
                            if (cx < screenW * 0.1f && cy < screenH * 0.1f) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < DOUBLE_TAP_TIMEOUT_MS) {
                                    tapCount++
                                    if (tapCount >= 2) {
                                        cycleMaskOpacity()
                                        tapCount = 0
                                    }
                                } else {
                                    tapCount = 1
                                }
                                lastTapTime = now
                                return true
                            }
                        }

                        // Pass touch through if outside gesture zones
                        return false
                    }
                }

                gestureDetector.onTouchEvent(event)
                return false
            }
        }

        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        mainHandler.post {
            try {
                windowManager.addView(control, controlParams)
                controlView = control
            } catch (_: Exception) {}
        }
    }

    private fun buildGestureListener(): GestureDetector.SimpleOnGestureListener =
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                vX: Float,
                vY: Float
            ): Boolean {
                // Drag mask view
                val maskV = maskView ?: return false
                val lp = maskV.layoutParams as? WindowManager.LayoutParams ?: return false
                lp.x = (lp.x + (e2.x - (e1?.x ?: e2.x))).toInt()
                lp.y = (lp.y + (e2.y - (e1?.y ?: e2.y))).toInt()
                mainHandler.post {
                    try { windowManager.updateViewLayout(maskV, lp) } catch (_: Exception) {}
                }
                return true
            }
        }

    // ── Overlay control helpers ───────────────────────────────────────────────

    private fun toggleMaskVisibility() {
        val mask = maskView ?: return
        maskVisible = !maskVisible
        mainHandler.post {
            mask.visibility = if (maskVisible) View.VISIBLE else View.INVISIBLE
        }
        vibrate(40)
    }

    private fun cycleMaskOpacity() {
        maskOpacityIndex = (maskOpacityIndex + 1) % OPACITY_CYCLE.size
        val alpha = OPACITY_CYCLE[maskOpacityIndex] / 255f
        val mask = maskView ?: return
        mainHandler.post { mask.alpha = alpha }
        vibrate(60)
    }

    private fun cycleMaskColor() {
        maskColorIndex = (maskColorIndex + 1) % COLOR_CYCLE.size
        val color = COLOR_CYCLE[maskColorIndex]
        val mask = maskView ?: return
        mainHandler.post { mask.setBackgroundColor(color) }
        vibrate(80)
    }

    private fun toggleMic() {
        isMuted.set(!isMuted.get())
        vibrate(if (isMuted.get()) 100 else 50)
    }

    private fun adjustVolume(increase: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val direction = if (increase)
            android.media.AudioManager.ADJUST_RAISE
        else
            android.media.AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            direction,
            0
        )
        vibrate(20)
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(ms)
                }
            }
        } catch (_: Exception) {}
    }

    // ── Encoding pipeline ─────────────────────────────────────────────────────

    private fun startEncoding() {
        try {
            initRtmpMuxer()
            initVideoEncoder()
            initAudioEncoder()
            initAudioRecord()
            createVirtualDisplay()
            startEncoderThreads()
        } catch (e: Exception) {
            scheduleReconnect()
        }
    }

    private fun initRtmpMuxer() {
        rtmpMuxer = SrsFlvMuxer(this).also {
            it.start(rtmpUrl)
        }
    }

    private fun initVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, videoFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun initAudioEncoder() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT) * 2)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
    }

    private fun initAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBuf * 2
        )
    }

    private fun createVirtualDisplay() {
        val surface = videoEncoder?.createInputSurface()
            ?: throw IllegalStateException("Video encoder surface null")

        videoEncoder?.start()

        val displayMetrics = resources.displayMetrics
        val dpi = displayMetrics.densityDpi

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "StreamShieldCapture",
            videoWidth,
            videoHeight,
            dpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
    }

    private fun startEncoderThreads() {
        videoThread = Thread({ drainVideoEncoder() }, "VideoEncoder").also { it.start() }
        audioThread = Thread({ drainAudioEncoder() }, "AudioEncoder").also { it.start() }
        audioReadThread = Thread({ readAudioAndEncode() }, "AudioReader").also { it.start() }
    }

    // ── Video drain loop ──────────────────────────────────────────────────────

    private fun drainVideoEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = videoEncoder ?: return
        val muxer   = rtmpMuxer   ?: return

        while (isRunning.get()) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* wait */ }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // SrsFlvMuxer handles SPS/PPS internally via codec config packets
                }
                index >= 0 -> {
                    val encodedData: ByteBuffer = encoder.getOutputBuffer(index) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        muxer.setSpsPPs(encodedData)
                    } else if (bufferInfo.size > 0) {
                        try {
                            muxer.sendVideoData(encodedData, bufferInfo)
                        } catch (_: Exception) {}
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    // ── Audio read → encode ───────────────────────────────────────────────────

    private fun readAudioAndEncode() {
        val record  = audioRecord ?: return
        val encoder = audioEncoder ?: return
        val minBuf  = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT)
        val buf     = ByteArray(minBuf)

        record.startRecording()

        while (isRunning.get()) {
            val read = record.read(buf, 0, buf.size)
            if (read <= 0) continue

            val inputIndex = encoder.dequeueInputBuffer(5_000)
            if (inputIndex < 0) continue

            val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()

            // Silence buffer when muted
            if (isMuted.get()) {
                inputBuffer.put(ByteArray(read))
            } else {
                inputBuffer.put(buf, 0, read)
            }

            val pts = System.nanoTime() / 1000
            encoder.queueInputBuffer(inputIndex, 0, read, pts, 0)
        }

        record.stop()
    }

    // ── Audio drain loop ──────────────────────────────────────────────────────

    private fun drainAudioEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = audioEncoder ?: return
        val muxer   = rtmpMuxer   ?: return

        while (isRunning.get()) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* wait */ }
                index >= 0 -> {
                    val data: ByteBuffer = encoder.getOutputBuffer(index) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        && bufferInfo.size > 0) {
                        try {
                            muxer.sendAudioData(data, bufferInfo)
                        } catch (_: Exception) {}
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    // ── ConnectCheckerRtmp ────────────────────────────────────────────────────

    override fun onConnectionSuccessRtmp() {
        reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        updateNotification("● LIVE — 0 viewers")
    }

    override fun onConnectionFailedRtmp(reason: String) {
        updateNotification("Connection failed — Reconnecting…")
        scheduleReconnect()
    }

    override fun onNewBitrateRtmp(bitrate: Long) { /* no-op */ }

    override fun onDisconnectRtmp() {
        updateNotification("Disconnected — Reconnecting…")
        scheduleReconnect()
    }

    override fun onAuthErrorRtmp() {
        updateNotification("Auth error — check stream key")
    }

    override fun onAuthSuccessRtmp() {
        updateNotification("Auth success — connecting…")
    }

    // ── Auto-reconnect ────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (!isRunning.get()) return
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val delay = reconnectDelayMs
        reconnectDelayMs = min(delay * 2, RECONNECT_MAX_DELAY_MS)
        reconnectRunnable = Runnable {
            if (!isRunning.get()) return@Runnable
            try {
                rtmpMuxer?.stop()
                rtmpMuxer = SrsFlvMuxer(this).also { it.start(rtmpUrl) }
            } catch (_: Exception) {
                scheduleReconnect()
            }
        }
        mainHandler.postDelayed(reconnectRunnable!!, delay)
    }

    // ── Viewer simulation ─────────────────────────────────────────────────────

    private fun startViewerSimulation() {
        viewerRunnable = object : Runnable {
            override fun run() {
                if (!isRunning.get()) return
                val delta = (-2..8).random()
                viewerCount = maxOf(0, viewerCount + delta)
                val notif = notificationManager?.activeNotifications
                    ?.any { it.id == NOTIF_ID } == true
                if (notif) {
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
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "StreamShieldLive active stream"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, StreamService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(status)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = buildNotification(status)
        notificationManager?.notify(NOTIF_ID, notification)
    }
}
