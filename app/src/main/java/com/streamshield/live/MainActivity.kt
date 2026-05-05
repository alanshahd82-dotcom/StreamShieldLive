package com.streamshield.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.streamshield.live.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRunning = false

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                startOverlay()
            } else {
                Toast.makeText(this, "يجب السماح بعرض فوق التطبيقات الأخرى", Toast.LENGTH_LONG).show()
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            checkOverlayAndStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener {
            if (isRunning) stopOverlay() else requestPermissionsAndStart()
        }
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkOverlayAndStart()
        }
    }

    private fun checkOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "السماح بعرض فوق التطبيقات الأخرى مطلوب", Toast.LENGTH_SHORT).show()
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            startOverlay()
        }
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        isRunning = true
        binding.btnToggle.text = "إيقاف الغطاء"
        binding.tvStatus.text = "● الغطاء يعمل — انتقل إلى TikTok"
        binding.tvStatus.setTextColor(0xFFBB86FC.toInt())
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        isRunning = false
        binding.btnToggle.text = "تشغيل الغطاء"
        binding.tvStatus.text = "الغطاء متوقف"
        binding.tvStatus.setTextColor(0xFF757575.toInt())
    }

    override fun onResume() {
        super.onResume()
        // Sync state if service was stopped externally
        if (isRunning && !OverlayService.isRunning) {
            isRunning = false
            binding.btnToggle.text = "تشغيل الغطاء"
            binding.tvStatus.text = "الغطاء متوقف"
            binding.tvStatus.setTextColor(0xFF757575.toInt())
        }
    }
}
