package com.dao.remote

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : AppCompatActivity(), PeerManager.Callback {

    private lateinit var startButton: MaterialButton
    private lateinit var shareButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var peerStatusText: TextView
    private lateinit var roomCodeText: TextView
    private lateinit var qrCodeImage: ImageView
    private lateinit var accessibilityWarning: TextView

    private var isCasting = false
    private var roomCode = ""

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            statusText.text = getString(R.string.status_idle)
            Toast.makeText(this, "需要投屏权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.startButton)
        shareButton = findViewById(R.id.shareButton)
        statusText = findViewById(R.id.statusText)
        peerStatusText = findViewById(R.id.peerStatusText)
        roomCodeText = findViewById(R.id.roomCodeText)
        qrCodeImage = findViewById(R.id.qrCodeImage)
        accessibilityWarning = findViewById(R.id.accessibilityWarning)

        startButton.setOnClickListener { toggleCasting() }
        shareButton.setOnClickListener { shareLink() }
        roomCodeText.setOnClickListener { copyRoomCode() }
        accessibilityWarning.setOnClickListener { openAccessibilitySettings() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        requestBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        checkAccessibility()
    }

    private fun checkAccessibility() {
        if (ControlService.isRunning()) {
            accessibilityWarning.visibility = View.GONE
        } else {
            accessibilityWarning.text = getString(R.string.accessibility_warning)
            accessibilityWarning.visibility = View.VISIBLE
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, getString(R.string.accessibility_guide), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "请手动打开: 设置 \u2192 无障碍 \u2192 亲情远程", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Silently ignore if not available
                }
            }
        }
    }

    private fun toggleCasting() {
        if (isCasting) {
            stopCasting()
        } else {
            if (!ControlService.isRunning()) {
                Toast.makeText(this, getString(R.string.accessibility_warning), Toast.LENGTH_LONG).show()
            }
            requestProjection()
        }
    }

    private fun requestProjection() {
        statusText.text = getString(R.string.status_connecting)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        roomCode = generateRoomCode()

        CaptureService.peerCallback = this

        val intent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
            putExtra(CaptureService.EXTRA_ROOM_CODE, roomCode)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isCasting = true
        startButton.text = getString(R.string.stop_casting)
        statusText.text = getString(R.string.status_streaming)

        roomCodeText.text = roomCode
        roomCodeText.visibility = View.VISIBLE
        findViewById<View>(R.id.copyHint).visibility = View.VISIBLE
        shareButton.visibility = View.VISIBLE

        showQrCode(buildViewerUrl(roomCode))
        qrCodeImage.visibility = View.VISIBLE
    }

    private fun stopCasting() {
        stopService(Intent(this, CaptureService::class.java))
        isCasting = false
        startButton.text = getString(R.string.start_casting)
        statusText.text = getString(R.string.status_idle)
        roomCodeText.visibility = View.GONE
        findViewById<View>(R.id.copyHint).visibility = View.GONE
        qrCodeImage.visibility = View.GONE
        shareButton.visibility = View.GONE
        peerStatusText.visibility = View.GONE
    }

    private fun shareLink() {
        val url = buildViewerUrl(roomCode)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "亲情远程投屏\n房间号: $roomCode\n打开链接即可查看:\n$url")
        }
        startActivity(Intent.createChooser(intent, "分享投屏链接"))
    }

    private fun copyRoomCode() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("room", roomCode))
        Toast.makeText(this, "房间号已复制", Toast.LENGTH_SHORT).show()
    }

    private fun buildViewerUrl(room: String): String {
        return "${App.VIEWER_URL}?r=$room"
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun showQrCode(content: String) {
        try {
            val size = 512
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF1a1a2e.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            qrCodeImage.setImageBitmap(bitmap)
        } catch (e: Exception) {
            qrCodeImage.visibility = View.GONE
        }
    }

    // PeerManager.Callback
    override fun onPeerConnected() {
        runOnUiThread {
            peerStatusText.text = getString(R.string.status_peer_connected)
            peerStatusText.visibility = View.VISIBLE
        }
    }

    override fun onPeerDisconnected() {
        runOnUiThread {
            peerStatusText.text = getString(R.string.status_peer_disconnected)
            peerStatusText.visibility = View.VISIBLE
        }
    }

    override fun onError(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
