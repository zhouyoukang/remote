package com.dao.remote

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class CaptureService : Service() {

    companion object {
        const val TAG = "CaptureService"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_ROOM_CODE = "room_code"
        const val NOTIF_ID = 1001

        var peerCallback: PeerManager.Callback? = null
    }

    private var peerManager: PeerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""

        if (resultCode == -1 || data == null || roomCode.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification(roomCode)
        acquireWakeLock()

        val metrics = getScreenMetrics()
        val width = metrics.first.coerceAtMost(1280)
        val height = metrics.second.coerceAtMost(1920)

        peerManager = PeerManager(
            context = this,
            roomCode = roomCode,
            resultCode = resultCode,
            projectionData = data,
            screenWidth = width,
            screenHeight = height,
            screenDpi = metrics.third,
            callback = peerCallback
        )
        peerManager?.start()

        Log.i(TAG, "Capture started: ${width}x${height} room=$roomCode")
        return START_STICKY
    }

    override fun onDestroy() {
        peerManager?.stop()
        peerManager = null
        releaseWakeLock()
        peerCallback = null
        Log.i(TAG, "Capture stopped")
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DaoRemote::Capture"
        ).apply { acquire() }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun startForegroundWithNotification(roomCode: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("${getString(R.string.notification_title)} \u00b7 $roomCode")
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun getScreenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }
}
