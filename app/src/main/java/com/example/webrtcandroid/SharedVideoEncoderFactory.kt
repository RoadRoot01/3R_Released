package com.example.webrtcandroid

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class SharedVideoEncoderFactory(private val factory: VideoEncoderFactory) : VideoEncoderFactory {
    companion object {
        @Volatile var instance: SharedVideoEncoderFactory? = null
    }

    init {
        instance = this
    }

    fun requestKeyFrame() {
        if (shutdownRequested) return
        Log.d(TAG, "공유인코더: 즉시 I-프레임 생성 요청 수신")
        masterEncoders.keys.forEach { codecName ->
            pendingKeyFrameRequestByCodec[codecName] = true
        }
    }

    private val TAG = "SharedVideoEncoderFactory"

    private val handlerThread = HandlerThread("SharedEncoderThread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    @Volatile
    private var shutdownRequested = false
    
    // 마스터 인코더 풀 (코덱 이름 기준)
    private val masterEncoders = ConcurrentHashMap<String, VideoEncoder>()
    private class InitializationAttempt(val id: Long) {
        val latch = CountDownLatch(1)
        @Volatile var result: VideoCodecStatus = VideoCodecStatus.FALLBACK_SOFTWARE
        @Volatile var cancelled = false
        @Volatile var completed = false
        var waiters = 0
        var candidate: VideoEncoder? = null
        var candidateReleased = false
    }

    private val nextInitializationAttemptId = AtomicLong(0)
    private val initializationAttempts = ConcurrentHashMap<String, InitializationAttempt>()
    private val proxiesByCodec = ConcurrentHashMap<String, CopyOnWriteArrayList<ProxyVideoEncoder>>()
    
    private val lastEncodedTimestampNsByCodec = ConcurrentHashMap<String, Long>()
    private val pendingKeyFrameRequestByCodec = ConcurrentHashMap<String, Boolean>()

    // Keyframe Throttling 및 Lock 타임아웃 처리를 위한 변수 ---
    private val lastKeyFrameTimeNsByCodec = ConcurrentHashMap<String, Long>()
    private val KEY_FRAME_THROTTLE_NS = 2_000_000_000L // 2초 (100ms) 쿨타임. 매번 계속 I 프레임만 생성하는걸 방지
    var forceKeyFrameIntervalSec = 3600L // 강제 I-프레임 생성 주기 (초 단위)

    // -------------------------------------------------------------------

    private val proxyBitrates = ConcurrentHashMap<ProxyVideoEncoder, Pair<VideoEncoder.BitrateAllocation, Int>>()
    private val lastAppliedBitrateBpsByCodec = ConcurrentHashMap<String, Int>()

    class BitrateLog(val minBps: Int, val maxBps: Int, val targetAvgBps: Int)
    val latestBitrateLogs = ConcurrentHashMap<String, BitrateLog>()

    private val RELEASE_DELAY_MS = 3000L // 인코더 삭제 유예 시간
    private val releaseRunnables = ConcurrentHashMap<String, Runnable>()

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? { // info에 VP9, H264 등 WebRTC 엔진이 최종 결정한 코덱 정보가 들어있다.
        if (shutdownRequested) return null
        Log.d(TAG, "공유인코더. createEncoder for ${info.name}")
        val proxy = ProxyVideoEncoder(this, info)
        
        val codecName = info.name
        proxiesByCodec.putIfAbsent(codecName, CopyOnWriteArrayList())
        proxiesByCodec[codecName]?.add(proxy)
        
        return proxy
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        return factory.supportedCodecs
    }

    fun proxyInitEncode(proxy: ProxyVideoEncoder, settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        val codecName = proxy.info.name
        if (shutdownRequested) return VideoCodecStatus.FALLBACK_SOFTWARE

        var attempt: InitializationAttempt? = null
        var startInitialization = false
        synchronized(this) {
            if (shutdownRequested) return VideoCodecStatus.FALLBACK_SOFTWARE

            val inProgress = initializationAttempts[codecName]
            if (inProgress != null) {
                attempt = inProgress
            } else if (masterEncoders[codecName] == null) {
                attempt = InitializationAttempt(nextInitializationAttemptId.incrementAndGet())
                initializationAttempts[codecName] = attempt!!
                startInitialization = true
            } else {
                releaseRunnables[codecName]?.let { handler.removeCallbacks(it) }
                Log.d(TAG, "공유인코더. Master Encoder for $codecName already active, forcing keyframe")
                pendingKeyFrameRequestByCodec[codecName] = true
                return VideoCodecStatus.OK
            }
            attempt!!.waiters++
        }

        val initializationAttempt = attempt!!
        if (startInitialization) {
            val posted = handler.post {
                initializeMasterEncoder(proxy, settings, codecName, initializationAttempt)
            }
            if (!posted) {
                synchronized(this) {
                    initializationAttempt.cancelled = true
                    initializationAttempt.result = VideoCodecStatus.FALLBACK_SOFTWARE
                    initializationAttempt.completed = true
                    initializationAttempts.remove(codecName, initializationAttempt)
                }
                initializationAttempt.latch.countDown()
                Log.e(TAG, "공유인코더. INIT_FAILED: handler unavailable for $codecName")
            }
        }

        // 하드웨어 코덱 부팅 및 OpenGL EGL 초기화 시간을 고려하여 대기 타임아웃을 5초로 넉넉하게 지정
        val completed = try {
            initializationAttempt.latch.await(5000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        synchronized(this) {
            initializationAttempt.waiters--
            if (!completed && !initializationAttempt.completed && initializationAttempt.waiters == 0) {
                initializationAttempt.cancelled = true
                Log.e(TAG, "공유인코더. INIT_TIMEOUT: $codecName")
            }
        }
        return if (completed) initializationAttempt.result else VideoCodecStatus.FALLBACK_SOFTWARE
    }

    private fun initializeMasterEncoder(
        proxy: ProxyVideoEncoder,
        settings: VideoEncoder.Settings,
        codecName: String,
        attempt: InitializationAttempt
    ) {
        try {
            releaseRunnables[codecName]?.let { handler.removeCallbacks(it) }

            if (shutdownRequested || attempt.cancelled || proxiesByCodec[codecName].isNullOrEmpty()) {
                attempt.result = VideoCodecStatus.FALLBACK_SOFTWARE
                return
            }

            Log.d(TAG, "공유인코더. Initializing Master Encoder for $codecName with resolution ${settings.width}x${settings.height}")
            val candidate = factory.createEncoder(proxy.info)
            attempt.candidate = candidate

            if (candidate == null) {
                attempt.result = VideoCodecStatus.FALLBACK_SOFTWARE
                return
            }

            val initResult = candidate.initEncode(settings, object : VideoEncoder.Callback {
                override fun onEncodedFrame(frame: EncodedImage, codecInfo: VideoEncoder.CodecSpecificInfo?) {
                    multicastFrame(codecName, frame, codecInfo)
                }
            })

            var published = false
            synchronized(this) {
                if (initResult == VideoCodecStatus.OK &&
                    !shutdownRequested &&
                    !attempt.cancelled &&
                    initializationAttempts[codecName] === attempt &&
                    masterEncoders[codecName] == null &&
                    !proxiesByCodec[codecName].isNullOrEmpty()
                ) {
                    masterEncoders[codecName] = candidate
                    attempt.candidate = null
                    attempt.result = VideoCodecStatus.OK
                    pendingKeyFrameRequestByCodec[codecName] = true
                    published = true
                } else {
                    attempt.result = if (initResult == VideoCodecStatus.OK) {
                        VideoCodecStatus.FALLBACK_SOFTWARE
                    } else {
                        initResult
                    }
                }
            }
            if (published) {
                Log.d(TAG, "공유인코더. MASTER_PUBLISHED: $codecName attempt=${attempt.id}")
            } else {
                releaseCandidate(attempt, codecName)
            }
        } catch (e: Exception) {
            attempt.result = VideoCodecStatus.FALLBACK_SOFTWARE
            releaseCandidate(attempt, codecName)
            Log.e(TAG, "공유인코더. INIT_FAILED: $codecName (${e.message})")
        } finally {
            synchronized(this) {
                attempt.completed = true
                initializationAttempts.remove(codecName, attempt)
            }
            attempt.latch.countDown()
        }
    }

    private fun releaseCandidate(attempt: InitializationAttempt, codecName: String) {
        val candidate = attempt.candidate ?: return
        if (attempt.candidateReleased) return
        attempt.candidateReleased = true
        attempt.candidate = null
        try {
            candidate.release()
            Log.d(TAG, "공유인코더. CANDIDATE_RELEASED: $codecName attempt=${attempt.id}")
        } catch (e: Exception) {
            Log.e(TAG, "공유인코더. CANDIDATE_RELEASED failed: $codecName (${e.message})")
        }
    }



    fun proxyEncode(proxy: ProxyVideoEncoder, frame: VideoFrame, info: VideoEncoder.EncodeInfo) {
        val codecName = proxy.info.name

        if (shutdownRequested) {
            frame.release()
            return
        }
        
        synchronized(this) {
            val nowNs = System.nanoTime()

            // 주기적 I-프레임 생성
            val lastKeyTimeForPeriodic = lastKeyFrameTimeNsByCodec[codecName] ?: nowNs
            if (nowNs - lastKeyTimeForPeriodic >= forceKeyFrameIntervalSec * 1_000_000_000L) {
                Log.d(TAG, "공유인코더. I-프레임 강제 생성: 설정된 주기(${forceKeyFrameIntervalSec}초) 도달 (코덱: $codecName)")
                pendingKeyFrameRequestByCodec[codecName] = true
                lastKeyFrameTimeNsByCodec[codecName] = nowNs
            }
            // ------------------------------------------------

            // [기존 로직 - 주석 처리됨]
            if (info.frameTypes.contains(EncodedImage.FrameType.VideoFrameKey)) {
                Log.d(TAG, "공유인코더. I-프레임(Keyframe) 생성 요청 수신됨! (코덱: $codecName)")
                pendingKeyFrameRequestByCodec[codecName] = true
            }


            // Keyframe Throttling
            /*
            if (info.frameTypes.contains(EncodedImage.FrameType.VideoFrameKey)) {
                Log.d(TAG, "공유인코더. I-프레임(Keyframe) 생성 요청 수신됨! (코덱: $codecName)")


                val lastKeyTimeNs = lastKeyFrameTimeNsByCodec[codecName] ?: 0L
                // 마지막으로 I-프레임을 만든 지 (쿨타임)초가 지났을 때만 요청을 수락
                if (nowNs - lastKeyTimeNs >= KEY_FRAME_THROTTLE_NS) {
                    Log.d(TAG, "공유인코더. I-프레임 요청 수락됨: 쿨타임 지남")
                    pendingKeyFrameRequestByCodec[codecName] = true
                    lastKeyFrameTimeNsByCodec[codecName] = nowNs // 즉시 갱신하여 중복 진입 및 다중 로그 출력 방지
                } else {
                    // 쿨타임 중 들어온 요청은 무시
                    Log.d(TAG, "공유인코더. I-프레임 요청 무시됨: 쿨타임 중 방어 (PLI Storm 방지)")
                }
            }
            */

            // --------------------------------------------------------

            val lastTimestamp = lastEncodedTimestampNsByCodec[codecName] ?: -1L

            // 이미 인코딩한 프레임 버림
            if (frame.timestampNs <= lastTimestamp) {
                frame.release()
                return
            }

            lastEncodedTimestampNsByCodec[codecName] = frame.timestampNs
        }

        val posted = handler.post {
            try {
                val encoder = if (shutdownRequested) null else masterEncoders[codecName]
                if (encoder != null) {
                    val pendingKey = pendingKeyFrameRequestByCodec[codecName] ?: false

                    // 키 프레임 생성 시점 기록 갱신
                    val finalInfo = if (pendingKey) {
                        pendingKeyFrameRequestByCodec[codecName] = false
                        lastKeyFrameTimeNsByCodec[codecName] = System.nanoTime() // 진짜로 키 프레임을 쏠 때 쿨타임 갱신
                        VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameKey))
                    } else {
                        info
                    }
                    // --------------------------------------------------

                    encoder.encode(frame, finalInfo)
                }
            } catch (e: Exception) {
                Log.e(TAG, "공유인코더. Error encoding frame: ${e.message}")
            } finally {
                frame.release()
            }
        }
        if (!posted) {
            frame.release()
            Log.e(TAG, "공유인코더. ENCODE_POST_REJECTED: $codecName")
        }
    }

    // 평균치로 타겟 비트레이트를 설정하도록 수정.
    fun proxySetRateAllocation(proxy: ProxyVideoEncoder, allocation: VideoEncoder.BitrateAllocation, framerate: Int) {
        if (shutdownRequested) return
        proxyBitrates[proxy] = Pair(allocation, framerate)
        val codecName = proxy.info.name
        val proxyList = proxiesByCodec[codecName] ?: return

        // 1. Max와 Min 값을 저장할 변수 초기화
        var maxBps = -1
        var minBps = Int.MAX_VALUE

        // 2. 모든 피어 전수 조사하여 Max, Min 찾기
        for (p in proxyList) {
            val entry = proxyBitrates[p]
            if (entry != null) {
                val sum = entry.first.sum
                if (sum > maxBps) maxBps = sum
                if (sum < minBps) minBps = sum
            }
        }
        val targetFramerate = HardwareEncoderConfig.videoFps

        // 예외 방어: 피어가 없거나 비정상 값일 경우
        if (maxBps == -1) return
        if (minBps == Int.MAX_VALUE) minBps = maxBps

        // 3. Max와 Min의 평균 비트레이트 계산
        //val targetAvgBps = ((maxBps + minBps) / 2 * 0.8).toInt()
        //val targetAvgBps = minBps

        val targetAvgBps = if (minBps < 500_000) 500_000 else minBps // 최소 bps 너무 떨어지지 않게 고정하는 로직 추가.

        latestBitrateLogs[codecName] = BitrateLog(minBps, maxBps, targetAvgBps)

        val lastApplied = lastAppliedBitrateBpsByCodec[codecName] ?: -1

        // 4. 평균값이 기존 적용값 대비 5% 이상 변동이 있을 때만 하드웨어에 적용
        if (targetAvgBps > 0 && (lastApplied == -1 || Math.abs(targetAvgBps - lastApplied) > lastApplied * 0.05)) {
            lastAppliedBitrateBpsByCodec[codecName] = targetAvgBps

            // 5. 평균 비트레이트를 담은 새로운 BitrateAllocation 객체 생성 (단일 레이어 기준)
            val averageAllocation = VideoEncoder.BitrateAllocation(arrayOf(intArrayOf(targetAvgBps)))

            handler.post {
                // 마스터 인코더에 평균 비트레이트와 고정 프레임레이트를 지시
                if (!shutdownRequested) {
                    masterEncoders[codecName]?.setRateAllocation(averageAllocation, targetFramerate)
                }
            }
        }
    }


    fun proxyRelease(proxy: ProxyVideoEncoder) {
        val codecName = proxy.info.name
        if (shutdownRequested) return
        val proxyList = proxiesByCodec[codecName]
        proxyList?.remove(proxy)
        proxyBitrates.remove(proxy)
        
        Log.d(TAG, "공유인코더. Proxy for $codecName removed. Remaining in codec: ${proxyList?.size ?: 0}")

        val posted = handler.post {
            if (proxyList.isNullOrEmpty()) {
                Log.d(TAG, "공유인코더. Last proxy for $codecName gone. Scheduling Master Encoder release in ${RELEASE_DELAY_MS}ms")
                
                val runnable = Runnable { releaseMasterEncoderInternal(codecName) }
                releaseRunnables[codecName] = runnable
                if (!handler.postDelayed(runnable, RELEASE_DELAY_MS)) {
                    releaseRunnables.remove(codecName, runnable)
                    Log.e(TAG, "공유인코더. MASTER_RELEASE schedule rejected: $codecName")
                    releaseMasterEncoderInternal(codecName)
                }
            } else {
                pendingKeyFrameRequestByCodec[codecName] = true
            }
        }
        if (!posted) {
            Log.e(TAG, "공유인코더. RELEASE_POST_REJECTED: $codecName")
        }
    }

    private fun releaseMasterEncoderInternal(codecName: String) {
        val proxyList = proxiesByCodec[codecName]
        if (proxyList.isNullOrEmpty()) {
            Log.d(TAG, "공유인코더. Releasing Master Encoder for $codecName now.")
            val encoder = masterEncoders.remove(codecName)
            try {
                encoder?.release()
                if (encoder != null) Log.d(TAG, "공유인코더. MASTER_RELEASED: $codecName")
            } catch (e: Exception) {
                Log.e(TAG, "공유인코더. MASTER_RELEASED failed: $codecName (${e.message})")
            }
            
            lastEncodedTimestampNsByCodec.remove(codecName)
            pendingKeyFrameRequestByCodec.remove(codecName)
            lastAppliedBitrateBpsByCodec.remove(codecName)
            releaseRunnables.remove(codecName)
            
            // 메모리 누수 방지를 위한 자원 해제 ---
            lastKeyFrameTimeNsByCodec.remove(codecName)
            // --------------------------------------------------
        }
    }

    fun shutdown() {
        synchronized(this) {
            if (shutdownRequested) return
            shutdownRequested = true
            initializationAttempts.values.forEach { attempt ->
                attempt.cancelled = true
                attempt.result = VideoCodecStatus.FALLBACK_SOFTWARE
                attempt.latch.countDown()
            }
        }

        val cleanup = Runnable {
            releaseRunnables.values.forEach { handler.removeCallbacks(it) }
            releaseRunnables.clear()

            masterEncoders.values.toList().forEach { encoder ->
                try {
                    encoder.release()
                } catch (e: Exception) {
                    Log.e(TAG, "공유인코더. MASTER_RELEASED failed during shutdown (${e.message})")
                }
            }
            masterEncoders.clear()
            initializationAttempts.values.forEach { attempt -> releaseCandidate(attempt, "shutdown") }
            initializationAttempts.clear()

            proxiesByCodec.clear()
            proxyBitrates.clear()
            lastEncodedTimestampNsByCodec.clear()
            pendingKeyFrameRequestByCodec.clear()
            lastKeyFrameTimeNsByCodec.clear()
            lastAppliedBitrateBpsByCodec.clear()
            latestBitrateLogs.clear()
            if (instance === this@SharedVideoEncoderFactory) instance = null
            Log.d(TAG, "공유인코더. SHUTDOWN_COMPLETE")
            handlerThread.quitSafely()
        }

        if (Looper.myLooper() == handlerThread.looper) {
            cleanup.run()
        } else if (!handler.post(cleanup)) {
            Log.e(TAG, "공유인코더. shutdown cleanup rejected")
            handlerThread.quitSafely()
        }
    }

    private fun multicastFrame(codecName: String, frame: EncodedImage, codecInfo: VideoEncoder.CodecSpecificInfo?) {
        if (frame.frameType == EncodedImage.FrameType.VideoFrameKey) {
            Log.d(TAG, "공유인코더. [확인용] 하드웨어 인코더가 키 프레임(I-Frame)을 생성 및 방출했습니다! (코덱: $codecName)")
        }

        // 현재 접속 중인 프록시 인코더(피어) 리스트를 전수 조사
        // 원본 EncodedImage의 생명주기는 HardwareVideoEncoder.deliverEncodedImage()가 관리
        // 콜백(여기) 반환 후 HW 인코더가 자체적으로 release()를 호출하므로,
        // 이 함수 안에서 원본 frame을 release하면 이중 해제(refcount < 1) 크래시가 발생
        val proxyList = proxiesByCodec[codecName] ?: return  // 수신자 없으면 즉시 반환 (원본 해제는 HW 인코더가 담당)

        // 루프를 돌며 각각의 프록시에게 영상 프레임을 메모리 카운트만 늘려서 직접 분배
        for (proxy in proxyList) {
            frame.retain() // 메모리 참조 횟수 +1 증가 (메모리 복사 없음).  WebRTC 라이브러리가 제공하는 C++ 네이티브 메모리 관리용 오리지널 메서드
            proxy.deliverEncodedFrame(frame, codecInfo)
        }
    }
}
