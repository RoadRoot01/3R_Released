package com.example.webrtcandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

// 코드 역할 : 화면 공유 백그라운드 스레드 제어 및 미디어 처리하는 코드...

// [1. 화면 공유 알림 서비스]
class ScreenCaptureService : Service() { // 포그라운드 서비스 동기화 및 생명주기 관리

    companion object {
        /**
         * 서비스가 startForeground()를 완료한 직후 호출되는 콜백.
         * startForegroundService()는 비동기이므로, 이 콜백을 통해
         * 포그라운드 서비스가 완전히 준비된 시점을 보장한다.
         */
        var onServiceReady: (() -> Unit)? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "screen_capture_channel"
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("화면 공유 중")
            .setContentText("현재 태블릿 화면이 공유되고 있습니다.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        // startForeground() 완료 후 콜백 호출
        onServiceReady?.invoke()
        onServiceReady = null

        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): android.os.IBinder? = null
}

// 화면 캡처 텍스처 호환성 우회를 위한 커스텀 옵저버
// GPU 텍스처 평탄화 및 JNI 메모리 누수 제어
class I420ConversionObserver(private val target: CapturerObserver) : CapturerObserver {
    private var frameCount = 0

    override fun onCapturerStarted(success: Boolean) = target.onCapturerStarted(success)
    override fun onCapturerStopped() = target.onCapturerStopped()

    override fun onFrameCaptured(frame: VideoFrame) {
        // OPAQUE/Texture 버퍼를 표준 I420으로 강제 변환
        val i420Buffer = frame.buffer.toI420()

        if (i420Buffer != null) {
            frameCount++

            val convertedFrame = VideoFrame(i420Buffer, frame.rotation, frame.timestampNs)
            target.onFrameCaptured(convertedFrame)

            // WebRTC JNI 메모리 누수 방지를 위한 명시적 자원 해제
            convertedFrame.release()
        } else {
            android.util.Log.w("WebRTC_SCREEN", "I420 변환 실패, 원본 프레임 전달")
            target.onFrameCaptured(frame)
        }
    }
}
