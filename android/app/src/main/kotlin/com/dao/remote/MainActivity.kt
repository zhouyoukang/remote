package com.dao.remote

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : AppCompatActivity() {

    private lateinit var startButton: MaterialButton
    private lateinit var shareButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var peerStatusText: TextView
    private lateinit var roomCodeText: TextView
    private lateinit var qrCodeImage: ImageView

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

        startButton.setOnClickListener { toggleCasting() }
        shareButton.setOnClickListener { shareLink() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toggleCasting() {
        if (isCasting) {
            stopCasting()
        } else {
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

    fun onPeerConnected() {
        runOnUiThread {
            peerStatusText.text = getString(R.string.status_peer_connected)
            peerStatusText.visibility = View.VISIBLE
        }
    }

    fun onPeerDisconnected() {
        runOnUiThread {
            peerStatusText.visibility = View.GONE
        }
    }
}
