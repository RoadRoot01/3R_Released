package com.example.webrtcandroid

import android.util.Log
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap
import com.example.webrtcandroid.monitor.TelemetryManager


// 코드 역할 : PeerConnection 생성 및 C++ 네이티브 스레드 자원 정리하는 코드

// SDP 처리를 위한 유틸리티 클래스 (보일러플레이트 코드 감소)
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}

class NativeTransportManager(
    private val factory: PeerConnectionFactory,
    private val serverUrl: String,
    private val roomName: String,
    private var localVideoTrack: VideoTrack?, // val에서 var로 수정함. 트랙 교체 기능 추가를 위함임.
    private val preferredCodec: String = "None", // 코덱 선택용 옵션 추가 (기본값: None)
    private val encoderMode: String = "Shared HW", // 인코더 모드 주입 받음
    private val onConnectedCallback: () -> Unit, // 추가
    private val onRemoteTrackAdded: (String, VideoTrack) -> Unit,
    private val onRemoteTrackRemoved: (String) -> Unit
) : SignalingCallback {

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val pendingCandidates = ConcurrentHashMap<String, MutableList<IceCandidate>>()
    private val gatheredCandidates = ConcurrentHashMap<String, MutableList<IceCandidate>>()
    private val statsMonitors = ConcurrentHashMap<String, com.example.webrtcandroid.monitor.WebRtcStatsMonitor>()
    private lateinit var signalingClient: SignalingClient

    private val rtcConfig = PeerConnection.RTCConfiguration(
        listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    fun startStatsMonitoring() {
        // 이 모니터링은 이제 WebRtcStatsMonitor로 옮김.
    }

    fun connect() {
        TelemetryManager.getInstance().setPeerCountProvider { peerConnections.size }
        TelemetryManager.getInstance().startSession(preferredCodec, encoderMode, roomName)
        signalingClient = SignalingClient(serverUrl, roomName, this)
        signalingClient.connect()
    }

    override fun onConnected() {
        onConnectedCallback()
    }

    override fun onMyIdReceived(id: String) {
        // 내 ID 저장 로직 <추가 구현 필요>
    }

    override fun onRootBroadcaster() {
        // 최상위 브로드캐스터 처리 <추가 구현 필요>
    }

    override fun onNewParent(parentId: String) {
        if (peerConnections.containsKey(parentId)) return

        val pc = createPeerConnection(parentId, "recvonly") ?: return
        peerConnections[parentId] = pc

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d("WebRTC_SDP", "원본 Local Offer SDP 생성됨:\n${sdp.description}")
                val filteredSdp = SessionDescription(sdp.type, filterSdpCodecs(sdp.description, preferredCodec))
                Log.d("WebRTC_SDP", "필터링된 Local Offer SDP 적용:\n${filteredSdp.description}")
                pc.setLocalDescription(SimpleSdpObserver(), filteredSdp)
                signalingClient.sendOffer(parentId, filteredSdp)
            }
        }, MediaConstraints())
    }

    override fun onOfferReceived(from: String, sdp: SessionDescription) {
        // 상대방 Offer SDP에서 원하지 않는 비디오 코덱을 제거한 뒤 setRemoteDescription에 전달
        // WebRTC 엔진은 "상대방이 해당 코덱만 지원한다"고 인식하여, Answer를 자연스럽게 해당 코덱으로 생성
        val filteredDescription = filterSdpCodecs(sdp.description, preferredCodec)
        val filteredOfferSdp = SessionDescription(sdp.type, filteredDescription)
        
        // 필터링 적용 여부에 따라 명확한 흐름 추적을 위한 로깅 수행
        if (filteredDescription != sdp.description) {
            Log.d("WebRTC_SDP", "수신 Offer SDP 코덱 필터 적용 완료. 강제 코덱: $preferredCodec")
            Log.d("WebRTC_SDP", "필터링된 Remote Offer SDP:\n${filteredOfferSdp.description}")
        } else {
            Log.d("WebRTC_SDP", "수신 Offer SDP 코덱 필터 미적용 (강제 코덱 미지정 또는 상대가 코덱을 지원하지 않음)")
        }

        val pc = createPeerConnection(from, "sendonly") ?: return
        peerConnections[from] = pc

        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                flushPendingCandidates(from)

                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        Log.d("WebRTC_SDP", "생성된 Answer SDP:\n${sdp.description}")
                        pc.setLocalDescription(SimpleSdpObserver(), sdp)
                        signalingClient.sendAnswer(from, sdp)
                    }
                }, MediaConstraints())
            }
        }, filteredOfferSdp) // 필터링된 Offer를 사용
    }

    override fun onAnswerReceived(from: String, sdp: SessionDescription) {
        val pc = peerConnections[from]
        pc?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                flushPendingCandidates(from)
            }
        }, sdp)
    }

    override fun onIceCandidateReceived(from: String, candidate: IceCandidate) {
        val pc = peerConnections[from]
        if (pc?.remoteDescription != null) {
            pc.addIceCandidate(candidate)
        } else {
            val candidates = pendingCandidates.getOrPut(from) { mutableListOf() }
            candidates.add(candidate)
        }
    }

    override fun onForceDisconnectRoom() {
        TelemetryManager.getInstance().stopSessionAndSave(peerConnections.size)
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        pendingCandidates.clear()
        gatheredCandidates.clear()
    }

    private fun createPeerConnection(peerId: String, type: String): PeerConnection? {
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val list = gatheredCandidates.getOrPut(peerId) { mutableListOf() }
                list.add(candidate)
                signalingClient.sendCandidate(peerId, candidate)
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.GATHERING) {
                    statsMonitors[peerId]?.onIceGatheringStarted()
                }
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    android.util.Log.d("WebRTC", "ICE Gathering Complete for $peerId")
                }
            }
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    track.addSink(object : VideoSink {
                        private var frameReceived = false
                        override fun onFrame(frame: VideoFrame) {
                            if (!frameReceived) {
                                frameReceived = true
                                statsMonitors[peerId]?.onMediaReceived()
                            }
                        }
                    })
                    onRemoteTrackAdded(peerId, track)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.FAILED ||
                    newState == PeerConnection.PeerConnectionState.DISCONNECTED ||
                    newState == PeerConnection.PeerConnectionState.CLOSED) {

                    onRemoteTrackRemoved(peerId)

                    val pc = peerConnections.remove(peerId)
                    statsMonitors.remove(peerId)?.stopMonitoring()

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            pc?.close()
                            pc?.dispose()
                            android.util.Log.d("WebRTC", "Peer $peerId 인코더 및 자원 해제 완료 (안전 처리)")
                        } catch (e: Exception) {
                            android.util.Log.e("WebRTC", "Error disposing PC for $peerId", e)
                        }
                    }

                    pendingCandidates.remove(peerId)
                    gatheredCandidates.remove(peerId)

                    android.util.Log.d("WebRTC", "Peer $peerId 인코더 및 자원 해제 완료")
                }
            }

            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    statsMonitors[peerId]?.onIceConnected()
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }

        val pc = factory.createPeerConnection(rtcConfig, observer)

        if (pc != null) {
            val monitor = com.example.webrtcandroid.monitor.WebRtcStatsMonitor(peerId, pc, type)
            statsMonitors[peerId] = monitor
            monitor.startMonitoring(1000)
        }

        if (type == "recvonly") {
            pc?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            pc?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        } else if (type == "sendonly") {
            localVideoTrack?.let { track ->
                val sender = pc?.addTrack(track, listOf("android_video_stream"))

                sender?.let { rtpSender ->
                    val parameters = rtpSender.parameters
                    if (parameters.encodings.isNotEmpty()) {
                        val encoding = parameters.encodings[0]

                        encoding.maxFramerate = HardwareEncoderConfig.videoFps
                        encoding.maxBitrateBps = HardwareEncoderConfig.videoBitrateKbps * 1000

                        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                        rtpSender.parameters = parameters
                        android.util.Log.d("WebRTC", "Sender parameters updated: FPS=${HardwareEncoderConfig.videoFps}, Bitrate=${HardwareEncoderConfig.videoBitrateKbps}kbps, Degradation=MAINTAIN_RESOLUTION")
                    }
                }

                // RtpSender 자바 객체 주소 불일치 문제를 우회하여 확실하게 비디오 트랜시버를 찾도록 미디어 타입 조건으로 검색함
                val transceiver = pc?.transceivers?.find { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }

                val capabilities = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                val allCodecs = capabilities.codecs

                val codecNames = allCodecs.map { it.name }
                Log.d("WebRTC_Codec", "기기 지원 비디오 코덱 목록: $codecNames, 사용자가 선택한 우선순위 코덱: $preferredCodec")

                if (allCodecs.isEmpty()) {
                    Log.w("WebRTC_Codec", "RtpSenderCapabilities에서 코덱 목록을 찾을 수 없음.")
                } else if (!preferredCodec.equals("None", ignoreCase = true)) {
                    // [기존 코드 - 주석 처리]
                    /*
                    val preferredOrder = allCodecs.sortedByDescending {
                        it.name.equals(preferredCodec, ignoreCase = true)
                    }
                    val error = transceiver?.setCodecPreferences(preferredOrder)
                    */

                    // 1차 비디오 코덱 목록 (제거 대상 판별용)
                    val PRIMARY_VIDEO_CODECS = setOf("VP8", "VP9", "H264", "H265", "HEVC", "AV1")

                    // 선택한 코덱 + 보조 코덱(RTX, RED, ULPFEC 등)은 보존하고, 다른 1차 비디오 코덱만 제거
                    val filteredOrder = allCodecs.filter { codec ->
                        val isPrimaryVideoCodec = PRIMARY_VIDEO_CODECS.any { it.equals(codec.name, ignoreCase = true) }
                        // 1차 비디오 코덱이면 → 선택한 코덱만 통과, 나머지 제거
                        // 보조 코덱(rtx, red, ulpfec 등)이면 → 무조건 통과
                        !isPrimaryVideoCodec || codec.name.equals(preferredCodec, ignoreCase = true)
                    }
                    Log.d("WebRTC_Codec", "필터 결과: ${filteredOrder.map { "${it.name}(${it.mimeType})" }}")
                    val error = transceiver?.setCodecPreferences(filteredOrder)

                    if (transceiver == null) {
                        Log.e("WebRTC_Codec", "비디오 transceiver를 찾을 수 없어 코덱을 설정하지 못함.")
                    } else if (error == null) {
                        Log.i("WebRTC_Codec", "코덱 우선순위 설정 성공. 최우선 적용 코덱: ${filteredOrder.firstOrNull()?.name}")
                    } else {
                        Log.e("WebRTC_Codec", "코덱 우선순위 설정 실패. 에러: $error")
                    }
                }
            }
        }
        return pc
    }

    private fun flushPendingCandidates(peerId: String) {
        val pc = peerConnections[peerId]
        val candidates = pendingCandidates.remove(peerId)
        if (pc != null && candidates != null) {
            candidates.forEach { pc.addIceCandidate(it) }
        }
    }

    fun changeVideoTrack(newTrack: VideoTrack?) {
        this.localVideoTrack = newTrack
        if (newTrack == null) return

        peerConnections.values.forEach { pc ->
            val videoSender = pc.senders.find { it.track()?.kind() == "video" }
            if (videoSender != null) {
                videoSender.setTrack(newTrack, false)
            }
        }
    }

    fun destroy() {
        TelemetryManager.getInstance().stopSessionAndSave(peerConnections.size)
        if (::signalingClient.isInitialized) {
            signalingClient.disconnect()
        }
        
        statsMonitors.values.forEach { it.stopMonitoring() }
        statsMonitors.clear()
        
        // Double-dispose 방지: 피어 커넥션 리스트를 복사한 후 맵을 먼저 비운다.
        val pcsToDispose = peerConnections.values.toList()
        peerConnections.clear()
        pendingCandidates.clear()
        gatheredCandidates.clear()

        pcsToDispose.forEach { pc ->
            try {
                pc.close()
                pc.dispose()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 특정 비디오 코덱만 강제하기 위해 SDP에서 다른 비디오 코덱 관련 페이로드 정보를 삭제하는 헬퍼 함수
    private fun filterSdpCodecs(sdp: String, preferred: String): String {
        if (preferred.equals("None", ignoreCase = true)) {
            return sdp
        }
        val targetCodec = preferred.uppercase()

        // 안드로이드 WebRTC SDK는 줄바꿈을 \r\n 또는 \n으로 반환할 수 있음
        val lineBreak = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(lineBreak)
        val result = mutableListOf<String>()
        val payloadTypesToRemove = mutableSetOf<String>()

        // 1단계: 상대방 SDP에 우리가 원하는 코덱이 실제로 존재하는지 확인 및 제거할 비디오 코덱의 페이로드 타입(PT) 식별
        var hasTargetCodec = false
        for (line in lines) {
            if (line.startsWith("a=rtpmap:")) {
                val parts = line.substring(9).split(" ")
                if (parts.size >= 2) {
                    val codecName = parts[1].split("/")[0] // "VP8/90000" → "VP8"
                    if (codecName.equals(targetCodec, ignoreCase = true)) {
                        hasTargetCodec = true
                        break
                    }
                }
            }
        }

        // 상대방이 지정된 코덱을 지원하지 않는 경우, 필터링을 취소하고 원본 SDP를 사용하여 연결 유지
        if (!hasTargetCodec) {
            Log.w("WebRTC_SDP", "상대방이 지정 코덱($preferred)을 지원하지 않습니다. 필터링을 생략하고 기본 협상을 진행합니다.")
            return sdp
        }

        for (line in lines) {
            if (line.startsWith("a=rtpmap:")) {
                val parts = line.substring(9).split(" ")
                if (parts.size >= 2) {
                    val pt = parts[0]
                    val codecName = parts[1].split("/")[0]
                    val PRIMARY_VIDEO_CODECS = setOf("VP8", "VP9", "H264", "H265", "HEVC", "AV1")
                    val isPrimary = PRIMARY_VIDEO_CODECS.any { it.equals(codecName, ignoreCase = true) }
                    // 1차 비디오 코덱 중 원하는 코덱이 아닌 것의 PT를 수집
                    if (isPrimary && !codecName.equals(targetCodec, ignoreCase = true)) {
                        payloadTypesToRemove.add(pt)
                    }
                }
            }
        }

        // RTX 중 제거 대상 코덱에 종속된 것도 함께 제거 (apt= 파라미터 기준)
        for (line in lines) {
            if (line.startsWith("a=fmtp:")) {
                val parts = line.substring(7).split(" ", limit = 2)
                if (parts.size >= 2) {
                    val pt = parts[0]
                    val params = parts[1]
                    // apt=XX 형태에서 XX가 제거 대상이면 이 RTX PT도 제거
                    val aptMatch = Regex("apt=(\\d+)").find(params)
                    if (aptMatch != null && aptMatch.groupValues[1] in payloadTypesToRemove) {
                        payloadTypesToRemove.add(pt)
                    }
                }
            }
        }

        Log.d("WebRTC_SDP", "SDP 필터: 제거 대상 PT=$payloadTypesToRemove, 보존 코덱=$targetCodec")

        // 2단계: m=video 라인에서 제거 대상 PT를 삭제하고, 관련 속성 라인도 스킵
        var insideVideoSection = false
        for (line in lines) {
            if (line.startsWith("m=video ")) {
                insideVideoSection = true
                val tokens = line.split(" ")
                if (tokens.size > 3) {
                    val prefix = tokens.subList(0, 3).joinToString(" ")
                    val pts = tokens.subList(3, tokens.size).filter { it !in payloadTypesToRemove }
                    result.add("$prefix ${pts.joinToString(" ")}")
                    continue
                }
            } else if (line.startsWith("m=")) {
                insideVideoSection = false
            }

            if (insideVideoSection) {
                var shouldSkip = false
                for (pt in payloadTypesToRemove) {
                    if (line.startsWith("a=rtpmap:$pt ") || line.startsWith("a=fmtp:$pt ") || line.startsWith("a=rtcp-fb:$pt ")) {
                        shouldSkip = true
                        break
                    }
                }
                if (shouldSkip) continue
            }
            result.add(line)
        }

        return result.joinToString(lineBreak)
    }
}
