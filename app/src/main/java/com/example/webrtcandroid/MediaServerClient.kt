package com.example.webrtcandroid

import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.URISyntaxException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
// WebSocket 로컬 SSL 우회, 시그널링 규격 기술
interface SignalingCallback {
    fun onConnected()
    fun onMyIdReceived(id: String)
    fun onRootBroadcaster()
    fun onNewParent(parentId: String)
    fun onOfferReceived(from: String, sdp: SessionDescription)
    fun onAnswerReceived(from: String, sdp: SessionDescription)
    fun onIceCandidateReceived(from: String, candidate: IceCandidate)
    fun onForceDisconnectRoom()
}

class SignalingClient(
    private val serverUrl: String,
    private val roomName: String,
    private val callback: SignalingCallback
) {
    private var socket: Socket? = null

    // 로컬 HTTPS 테스트를 위한 SSL 검증 우회 클라이언트 생성
    private fun getUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun connect() {
        try {
            val okHttpClient = getUnsafeOkHttpClient()
            IO.setDefaultOkHttpWebSocketFactory(okHttpClient)
            IO.setDefaultOkHttpCallFactory(okHttpClient)

            val options = IO.Options().apply {
                callFactory = okHttpClient
                webSocketFactory = okHttpClient
                transports = arrayOf("websocket")
                upgrade = false
                reconnection = true
                forceNew = true
            }
            socket = IO.socket(serverUrl, options)
            setupSocketListeners()
            socket?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    private fun setupSocketListeners() {
        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val err = args[0] as? Exception
            android.util.Log.e("SignalingClient", "Socket connect error: ${err?.message}")

            // 에러 원인 출력하기
            err?.cause?.let { cause ->
                android.util.Log.e("SignalingClient", "Underlying Cause: ${cause.message}")
                cause.printStackTrace()
            } ?: err?.printStackTrace()
        }

        socket?.on(Socket.EVENT_CONNECT) {
            android.util.Log.i("SignalingClient", "Socket connected successfully!")

            callback.onConnected()

            val joinData = JSONObject().apply {
                put("room", roomName)
                put("type", "broadcast")
            }
            socket?.emit("join", joinData)
        }

        socket?.on(Socket.EVENT_DISCONNECT) {
            android.util.Log.e("SignalingClient", "Socket disconnected")
        }

        socket?.on("my-id") { args ->
            val id = args[0] as String
            callback.onMyIdReceived(id)
        }

        socket?.on("root-broadcaster") {
            callback.onRootBroadcaster()
        }

        socket?.on("new-parent") { args ->
            val parentId = args[0] as String
            callback.onNewParent(parentId)
        }

        socket?.on("offer") { args ->
            val data = args[0] as JSONObject
            val from = data.getString("from")
            val sdpData = data.getJSONObject("data")
            val sdp = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdpData.getString("type")),
                sdpData.getString("sdp")
            )
            callback.onOfferReceived(from, sdp)
        }

        socket?.on("answer") { args ->
            val data = args[0] as JSONObject
            val from = data.getString("from")
            val sdpData = data.getJSONObject("data")
            val sdp = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdpData.getString("type")),
                sdpData.getString("sdp")
            )
            callback.onAnswerReceived(from, sdp)
        }

        socket?.on("candidate") { args ->
            val data = args[0] as JSONObject
            val from = data.getString("from")
            val candData = data.getJSONObject("data").getJSONObject("candidate")
            val candidate = IceCandidate(
                candData.getString("sdpMid"),
                candData.getInt("sdpMLineIndex"),
                candData.getString("candidate")
            )
            callback.onIceCandidateReceived(from, candidate)
        }

        socket?.on("force-disconnect-room") {
            callback.onForceDisconnectRoom()
        }
    }

    fun sendOffer(to: String, sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("to", to)
            put("data", JSONObject().apply {
                put("type", sdp.type.canonicalForm())
                put("sdp", sdp.description)
            })
        }
        socket?.emit("offer", payload)
    }

    fun sendAnswer(to: String, sdp: SessionDescription) {
        val payload = JSONObject().apply {
            put("to", to)
            put("data", JSONObject().apply {
                put("type", sdp.type.canonicalForm())
                put("sdp", sdp.description)
            })
        }
        socket?.emit("answer", payload)
    }

    fun sendCandidate(to: String, candidate: IceCandidate) {
        val payload = JSONObject().apply {
            put("to", to)
            put("data", JSONObject().apply {
                put("type", "candidate")
                put("candidate", JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
            })
        }
        socket?.emit("candidate", payload)
    }

    fun disconnect() {
        socket?.disconnect()
    }
}
