package com.example.webrtcandroid

import android.content.Context
import org.webrtc.Camera2Enumerator
import org.webrtc.VideoCapturer

object CameraCapturerHelper { // WebRTC 라이브러리의 Camera2Enumerator를 사용하여 하드웨어 장치를 조회하고, 전면 카메라(isFrontFacing)를 자동 타겟팅해 캡처 인스턴스를 빌드
    fun createCameraCapturer(context: Context): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.find { enumerator.isFrontFacing(it) } 
            ?: enumerator.deviceNames.firstOrNull() ?: return null
        return enumerator.createCapturer(deviceName, null)
    }
}
