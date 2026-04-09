package com.dao.remote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer

class PeerManager(
    private val context: Context,
    private val roomCode: String,
    private val resultCode: Int,
    private val projectionData: Intent,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDpi: Int,
    private val callback: Callback? = null
) {

    interface Callback {
        fun onPeerConnected()
        fun onPeerDisconnected()
        fun onError(msg: String)
    }

    companion object {
        const val TAG = "PeerManager"
        private const val FPS = 30
        private const val MAX_BITRATE_BPS = 2_500_000
        private const val MIN_BITRATE_BPS = 300_000

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.miwifi.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.qq.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.services.mozilla.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var dataChannel: DataChannel? = null
    private var signalClient: SignalClient? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentViewerPeerId: String? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true

        eglBase = EglBase.create()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        initScreenCapture()
        initSignaling()
    }

    fun stop() {
        if (!running) return
        running = false

        signalClient?.disconnect()
        signalClient = null

        try { capturer?.stopCapture() } catch (e: Exception) { Log.w(TAG, "stopCapture: ${e.message}") }
        capturer?.dispose()
        capturer = null

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        surfaceHelper?.dispose()
        surfaceHelper = null

        dataChannel?.close()
        dataChannel = null

        peerConnection?.close()
        peerConnection = null

        factory?.dispose()
        factory = null

        eglBase?.release()
        eglBase = null

        Log.i(TAG, "PeerManager stopped")
    }

    private fun initScreenCapture() {
        capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection revoked by system")
                mainHandler.post { callback?.onError("系统收回投屏权限") }
            }
        })

        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
        capturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(screenWidth, screenHeight, FPS)

        videoTrack = factory!!.createVideoTrack("screen_track", videoSource).apply {
            setEnabled(true)
        }

        Log.i(TAG, "Screen capture: ${screenWidth}x${screenHeight}@${FPS}fps")
    }

    private fun initSignaling() {
        val hostPeerId = "dao-host-$roomCode"
        signalClient = SignalClient(hostPeerId, object : SignalClient.Listener {
            override fun onOpen() {
                Log.i(TAG, "Signaling ready: $hostPeerId")
            }

            override fun onOffer(peerId: String, sdp: SessionDescription) {
                mainHandler.post {
                    if (!running) return@post
                    Log.i(TAG, "Viewer request: $peerId")
                    closePeerConnection()
                    currentViewerPeerId = peerId
                    createPeerConnectionAndOffer(peerId)
                }
            }

            override fun onAnswer(peerId: String, sdp: SessionDescription) {
                mainHandler.post {
                    if (!running) return@post
                    Log.i(TAG, "Answer from: $peerId")
                    peerConnection?.setRemoteDescription(SimpleSdpObserver("setRemote"), sdp)
                    applyBitrateLimit()
                }
            }

            override fun onCandidate(peerId: String, candidate: IceCandidate) {
                mainHandler.post {
                    peerConnection?.addIceCandidate(candidate)
                }
            }

            override fun onViewerJoined(viewerPeerId: String) {}

            override fun onError(error: String) {
                Log.e(TAG, "Signal error: $error")
                mainHandler.post { callback?.onError(error) }
            }
        })
        signalClient?.connect()
    }

    private fun closePeerConnection() {
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
    }

    private fun createPeerConnectionAndOffer(viewerPeerId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        peerConnection = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalClient?.sendCandidate(viewerPeerId, candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE: $state")
                mainHandler.post {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            callback?.onPeerConnected()
                            sendScreenInfo()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            callback?.onPeerDisconnected()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            callback?.onPeerDisconnected()
                            tryIceRestart(viewerPeerId)
                        }
                        else -> {}
                    }
                }
            }

            override fun onDataChannel(dc: DataChannel) {
                Log.i(TAG, "Remote DataChannel: ${dc.label()}")
                if (dc.label() == "control") {
                    setupControlChannel(dc)
                }
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })

        videoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("screen_stream"))
        }

        val dcInit = DataChannel.Init().apply {
            ordered = true
            maxRetransmits = 3
        }
        dataChannel = peerConnection?.createDataChannel("control", dcInit)
        setupControlChannel(dataChannel!!)

        peerConnection?.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                super.onCreateSuccess(sdp)
                val optimized = preferH264(sdp)
                peerConnection?.setLocalDescription(SimpleSdpObserver("setLocal"), optimized)
                signalClient?.sendOffer(viewerPeerId, optimized)
            }
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        })
    }

    private fun setupControlChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {}
            override fun onStateChange() {
                Log.i(TAG, "DC ${dc.label()}: ${dc.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                ControlService.handleCommand(String(data))
            }
        })
    }

    private fun sendScreenInfo() {
        val json = """{"type":"screenInfo","w":$screenWidth,"h":$screenHeight,"dpi":$screenDpi}"""
        sendData(json)
    }

    fun sendData(msg: String) {
        try {
            val dc = dataChannel ?: return
            if (dc.state() == DataChannel.State.OPEN) {
                dc.send(DataChannel.Buffer(ByteBuffer.wrap(msg.toByteArray()), false))
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendData: ${e.message}")
        }
    }

    private fun applyBitrateLimit() {
        try {
            val sender = peerConnection?.senders?.firstOrNull { it.track()?.kind() == "video" } ?: return
            val params = sender.parameters
            params.encodings.forEach { enc ->
                enc.maxBitrateBps = MAX_BITRATE_BPS
                enc.minBitrateBps = MIN_BITRATE_BPS
                enc.maxFramerate = FPS
            }
            sender.parameters = params
            Log.i(TAG, "Bitrate: ${MIN_BITRATE_BPS/1000}-${MAX_BITRATE_BPS/1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "applyBitrate: ${e.message}")
        }
    }

    private fun tryIceRestart(viewerPeerId: String) {
        if (!running || peerConnection == null) return
        Log.i(TAG, "ICE restart...")
        peerConnection?.createOffer(object : SimpleSdpObserver("iceRestart") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                super.onCreateSuccess(sdp)
                val optimized = preferH264(sdp)
                peerConnection?.setLocalDescription(SimpleSdpObserver("setLocal-r"), optimized)
                signalClient?.sendOffer(viewerPeerId, optimized)
            }
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        })
    }

    private fun preferH264(sdp: SessionDescription): SessionDescription {
        val lines = sdp.description.split("\r\n").toMutableList()
        val result = mutableListOf<String>()

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("m=video")) {
                val parts = line.split(" ").toMutableList()
                if (parts.size > 3) {
                    val pts = parts.subList(3, parts.size).toMutableList()
                    val h264Pts = mutableListOf<String>()
                    for (other in lines) {
                        if (other.startsWith("a=rtpmap:") && other.contains("H264", true)) {
                            val pt = other.substringAfter("a=rtpmap:").substringBefore(" ")
                            if (pt in pts) h264Pts.add(pt)
                        }
                    }
                    if (h264Pts.isNotEmpty()) {
                        pts.removeAll(h264Pts)
                        val reordered = h264Pts + pts
                        result.add(parts.subList(0, 3).joinToString(" ") + " " + reordered.joinToString(" "))
                        result.add("b=AS:${MAX_BITRATE_BPS / 1000}")
                        continue
                    }
                }
            }
            if (line.startsWith("b=AS:")) continue
            result.add(line)
        }

        return SessionDescription(sdp.type, result.joinToString("\r\n"))
    }

    open class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            Log.d(TAG, "$tag ok")
        }
        override fun onSetSuccess() {
            Log.d(TAG, "$tag set ok")
        }
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "$tag fail: $error")
        }
        override fun onSetFailure(error: String) {
            Log.e(TAG, "$tag set fail: $error")
        }
    }
}
