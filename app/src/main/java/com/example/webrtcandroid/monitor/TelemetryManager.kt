package com.example.webrtcandroid.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import com.example.webrtcandroid.HardwareEncoderConfig
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.RTCStatsReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


/* 파일은 스마트폰 내부의 앱 전용 공간에 다음 양식으로 저장된다: webrtc_dumps/telemetry_[인코더모드]_[코덱]_[해상도]_[FPS]_[피어수]_[타임스탬프].json
예시: telemetry_SharedHW_H265_1280x800_60fps_15peers_20260629_210255.json

PC로 원클릭 추출:
케이블을 연결한 뒤 PC 터미널에서 다음 명령어를 실행하여 폰에 저장된 모든 테스트 결과 로우 데이터를 PC 폴더로 한 번에 당겨온다.
adb pull /sdcard/Android/data/com.example.webrtcandroid/files/webrtc_dumps/ ./test_results/
*/

class TelemetryManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TelemetryManager"
        @Volatile
        private var instance: TelemetryManager? = null

        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(TelemetryManager::class.java) {
                    if (instance == null) {
                        instance = TelemetryManager(context.applicationContext)
                    }
                }
            }
        }

        fun getInstance(): TelemetryManager {
            return instance ?: throw IllegalStateException("TelemetryManager is not initialized. Call initialize(context) first.")
        }
    }

    private var isSessionActive = false
    private var sessionStartTimeMs: Long = 0
    private var targetCodec = "None"
    private var encoderMode = "Shared HW"
    private var roomName = ""

    // CPU calculations
    private var lastRealtime: Long = 0
    private var lastCpuTime: Long = 0
    private val numCores = Runtime.getRuntime().availableProcessors()

    // Logs buffer
    private val systemLogs = ArrayList<JSONObject>()
    private val peerWebRtcLogs = ConcurrentHashMap<String, ArrayList<JSONObject>>()
    private val connectionSetupTimes = ConcurrentHashMap<String, Long>()

    private var scheduler: ScheduledExecutorService? = null
    private var peerCountProvider: (() -> Int)? = null

    fun setPeerCountProvider(provider: () -> Int) {
        this.peerCountProvider = provider
    }

    fun startSession(codec: String, mode: String, room: String) {
        if (isSessionActive) {
            Log.w(TAG, "Telemetry session is already active. Ignoring start request.")
            return
        }

        targetCodec = codec
        encoderMode = mode
        roomName = room
        sessionStartTimeMs = System.currentTimeMillis()
        isSessionActive = true

        lastRealtime = SystemClock.elapsedRealtime()
        lastCpuTime = android.os.Process.getElapsedCpuTime()

        systemLogs.clear()
        peerWebRtcLogs.clear()
        connectionSetupTimes.clear()

        Log.i(TAG, "Starting Telemetry Session: Codec=$codec, Mode=$mode, Room=$room")

        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleAtFixedRate({
            try {
                collectSystemMetrics()
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting system metrics", e)
            }
        }, 0, 1000, TimeUnit.MILLISECONDS)
    }
    fun recordConnectionSetupTime(peerId: String, setupTimeMs: Long) {
        connectionSetupTimes[peerId] = setupTimeMs
    }

    fun stopSessionAndSave(numPeers: Int) {
        if (!isSessionActive) {
            return
        }
        isSessionActive = false

        scheduler?.shutdown()
        scheduler = null

        val endTimeMs = System.currentTimeMillis()
        val durationSec = (endTimeMs - sessionStartTimeMs) / 1000.0

        Log.i(TAG, "Stopping Telemetry Session. Duration: $durationSec seconds. Saving logs...")

        try {
            val root = JSONObject()

            // 1. Metadata
            val meta = JSONObject()
            meta.put("target_codec", targetCodec)
            meta.put("encoder_mode", encoderMode)
            meta.put("target_resolution", "${HardwareEncoderConfig.videoWidth}x${HardwareEncoderConfig.videoHeight}")
            meta.put("target_fps", HardwareEncoderConfig.videoFps)
            meta.put("num_peers", numPeers)
            meta.put("room_name", roomName)
            
            val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
            df.timeZone = TimeZone.getTimeZone("UTC")
            meta.put("start_time", df.format(Date(sessionStartTimeMs)))
            meta.put("end_time", df.format(Date(endTimeMs)))
            meta.put("duration_seconds", durationSec)
            root.put("test_metadata", meta)

            // 2. System logs
            val sysArray = JSONArray()
            synchronized(systemLogs) {
                systemLogs.forEach { sysArray.put(it) }
            }
            root.put("system_logs", sysArray)

            // 3. WebRTC logs
            val webrtcObj = JSONObject()
            peerWebRtcLogs.forEach { (peerId, list) ->
                val peerArray = JSONArray()
                synchronized(list) {
                    list.forEach { peerArray.put(it) }
                }
                webrtcObj.put(peerId, peerArray)
            }
            root.put("webrtc_logs", webrtcObj)

            // 4. Connection Setup Times
            val setupTimesObj = JSONObject()
            connectionSetupTimes.forEach { (peerId, setupTimeMs) ->
                setupTimesObj.put(peerId, setupTimeMs)
            }
            root.put("connection_setup_times", setupTimesObj)

            // 4. Save to file
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "webrtc_dumps")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val cleanMode = encoderMode.replace(" ", "")
            val fileName = "telemetry_${cleanMode}_${targetCodec}_${HardwareEncoderConfig.videoWidth}x${HardwareEncoderConfig.videoHeight}_${HardwareEncoderConfig.videoFps}fps_${numPeers}peers_${timeStamp}.json"
            val file = File(dir, fileName)
            file.writeText(root.toString(2))
            Log.i(TAG, "Telemetry report successfully saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build and save telemetry JSON", e)
        }
    }

    private fun collectSystemMetrics() {
        if (!isSessionActive) return

        val elapsedMs = System.currentTimeMillis() - sessionStartTimeMs
        val sysObj = JSONObject()

        sysObj.put("elapsed_ms", elapsedMs)

        // Active peer count over time
        val peers = peerCountProvider?.invoke() ?: 0
        sysObj.put("active_peers", peers)

        // Shared Video Encoder Factory Target Bitrate Logging
        val sharedFactory = com.example.webrtcandroid.SharedVideoEncoderFactory.instance
        if (sharedFactory != null) {
            val logVal = sharedFactory.latestBitrateLogs[targetCodec] ?: sharedFactory.latestBitrateLogs.values.firstOrNull()
            if (logVal != null) {
                sysObj.put("shared_encoder_min_bps", logVal.minBps)
                sysObj.put("shared_encoder_max_bps", logVal.maxBps)
                sysObj.put("shared_encoder_target_avg_bps", logVal.targetAvgBps)
            } else {
                sysObj.put("shared_encoder_min_bps", JSONObject.NULL)
                sysObj.put("shared_encoder_max_bps", JSONObject.NULL)
                sysObj.put("shared_encoder_target_avg_bps", JSONObject.NULL)
            }
        } else {
            sysObj.put("shared_encoder_min_bps", JSONObject.NULL)
            sysObj.put("shared_encoder_max_bps", JSONObject.NULL)
            sysObj.put("shared_encoder_target_avg_bps", JSONObject.NULL)
        }

        // CPU & Memory
        val cpuUsage = getCpuUsage()
        val memMb = getMemoryUsageMb()
        sysObj.put("cpu_usage_pct", cpuUsage)
        sysObj.put("memory_usage_mb", memMb)

        // GPU
        val gpuUsage = getGpuUsage()
        sysObj.put("gpu_usage_pct", if (gpuUsage >= 0) gpuUsage else JSONObject.NULL)

        // Temperatures & Battery
        val cpuTemp = getCpuTemperature()
        sysObj.put("cpu_temp_c", if (cpuTemp >= 0) cpuTemp else JSONObject.NULL)

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100.0 / scale) else -1.0
        val batteryTemp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
        sysObj.put("battery_pct", if (batteryPct >= 0) batteryPct else JSONObject.NULL)
        sysObj.put("battery_temp_c", if (batteryTemp >= 0) batteryTemp else JSONObject.NULL)

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentMicroAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = currentMicroAmps / 1000.0
        sysObj.put("battery_current_ma", currentMa)

        synchronized(systemLogs) {
            systemLogs.add(sysObj)
        }
    }

    private fun getCpuUsage(): Double {
        val elapsedRealtime = SystemClock.elapsedRealtime()
        val cpuTime = android.os.Process.getElapsedCpuTime()

        val deltaRealtime = elapsedRealtime - lastRealtime
        val deltaCpuTime = cpuTime - lastCpuTime

        lastRealtime = elapsedRealtime
        lastCpuTime = cpuTime

        return if (deltaRealtime > 0) {
            (deltaCpuTime.toDouble() / deltaRealtime) * 100.0 / numCores
        } else {
            0.0
        }
    }

    private fun getMemoryUsageMb(): Double {
        try {
            val statm = File("/proc/self/statm").readText().trim()
            val parts = statm.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val residentPages = parts[1].toLong()
                val pageSizeBytes = 4096 // 4KB page size
                return (residentPages * pageSizeBytes) / (1024.0 * 1024.0)
            }
        } catch (e: Exception) {
            // Ignore
        }
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0)
    }

    private fun getGpuUsage(): Double {
        try {
            // Mali GPU
            val maliFiles = arrayOf(
                "/sys/kernel/gpu/gpu_busy",
                "/sys/module/mali_kbase/parameters/mali_gpu_utilization",
                "/sys/module/mali/parameters/mali_gpu_utilization"
            )
            for (path in maliFiles) {
                val file = File(path)
                if (file.exists()) {
                    val content = file.readText().trim()
                    val match = Regex("(\\d+)").find(content)
                    if (match != null) {
                        return match.groupValues[1].toDouble()
                    }
                }
            }

            // Adreno GPU
            val adrenoFile = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
            if (adrenoFile.exists()) {
                val content = adrenoFile.readText().trim()
                val parts = content.split("\\s+".toRegex())
                if (parts.size == 2) {
                    val active = parts[0].toDouble()
                    val total = parts[1].toDouble()
                    if (total > 0) {
                        return (active / total) * 100.0
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return -1.0
    }

    private fun getCpuTemperature(): Double {
        // 삼성 AP 써미스터 노드 우선 조회 (SELinux 권한 제한 우회)
        val samsungApFiles = arrayOf(
            "/sys/devices/virtual/sec/sec-ap-thermistor/temperature",
            "/sys/class/hwmon/hwmon0/device/temperature"
        )
        for (path in samsungApFiles) {
            try {
                val file = File(path)
                if (file.exists()) {
                    val raw = file.readText().trim().toDouble()
                    return if (raw > 150) raw / 10.0 else raw
                }
            } catch (e: Exception) {
                // 무시
            }
        }

        // 표준 서멀 존 탐색 (폴백)
        for (i in 0..20) {
            try {
                val typeFile = File("/sys/class/thermal/thermal_zone$i/type")
                if (typeFile.exists()) {
                    val type = typeFile.readText().trim().lowercase()
                    if (type.contains("cpu") || type.contains("ap") || type.contains("tsens") || type.contains("soc")) {
                        val tempFile = File("/sys/class/thermal/thermal_zone$i/temp")
                        if (tempFile.exists()) {
                            val tempRaw = tempFile.readText().trim().toDouble()
                            return if (tempRaw > 1000) tempRaw / 1000.0 else tempRaw
                        }
                    }
                }
            } catch (e: Exception) {
                // 무시
            }
        }
        return -1.0
    }

    fun recordWebRtcStats(peerId: String, report: RTCStatsReport) {
        if (!isSessionActive) return

        val statsMap = report.statsMap
        val elapsedMs = System.currentTimeMillis() - sessionStartTimeMs

        val statsJson = JSONObject()
        statsJson.put("elapsed_ms", elapsedMs)

        // 1. Outbound-RTP (Video)
        val outboundRtp = statsMap.values.find {
            it.type == "outbound-rtp" && it.members["kind"] == "video"
        }
        if (outboundRtp != null) {
            val members = outboundRtp.members
            statsJson.put("bytes_sent", members["bytesSent"] ?: 0)
            statsJson.put("packets_sent", members["packetsSent"] ?: 0)
            statsJson.put("frames_encoded", members["framesEncoded"] ?: 0)
            statsJson.put("frames_sent", members["framesSent"] ?: 0)
            statsJson.put("total_encode_time_sec", members["totalEncodeTime"] ?: 0.0)
            statsJson.put("qp_sum", members["qpSum"] ?: 0)
            statsJson.put("target_bitrate_bps", members["targetBitrate"] ?: 0.0)
            statsJson.put("frame_width", members["frameWidth"] ?: 0)
            statsJson.put("frame_height", members["frameHeight"] ?: 0)
            statsJson.put("fps", members["framesPerSecond"] ?: 0)
            statsJson.put("encoder_implementation", members["encoderImplementation"] ?: "unknown")
            statsJson.put("nack_count", members["nackCount"] ?: 0)
            statsJson.put("pli_count", members["pliCount"] ?: 0)
            statsJson.put("fir_count", members["firCount"] ?: 0)

            val codecId = members["codecId"] as? String
            val codecStats = if (codecId != null) statsMap[codecId] else null
            statsJson.put("mime_type", codecStats?.members?.get("mimeType") ?: "unknown")
        }

        // 2. Track (Video)
        val trackStats = statsMap.values.find {
            it.type == "track" && it.members["kind"] == "video"
        }
        if (trackStats != null) {
            statsJson.put("frames_dropped", trackStats.members["framesDropped"] ?: 0)
        }

        // 3. Remote-Inbound-RTP (Receiver Feedback)
        val remoteInboundRtp = statsMap.values.find {
            it.type == "remote-inbound-rtp" && it.members["kind"] == "video"
        }
        if (remoteInboundRtp != null) {
            val members = remoteInboundRtp.members
            statsJson.put("fraction_lost", members["fractionLost"] ?: 0.0)
            statsJson.put("packets_lost", members["packetsLost"] ?: 0)
            statsJson.put("rtt_sec", members["roundTripTime"] ?: 0.0)
            statsJson.put("jitter_sec", members["jitter"] ?: 0.0)
        }

        // 4. Candidate-Pair
        val candidatePair = statsMap.values.find {
            it.type == "candidate-pair" && it.members["state"] == "succeeded"
        }
        if (candidatePair != null) {
            val members = candidatePair.members
            statsJson.put("current_rtt_sec", members["currentRoundTripTime"] ?: 0.0)
            statsJson.put("available_outgoing_bitrate_bps", members["availableOutgoingBitrate"] ?: 0.0)
        }

        val peerBuffer = peerWebRtcLogs.getOrPut(peerId) { ArrayList() }
        synchronized(peerBuffer) {
            peerBuffer.add(statsJson)
        }
    }
}
