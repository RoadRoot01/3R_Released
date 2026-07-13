package org.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

// 이 코드는 아직 구현 전. 테스트 용. 현재 실제로 작동하지 않으므로 참고 X

/**
 * WebRTC가 하드웨어 인코더뿐만 아니라, 안드로이드 OS에 내장된 소프트웨어 인코더도
 * Java 레벨의 HardwareVideoEncoder로 감싸서 사용하도록 강제하는 커스텀 팩토리입니다.
 */
class CustomMediaCodecVideoEncoderFactory(
    private val sharedContext: EglBase.Context?,
    private val enableIntelVp8Encoder: Boolean,
    private val enableH264HighProfile: Boolean
) : VideoEncoderFactory {

    // 진짜 하드웨어 코덱을 찾아주는 기본 팩토리를 내부에 하나 품습니다.
    private val hwFactory = HardwareVideoEncoderFactory(sharedContext, enableIntelVp8Encoder, enableH264HighProfile)

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        // 1. 우선 기기의 칩셋 하드웨어 인코더가 있는지 찾습니다.
        val hwEncoder = hwFactory.createEncoder(info)
        if (hwEncoder != null) {
            return hwEncoder // 하드웨어 인코더가 있다면 그대로 반환
        }

        // 2. 하드웨어 인코더가 없다면, C++ 코덱(WrappedNativeVideoEncoder)으로 넘어가지 않게 방어하고
        // 안드로이드 OS의 소프트웨어 MediaCodec을 찾아서 HardwareVideoEncoder에 주입합니다!
        return createSoftwareMediaCodecEncoder(info)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val hwCodecs = hwFactory.supportedCodecs.toMutableList()
        val swCodecs = SoftwareVideoEncoderFactory().supportedCodecs

        // 기기가 지원하는 하드웨어 + 소프트웨어 코덱 목록 병합
        val combined = LinkedHashSet<VideoCodecInfo>()
        combined.addAll(hwCodecs)
        combined.addAll(swCodecs)
        return combined.toTypedArray()
    }

    private fun createSoftwareMediaCodecEncoder(info: VideoCodecInfo): VideoEncoder? {
        val mimeType = when (info.name.uppercase()) {
            "VP8" -> "video/x-vnd.on2.vp8"
            "VP9" -> "video/x-vnd.on2.vp9"
            "H264" -> "video/avc"
            "AV1" -> "video/av01"
            else -> return null
        }

        // 안드로이드 OS에서 소프트웨어 인코더(MediaCodec)를 수동으로 찾습니다.
        val codecInfo = findSoftwareCodec(mimeType) ?: return null
        val colorFormat = findMatchingColorFormat(codecInfo, mimeType) ?: return null

        val codecType = VideoCodecMimeType.valueOf(info.name.uppercase())

        // org.webrtc 패키지에 속해 있기 때문에 아래 내부 구현체들에 접근할 수 있습니다.
        val wrapperFactory = MediaCodecWrapperFactoryImpl()
        val bitrateAdjuster = BaseBitrateAdjuster()

        // 하드웨어 인코더 생성자를 호출하여, 소프트웨어 MediaCodec을 Java 껍데기로 감쌉니다!
        return HardwareVideoEncoder(
            wrapperFactory,
            codecInfo.name, // 예: OMX.google.vp9.encoder
            codecType,
            null, // 소프트웨어 코덱은 대개 Surface 모드를 지원하지 않으므로 null
            colorFormat,
            info.params,
            3, // keyFrameIntervalSec
            0, // forceKeyFrameIntervalMs
            bitrateAdjuster,
            if (sharedContext is EglBase14.Context) sharedContext else null
        )
    }

    private fun findSoftwareCodec(mimeType: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue

            // 하드웨어 가속이 아닌 '소프트웨어' 코덱인지 판별
            val isSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isSoftwareOnly
            } else {
                val name = info.name.lowercase()
                name.startsWith("omx.google.") || name.startsWith("c2.android.")
            }

            if (isSoftware && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                return info
            }
        }
        return null
    }

    private fun findMatchingColorFormat(info: MediaCodecInfo, mimeType: String): Int? {
        val caps = info.getCapabilitiesForType(mimeType)
        val supportedColorFormats = caps.colorFormats

        // WebRTC의 HardwareVideoEncoder가 이해할 수 있는 YUV 포맷 매칭
        val targetFormats = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar, // I420
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, // NV12
            2141391872 // COLOR_QCOM_FormatYUV420SemiPlanar
        )
        for (target in targetFormats) {
            if (supportedColorFormats.contains(target)) {
                return target
            }
        }
        return null
    }
}