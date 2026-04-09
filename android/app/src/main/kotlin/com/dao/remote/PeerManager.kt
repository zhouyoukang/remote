package com.dao.remote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*

class PeerManager(
    private val context: Context,
    private val roomCode: String,
    private val resultCode: Int,
    private val projectionData: Intent,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDpi: Int
) {

    companion object {
        const val TAG = "PeerManager"
        private const val FPS = 30

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.miwifi.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
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

    fun start() {
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
        signalClient?.disconnect()
        signalClient = null

        capturer?.stopCapture()
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
                Log.w(TAG, "MediaProjection stopped")
            }
        })

        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
        capturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(screenWidth, screenHeight, FPS)

        videoTrack = factory!!.createVideoTrack("screen_track", videoSource).apply {
            setEnabled(true)
        }

        Log.i(TAG, "Screen capture initialized: ${screenWidth}x${screenHeight}@${FPS}fps")
    }

    private fun initSignaling() {
        val hostPeerId = "dao-host-$roomCode"
        signalClient = SignalClient(hostPeerId, object : SignalClient.Listener {
            override fun onOpen() {
                Log.i(TAG, "Signaling connected as $hostPeerId, waiting for viewer...")
            }

            override fun onOffer(peerId: String, sdp: SessionDescription) {
                mainHandler.post {
                    if (sdp.description == "hello") {
                        Log.i(TAG, "Viewer joined via hello: $peerId \u2192 creating offer")
                    } else {
                        Log.i(TAG, "Viewer rejoin from $peerId")
                    }
                    closePeerConnection()
                    currentViewerPeerId = peerId
                    createPeerConnectionAndOffer(peerId)
                }
            }

            override fun onAnswer(peerId: String, sdp: SessionDescription) {
                mainHandler.post {
                    Log.i(TAG, "Received answer from $peerId")
                    peerConnection?.setRemoteDescription(SimpleSdpObserver("setRemote"), sdp)
                }
            }

            override fun onCandidate(peerId: String, candidate: IceCandidate) {
                mainHandler.post {
                    peerConnection?.addIceCandidate(candidate)
                }
            }

            override fun onViewerJoined(viewerPeerId: String) {
                // Handled in onOffer via "hello" detection
            }

            override fun onError(error: String) {
                Log.e(TAG, "Signaling error: $error")
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
        }

        peerConnection = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalClient?.sendCandidate(viewerPeerId, candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        Log.i(TAG, "P2P connected!")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.w(TAG, "P2P disconnected/failed")
                    }
                    else -> {}
                }
            }

            override fun onDataChannel(dc: DataChannel) {
                Log.i(TAG, "Received data channel: ${dc.label()}")
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
        }
        dataChannel = peerConnection?.createDataChannel("control", dcInit)
        setupControlChannel(dataChannel!!)

        peerConnection?.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                super.onCreateSuccess(sdp)
                val optimizedSdp = optimizeSdp(sdp)
                peerConnection?.setLocalDescription(SimpleSdpObserver("setLocal"), optimizedSdp)
                signalClient?.sendOffer(viewerPeerId, optimizedSdp)
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
                Log.i(TAG, "DataChannel state: ${dc.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val msg = String(data)
                ControlService.handleCommand(msg)
            }
        })
    }

    private fun optimizeSdp(sdp: SessionDescription): SessionDescription {
        var desc = sdp.description
        // Prefer H.264 Constrained Baseline for maximum compatibility + low latency
        // Add bandwidth limit for mobile-friendly streaming
        if (!desc.contains("b=AS:")) {
            desc = desc.replace("c=IN ", "b=AS:2000\r\nc=IN ")
        }
        return SessionDescription(sdp.type, desc)
    }

    open class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            Log.d(TAG, "$tag onCreateSuccess")
        }
        override fun onSetSuccess() {
            Log.d(TAG, "$tag onSetSuccess")
        }
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "$tag onCreateFailure: $error")
        }
        override fun onSetFailure(error: String) {
            Log.e(TAG, "$tag onSetFailure: $error")
        }
    }
}
