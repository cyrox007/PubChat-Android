package ru.pubchat.app.core.calls

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID

/**
 * Single active, foreground-only, one-to-one WebRTC call.
 * DTLS-SRTP protects media; signaling/peer identity is not application E2EE.
 * Caller must request CAMERA/RECORD_AUDIO permissions before starting or answering.
 */
class NativeCallEngine(
    context: Context,
    private val sendSignal: (String, String, String, JSONObject) -> Boolean,
    private val status: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    val videoContext: EglBase.Context get() = egl.eglBaseContext
    var remoteVideo: VideoTrack? = null
        private set
    var localVideo: VideoTrack? = null
        private set
    var peerUid: String? = null
        private set
    var callUid: String? = null
        private set
    var mode: String = "audio"
        private set
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionReady = false

    fun begin(peer: String, video: Boolean, iceConfig: JSONObject, incomingCallUid: String? = null) {
        end(sendEnd = false)
        peerUid = peer
        callUid = incomingCallUid ?: UUID.randomUUID().toString()
        mode = if (video) "video" else "audio"
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        this.factory = factory
        val servers = mutableListOf<PeerConnection.IceServer>()
        val arr = iceConfig.optJSONArray("ice_servers")
        if (arr != null) for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val urls = entry.optJSONArray("urls") ?: continue
            val list = (0 until urls.length()).mapNotNull { index -> urls.optString(index).takeIf { it.isNotBlank() } }
            if (list.isEmpty()) continue
            servers += PeerConnection.IceServer.builder(list)
                .setUsername(entry.optString("username"))
                .setPassword(entry.optString("credential"))
                .createIceServer()
        }
        require(servers.isNotEmpty()) { "Сервер не настроил STUN/TURN" }
        val config = PeerConnection.RTCConfiguration(servers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        if (iceConfig.optString("ice_transport_policy") == "relay") {
            config.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        connection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(value: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(value: PeerConnection.IceConnectionState?) {
                if (value == PeerConnection.IceConnectionState.CONNECTED) status("active")
                if (value == PeerConnection.IceConnectionState.FAILED) status("error")
            }
            override fun onIceConnectionReceivingChange(value: Boolean) {}
            override fun onIceGatheringChange(value: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val uid = callUid ?: return
                val target = peerUid ?: return
                sendSignal(target, uid, "call_ice", JSONObject().put("candidate",
                    JSONObject().put("candidate", candidate.sdp)
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let { remoteVideo = it; remoteRenderer?.let(it::addSink); status("video_track") }
            }
        })
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("pubchat-audio", audioSource)
        audioTrack?.setEnabled(true)
        connection?.addTrack(audioTrack, listOf("pubchat"))
        if (video) {
            val enumerator = Camera2Enumerator(app)
            val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            capturer = device?.let { enumerator.createCapturer(it, null) }
            if (capturer == null) error("Камера недоступна")
            surfaceHelper = SurfaceTextureHelper.create("PubChatCamera", egl.eglBaseContext)
            videoSource = factory.createVideoSource(false)
            capturer?.initialize(surfaceHelper, app, videoSource?.capturerObserver)
            capturer?.startCapture(640, 480, 24)
            videoTrack = factory.createVideoTrack("pubchat-video", videoSource)
            localVideo = videoTrack
            connection?.addTrack(videoTrack, listOf("pubchat"))
        }
    }

    fun offer() {
        connection?.createOffer(sdpObserver { description ->
            connection?.setLocalDescription(sdpObserver(), description)
            sendDescription("call_offer", description, JSONObject().put("mode", mode))
        }, MediaConstraints())
    }

    fun answer(offer: JSONObject) {
        setRemote(offer) {
            connection?.createAnswer(sdpObserver { description ->
                connection?.setLocalDescription(sdpObserver(), description)
                sendDescription("call_answer", description)
            }, MediaConstraints())
        }
    }

    fun acceptAnswer(answer: JSONObject) { setRemote(answer) }

    fun addIce(candidate: JSONObject) {
        val ice = IceCandidate(candidate.optString("sdpMid"), candidate.optInt("sdpMLineIndex"), candidate.optString("candidate"))
        if (remoteDescriptionReady) connection?.addIceCandidate(ice) else pendingIce.add(ice)
    }

    fun attachRemote(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        remoteVideo?.addSink(renderer)
    }

    fun detachRemote(renderer: SurfaceViewRenderer) {
        remoteVideo?.removeSink(renderer)
        if (remoteRenderer === renderer) remoteRenderer = null
    }

    fun release() {
        end(false)
        egl.release()
    }

    fun mute(muted: Boolean) { audioTrack?.setEnabled(!muted) }
    fun camera(enabled: Boolean) { videoTrack?.setEnabled(enabled) }

    fun end(sendEnd: Boolean = true) {
        if (sendEnd) {
            val uid = callUid
            val peer = peerUid
            if (uid != null && peer != null) sendSignal(peer, uid, "call_end",
                JSONObject().put("reason", "ended"))
        }
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer = null
        surfaceHelper?.dispose(); surfaceHelper = null
        remoteRenderer?.let { renderer -> remoteVideo?.removeSink(renderer) }
        remoteRenderer = null
        connection?.close(); connection?.dispose(); connection = null
        videoTrack?.dispose(); videoTrack = null; localVideo = null
        videoSource?.dispose(); videoSource = null; remoteVideo = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        factory?.dispose(); factory = null
        pendingIce.clear(); remoteDescriptionReady = false
        callUid = null; peerUid = null
        status("idle")
    }

    private fun sendDescription(action: String, description: SessionDescription, extra: JSONObject = JSONObject()) {
        val uid = callUid ?: return
        val peer = peerUid ?: return
        val payload = JSONObject(extra.toString())
            .put("sdp", JSONObject().put("type", description.type.canonicalForm())
                .put("sdp", description.description))
        sendSignal(peer, uid, action, payload)
    }

    private fun setRemote(payload: JSONObject, done: () -> Unit = {}) {
        val type = when (payload.optString("type")) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> return
        }
        val description = payload.optString("sdp")
        if (description.isBlank()) return
        connection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                remoteDescriptionReady = true
                pendingIce.forEach { connection?.addIceCandidate(it) }
                pendingIce.clear()
                done()
            }
            override fun onCreateFailure(error: String?) { status("error") }
            override fun onSetFailure(error: String?) { status("error") }
        }, SessionDescription(type, description))
    }

    private fun sdpObserver(onCreated: (SessionDescription) -> Unit = {}): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) { if (sdp != null) onCreated(sdp) }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { status("error") }
        override fun onSetFailure(error: String?) { status("error") }
    }
}
