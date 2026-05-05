package com.streamshield.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.streamshield.live.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isStreaming = false

    // ── MediaProjection launcher ──────────────────────────────────────────────
    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startStreamService(result.resultCode, result.data!!)
            } else {
                showToast("Screen capture permission denied.")
            }
        }

    // ── Runtime permission launcher ───────────────────────────────────────────
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.values.all { it }
            if (allGranted) {
                checkOverlayPermissionThenProceed()
            } else {
                showToast("Required permissions not granted.")
            }
        }

    // ── Overlay permission launcher ───────────────────────────────────────────
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                requestMediaProjection()
            } else {
                showToast("Overlay permission is required for the hidden mask.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupSpinners()
        setupBitrateSeekbar()
        setupButton()
    }

    private fun setupSpinners() {
        val resAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.resolution_options,
            android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerResolution.adapter = resAdapter
        binding.spinnerResolution.setSelection(0) // default 720p

        val fpsAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.fps_options,
            android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerFps.adapter = fpsAdapter
        binding.spinnerFps.setSelection(1) // default 30
    }

    private fun setupBitrateSeekbar() {
        updateBitrateLabel(binding.seekBitrate.progress)
        binding.seekBitrate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateBitrateLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun updateBitrateLabel(progress: Int) {
        val mbps = progress + 1
        binding.tvBitrateLabel.text = getString(R.string.label_bitrate, mbps)
    }

    private fun setupButton() {
        binding.btnStartStop.setOnClickListener {
            if (isStreaming) {
                stopStream()
            } else {
                if (validateInputs()) {
                    requestPermissionsAndStart()
                }
            }
        }
    }

    private fun validateInputs(): Boolean {
        val url = binding.etRtmpUrl.text?.toString()?.trim()
        val key = binding.etStreamKey.text?.toString()?.trim()

        if (url.isNullOrEmpty() || key.isNullOrEmpty()) {
            showToast(getString(R.string.error_empty_fields))
            return false
        }

        if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) {
            showToast("RTMP URL must start with rtmp:// or rtmps://")
            return false
        }

        return true
    }

    private fun requestPermissionsAndStart() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            checkOverlayPermissionThenProceed()
        }
    }

    private fun checkOverlayPermissionThenProceed() {
        if (!Settings.canDrawOverlays(this)) {
            showToast(getString(R.string.permission_overlay_needed))
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun startStreamService(resultCode: Int, data: Intent) {
        val rtmpUrl = binding.etRtmpUrl.text?.toString()?.trim() ?: return
        val streamKey = binding.etStreamKey.text?.toString()?.trim() ?: return
        val resolutionLabel = binding.spinnerResolution.selectedItem?.toString() ?: "720p"
        val fpsLabel = binding.spinnerFps.selectedItem?.toString() ?: "30"
        val bitrateKbps = (binding.seekBitrate.progress + 1) * 1000

        val (width, height) = when (resolutionLabel) {
            "1080p" -> Pair(1920, 1080)
            else    -> Pair(1280, 720)
        }
        val fps = fpsLabel.toIntOrNull() ?: 30

        val serviceIntent = Intent(this, StreamService::class.java).apply {
            putExtra(StreamService.EXTRA_RTMP_URL, "$rtmpUrl/$streamKey")
            putExtra(StreamService.EXTRA_WIDTH, width)
            putExtra(StreamService.EXTRA_HEIGHT, height)
            putExtra(StreamService.EXTRA_FPS, fps)
            putExtra(StreamService.EXTRA_BITRATE_KBPS, bitrateKbps)
            putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamService.EXTRA_PROJECTION_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isStreaming = true
        binding.btnStartStop.text = getString(R.string.btn_stop_stream)
        setStatus(getString(R.string.status_connecting), getColor(R.color.colorPrimary))
    }

    private fun stopStream() {
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_STOP
        }
        startService(intent)

        isStreaming = false
        binding.btnStartStop.text = getString(R.string.btn_start_stream)
        setStatus(getString(R.string.status_idle), getColor(R.color.colorIdle))
    }

    private fun setStatus(message: String, color: Int) {
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(color)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
