package com.dao.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.TimeUnit

class SignalClient(
    private val peerId: String,
    private val listener: Listener
) {

    companion object {
        const val TAG = "SignalClient"
        private const val PEERJS_HOST = "0.peerjs.com"
        private const val PEERJS_PORT = 443
        private const val PEERJS_PATH = "/peerjs"
        private const val PEERJS_KEY = "peerjs"
    }

    interface Listener {
        fun onOpen()
        fun onOffer(peerId: String, sdp: SessionDescription)
        fun onAnswer(peerId: String, sdp: SessionDescription)
        fun onCandidate(peerId: String, candidate: IceCandidate)
        fun onViewerJoined(viewerPeerId: String)
        fun onError(error: String)
    }

    private val gson = Gson()
    private val token = UUID.randomUUID().toString()
    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val url = "wss://$PEERJS_HOST:$PEERJS_PORT$PEERJS_PATH" +
                "?key=$PEERJS_KEY&id=$peerId&token=$token"

        Log.i(TAG, "Connecting to PeerJS: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                listener.onError(t.message ?: "Connection failed")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $code $reason")
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
    }

    fun sendOffer(targetPeerId: String, sdp: SessionDescription) {
        val payload = JsonObject().apply {
            add("sdp", JsonObject().apply {
                addProperty("type", sdp.type.canonicalForm())
                addProperty("sdp", sdp.description)
            })
            addProperty("type", "media")
            addProperty("connectionId", "dc_${System.currentTimeMillis()}")
            addProperty("browser", "android")
        }

        val msg = JsonObject().apply {
            addProperty("type", "OFFER")
            add("payload", payload)
            addProperty("dst", targetPeerId)
        }

        send(msg)
    }

    fun sendAnswer(targetPeerId: String, sdp: SessionDescription) {
        val payload = JsonObject().apply {
            add("sdp", JsonObject().apply {
                addProperty("type", sdp.type.canonicalForm())
                addProperty("sdp", sdp.description)
            })
            addProperty("type", "media")
            addProperty("browser", "android")
        }

        val msg = JsonObject().apply {
            addProperty("type", "ANSWER")
            add("payload", payload)
            addProperty("dst", targetPeerId)
        }

        send(msg)
    }

    fun sendCandidate(targetPeerId: String, candidate: IceCandidate) {
        val payload = JsonObject().apply {
            add("candidate", JsonObject().apply {
                addProperty("candidate", candidate.sdp)
                addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
                addProperty("sdpMid", candidate.sdpMid)
            })
            addProperty("type", "media")
            addProperty("connectionId", "dc_${System.currentTimeMillis()}")
        }

        val msg = JsonObject().apply {
            addProperty("type", "CANDIDATE")
            add("payload", payload)
            addProperty("dst", targetPeerId)
        }

        send(msg)
    }

    private fun send(json: JsonObject) {
        val text = gson.toJson(json)
        ws?.send(text)
        Log.d(TAG, "Sent: ${json.get("type")?.asString}")
    }

    private fun handleMessage(text: String) {
        try {
            val msg = gson.fromJson(text, JsonObject::class.java)
            val type = msg.get("type")?.asString ?: return

            when (type) {
                "OPEN" -> {
                    Log.i(TAG, "PeerJS OPEN \u2014 peer registered: $peerId")
                    listener.onOpen()
                }

                "OFFER" -> {
                    val src = msg.get("src")?.asString ?: return
                    val payload = msg.getAsJsonObject("payload")
                    val sdpObj = payload.getAsJsonObject("sdp")
                    val sdp = SessionDescription(
                        SessionDescription.Type.OFFER,
                        sdpObj.get("sdp").asString
                    )
                    listener.onOffer(src, sdp)
                }

                "ANSWER" -> {
                    val src = msg.get("src")?.asString ?: return
                    val payload = msg.getAsJsonObject("payload")
                    val sdpObj = payload.getAsJsonObject("sdp")
                    val sdp = SessionDescription(
                        SessionDescription.Type.ANSWER,
                        sdpObj.get("sdp").asString
                    )
                    listener.onAnswer(src, sdp)
                }

                "CANDIDATE" -> {
                    val src = msg.get("src")?.asString ?: return
                    val payload = msg.getAsJsonObject("payload")
                    val candObj = payload.getAsJsonObject("candidate")
                    val candidate = IceCandidate(
                        candObj.get("sdpMid")?.asString ?: "",
                        candObj.get("sdpMLineIndex")?.asInt ?: 0,
                        candObj.get("candidate").asString
                    )
                    listener.onCandidate(src, candidate)
                }

                "EXPIRE" -> {
                    Log.w(TAG, "Peer expired, reconnecting...")
                    scheduleReconnect()
                }

                "HEARTBEAT" -> { /* ignore */ }

                "ERROR" -> {
                    val errorMsg = msg.get("payload")?.asString ?: "Unknown"
                    Log.e(TAG, "PeerJS error: $errorMsg")
                    if (errorMsg.contains("taken", ignoreCase = true)) {
                        listener.onError("房间号已被占用")
                    } else {
                        listener.onError(errorMsg)
                    }
                }

                else -> Log.d(TAG, "Unknown message type: $type")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    @Volatile private var reconnectAttempt = 0
    private val maxReconnectDelay = 30_000L

    private fun scheduleReconnect() {
        val delay = minOf(1000L * (1L shl minOf(reconnectAttempt, 5)), maxReconnectDelay)
        reconnectAttempt++
        Log.i(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempt)")
        Thread {
            Thread.sleep(delay)
            if (ws == null) connect()
        }.start()
    }

    fun resetReconnect() {
        reconnectAttempt = 0
    }
}
