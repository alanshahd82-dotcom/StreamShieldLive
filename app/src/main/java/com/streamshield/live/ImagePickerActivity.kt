package com.streamshield.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent trampoline activity — opens the gallery picker and
 * passes the result back to OverlayService without showing any UI.
 */
class ImagePickerActivity : AppCompatActivity() {

    private val pickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_PICK_IMAGE
                    putExtra(OverlayService.EXTRA_IMAGE_URI, uri)
                    // Keep URI permission across process boundary
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(serviceIntent)
                else
                    startService(serviceIntent)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launch immediately with no visible window
        pickerLauncher.launch("image/*")
    }
}
