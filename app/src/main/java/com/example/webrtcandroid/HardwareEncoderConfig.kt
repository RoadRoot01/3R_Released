package com.example.webrtcandroid

import android.media.MediaCodecList
import android.util.Log

// 코덱 및 인코더 설정

object HardwareEncoderConfig {
    @JvmStatic var videoWidth = 1280
    @JvmStatic var videoHeight = 800
    @JvmStatic var videoFps = 60
    @JvmStatic var videoBitrateKbps = 10000
    fun logSupportedEncoders() {
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val codecInfos = codecList.codecInfos

            for (info in codecInfos) {
                if (info.isEncoder) {
                    val types = info.supportedTypes
                    for (type in types) {
                        if (type.equals("video/avc", ignoreCase = true)) {
                            val caps = info.getCapabilitiesForType(type)
                            val maxInstances = caps.maxSupportedInstances
                            Log.d("CodecLog", "인코더: ${info.name} | 최대 인스턴스: $maxInstances")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CodecLog", "코덱 정보 조회 중 에러: ${e.message}")
        }
    }
}
