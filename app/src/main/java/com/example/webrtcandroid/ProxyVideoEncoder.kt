package com.example.webrtcandroid

import android.util.Log
import org.webrtc.*

class ProxyVideoEncoder(
    private val factory: SharedVideoEncoderFactory,
    val info: VideoCodecInfo // 여기에 원본 코덱 정보가 저장되어 있다!
) : VideoEncoder {
    private val TAG = "ProxyVideoEncoder"
    private var callback: VideoEncoder.Callback? = null
    
    @Volatile
    private var released = false
    
    @Volatile
    private var initDone = false

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        Log.d(TAG, "프록시. initEncode: ${info.name}")
        this.callback = callback
        
        // Ensure we don't send frames during initialization
        initDone = false
        
        val status = factory.proxyInitEncode(this, settings, callback)
        
        if (status == VideoCodecStatus.OK) {
            initDone = true
        }
        
        return status
    }

    override fun release(): VideoCodecStatus {
        if (released) return VideoCodecStatus.OK
        Log.d(TAG, "프록시. release")
        released = true
        initDone = false
        factory.proxyRelease(this)
        return VideoCodecStatus.OK
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        if (released || !initDone) return VideoCodecStatus.OK
        
        frame.retain()
        factory.proxyEncode(this, frame, info)
        return VideoCodecStatus.OK
    }

    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus {
        if (released || !initDone) return VideoCodecStatus.OK
        factory.proxySetRateAllocation(this, allocation, framerate)
        return VideoCodecStatus.OK
    }

    override fun getScalingSettings(): VideoEncoder.ScalingSettings {
        return VideoEncoder.ScalingSettings.OFF
    }

    override fun getImplementationName(): String {
        return "Proxy"
    }

    fun deliverEncodedFrame(frame: EncodedImage, codecInfo: VideoEncoder.CodecSpecificInfo?) {
        try {
            // CRITICAL: Gatekeep frames until the native side has finished initEncode.
            if (!released && initDone) {
                callback?.onEncodedFrame(frame, codecInfo) // WebRTC 네이티브 순정 콜백 호출. 실제로 학생한테 쏘아보냄
            }
        } catch (e: Exception) {
            Log.e(TAG, "프록시. Error delivering frame: ${e.message}")
        } finally {
            frame.release() // WebRTC 네이티브 오리지널 API. 다 썼으니 참조 카운트를 -1 낮춤
        }
    }
}
