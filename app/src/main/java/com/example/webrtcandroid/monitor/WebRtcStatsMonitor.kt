package com.example.webrtcandroid.monitor

import android.util.Log
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import java.util.Timer
import java.util.TimerTask

class WebRtcStatsMonitor(
    private val peerId: String,
    private val peerConnection: PeerConnection?,
    private val type: String
) {

    private val TAG = "WebRtcStatsMonitor"
    private val creationTimeMs = System.currentTimeMillis()
    private var timer: Timer? = null

    // For Connection Setup Time
    var iceGatheringStartTimeMs: Long = -1
    var iceConnectedTimeMs: Long = -1

    // ABR / Statistics delta tracking
    private var lastFramesEncoded: Long = 0L
    private var lastEncodeTime: Double = 0.0
    private var lastBytesSent: Long = 0L
    private var lastTimestampMs: Long = 0L
    private var logTickCount = 0

    fun startMonitoring(intervalMs: Long = 1000) {
        if (peerConnection == null) {
            Log.w(TAG, "[$peerId] PeerConnection is null, cannot start monitoring.")
            return
        }

        Log.d(TAG, "[$peerId] Starting WebRTC Stats Monitoring...")
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                peerConnection.getStats { report ->
                    analyzeStats(report)
                }
            }
        }, 0, intervalMs)
    }

    fun stopMonitoring() {
        Log.d(TAG, "[$peerId] Stopping WebRTC Stats Monitoring.")
        timer?.cancel()
        timer = null
    }

    private fun analyzeStats(report: RTCStatsReport) {
        TelemetryManager.getInstance().recordWebRtcStats(peerId, report)
        val statsMap = report.statsMap
        val currentTimestampMs = System.currentTimeMillis()

        // Find outbound video RTP stream stats
        val outboundRtp = statsMap.values.find {
            it.type == "outbound-rtp" && it.members["kind"] == "video"
        }

        // Find track stats for dropped frames
        var framesDropped = 0L
        val trackStats = statsMap.values.find {
            it.type == "track" && it.members["kind"] == "video"
        }
        if (trackStats != null) {
            framesDropped = (trackStats.members["framesDropped"] as? Long) ?: 0L
        }

        if (outboundRtp != null) {
            val codecId = outboundRtp.members["codecId"] as? String
            val implementation = outboundRtp.members["encoderImplementation"] as? String ?: "unknown"
            val frameWidth = outboundRtp.members["frameWidth"] ?: 0
            val frameHeight = outboundRtp.members["frameHeight"] ?: 0
            val framesPerSecond = outboundRtp.members["framesPerSecond"] ?: 0

            val totalEncodeTime = (outboundRtp.members["totalEncodeTime"] as? Number)?.toDouble() ?: 0.0
            val framesEncoded = (outboundRtp.members["framesEncoded"] as? Number)?.toLong() ?: 0L
            val qpSum = (outboundRtp.members["qpSum"] as? Number)?.toLong() ?: 0L
            val bytesSent = (outboundRtp.members["bytesSent"] as? Number)?.toLong() ?: 0L
            val targetBitrateBps = (outboundRtp.members["targetBitrate"] as? Number)?.toDouble() ?: 0.0
            val framesSent = (outboundRtp.members["framesSent"] as? Number)?.toLong() ?: 0L

            // Parse codec mimeType
            val codecStats = if (codecId != null) statsMap[codecId] else null
            val mimeType = codecStats?.members?.get("mimeType") ?: "unknown"

            // Compute Delta Metrics
            val deltaFrames = framesEncoded - lastFramesEncoded
            val deltaTime = totalEncodeTime - lastEncodeTime
            val deltaBytes = bytesSent - lastBytesSent
            val timeElapsedSec = if (lastTimestampMs > 0L) {
                (currentTimestampMs - lastTimestampMs) / 1000.0
            } else {
                1.0
            }

            var currentEncodeLatencyMs = 0.0
            if (deltaFrames > 0) {
                currentEncodeLatencyMs = (deltaTime / deltaFrames) * 1000.0
            }

            val realTimeBitrateKbps = if (deltaBytes > 0 && timeElapsedSec > 0.0) {
                (deltaBytes * 8.0) / timeElapsedSec / 1000.0
            } else {
                0.0
            }

            val targetBitrateKbps = targetBitrateBps / 1000.0

            // Frame Drop Rate
            val totalFrames = framesDropped + framesSent
            val dropRate = if (totalFrames > 0) {
                (framesDropped.toDouble() / totalFrames) * 100
            } else {
                0.0
            }

            // Update baseline maps
            lastFramesEncoded = framesEncoded
            lastEncodeTime = totalEncodeTime
            lastBytesSent = bytesSent
            lastTimestampMs = currentTimestampMs

            // QP Average Quality calculation
            val avgQp = if (framesEncoded > 0) qpSum.toDouble() / framesEncoded else 0.0

            // Print Clean Consolidated Log every 5 seconds (5 ticks of 1-second measurements)
            logTickCount++
            if (logTickCount >= 5) {
                Log.i("HW_ENCODER_LOAD", """
                    ========================================================
                    [Peer ID: $peerId] 실시간 미디어 성능 리포트 (5초 주기 출력)
                    - 가속 칩셋 엔진: $implementation ($mimeType)
                    - 프레임 드롭률: ${String.format("%.2f", dropRate)}% ($framesDropped dropped / $totalFrames sent+dropped)
                    - 실시간 프레임당 인코딩 지연: ${String.format("%.2f", currentEncodeLatencyMs)} ms (누적평균: ${String.format("%.2f", if (framesEncoded > 0) (totalEncodeTime / framesEncoded) * 1000 else 0.0)} ms)
                    - 실시간 실제 전송 속도: ${String.format("%.2f", realTimeBitrateKbps)} Kbps
                    - WebRTC 할당 목표 비트레이트: ${String.format("%.2f", targetBitrateKbps)} Kbps
                    - 현재 처리 해상도: ${frameWidth}x${frameHeight} @${framesPerSecond}fps
                    - 평균 화질 지표 (QP): ${String.format("%.1f", avgQp)} (낮을수록 선명, <25: 우수, 25~35: 보통, >35: 열화)
                    - 누적 압축 프레임: ${framesEncoded}장 | 누적 QP 양자화 값: $qpSum
                    ========================================================
                """.trimIndent())
                logTickCount = 0
            }
        }
    }

    // Call this from PeerConnection.Observer.onIceGatheringChange when state is GATHERING
    fun onIceGatheringStarted() {
        if (iceGatheringStartTimeMs == -1L) {
            iceGatheringStartTimeMs = System.currentTimeMillis()
        }
    }

    // Call this from PeerConnection.Observer.onIceConnectionChange when state is CONNECTED
    fun onIceConnected() {
        if (iceConnectedTimeMs == -1L) {
            iceConnectedTimeMs = System.currentTimeMillis()
            if (type == "sendonly") {
                val startTime = if (iceGatheringStartTimeMs != -1L) iceGatheringStartTimeMs else creationTimeMs
                val setupTime = iceConnectedTimeMs - startTime
                Log.i("WebRtcStatsMonitor", "[$peerId] 송출 연결 수립 완료 소요시간 (ICE 시작 -> Connected): $setupTime ms")
                TelemetryManager.getInstance().recordConnectionSetupTime(peerId, setupTime)
            } else {
                val startTime = if (iceGatheringStartTimeMs != -1L) iceGatheringStartTimeMs else creationTimeMs
                val setupTime = iceConnectedTimeMs - startTime
                Log.i("WebRtcStatsMonitor", "[$peerId] ICE 연결 성공 소요시간: $setupTime ms")
            }
        }
    }

    var mediaReceivedTimeMs: Long = -1
    var connectionSetupTimeMs: Long = -1

    fun onMediaReceived() {
        if (mediaReceivedTimeMs == -1L) {
            mediaReceivedTimeMs = System.currentTimeMillis()
            if (type == "recvonly") {
                val startTime = if (iceGatheringStartTimeMs != -1L) iceGatheringStartTimeMs else creationTimeMs
                val setupTime = mediaReceivedTimeMs - startTime
                connectionSetupTimeMs = setupTime
                Log.i("WebRtcStatsMonitor", "[$peerId] 수신 연결 수립 완료 소요시간 (ICE 시작 -> 미디어 수신): $setupTime ms")
                TelemetryManager.getInstance().recordConnectionSetupTime(peerId, setupTime)
            }
        }
    }
}
