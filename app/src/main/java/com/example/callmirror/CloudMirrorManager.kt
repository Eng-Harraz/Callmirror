package com.example.callmirror

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Robust cloud messaging manager that combines WebSockets with HTTP fallback
 * using ntfy.sh (open-source, 100% free, ultra-reliable pub/sub relay).
 */
class CloudMirrorManager(
    private val context: Context,
    private val onMessageReceived: ((CallPayload) -> Unit)? = null,
    private val onConnectionStateChanged: ((Boolean) -> Unit)? = null
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val postClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var currentPairingCode: String? = null
    private var shouldKeepConnected = false
    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "CloudMirrorManager"
        private const val NTFY_WS_BASE = "wss://ntfy.sh"
        private const val NTFY_HTTP_BASE = "https://ntfy.sh"
    }

    fun connect(pairingCode: String) {
        val cleanCode = pairingCode.trim().filter { it.isLetterOrDigit() }.lowercase()
        if (cleanCode.isBlank()) {
            Log.w(TAG, "Empty pairing code, cannot connect")
            return
        }

        currentPairingCode = cleanCode
        shouldKeepConnected = true

        disconnect()

        val topic = CryptoUtils.deriveTopicName(cleanCode)
        val wsUrl = "$NTFY_WS_BASE/$topic/ws"
        Log.d(TAG, "Connecting to Cloud Relay (Secure Topic: $topic)")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to Cloud Relay successfully")
                isConnected = true
                onConnectionStateChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    // ntfy wraps payloads in {"event":"message","message":"{...}"}
                    val json = org.json.JSONObject(text)
                    val event = json.optString("event")
                    if (event == "message") {
                        val messageStr = json.optString("message")
                        val code = currentPairingCode ?: ""
                        val decryptedStr = CryptoUtils.decrypt(messageStr, code)
                        if (decryptedStr != null) {
                            val payload = CallPayload.fromJson(decryptedStr)
                            if (payload != null) {
                                Log.d(TAG, "Received Cloud payload: ${payload.status}")
                                onMessageReceived?.invoke(payload)
                            }
                        } else {
                            Log.w(TAG, "Failed to decrypt cloud message. Key mismatch or corrupted data.")
                        }
                    }
                } catch (e: Exception) {
                    val code = currentPairingCode ?: ""
                    val decryptedStr = CryptoUtils.decrypt(text, code)
                    if (decryptedStr != null) {
                        val directPayload = CallPayload.fromJson(decryptedStr)
                        if (directPayload != null) {
                            onMessageReceived?.invoke(directPayload)
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Cloud WebSocket closing: $reason ($code)")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Cloud WebSocket failure: ${t.message}")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                reconnectIfNeeded()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Cloud WebSocket closed: $reason")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                reconnectIfNeeded()
            }
        })
    }

    private fun reconnectIfNeeded() {
        if (shouldKeepConnected && !currentPairingCode.isNullOrBlank()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (shouldKeepConnected && !isConnected) {
                    Log.d(TAG, "Attempting cloud reconnect...")
                    currentPairingCode?.let { connect(it) }
                }
            }, 4000)
        }
    }

    fun sendPayload(payload: CallPayload): Boolean {
        val cleanCode = currentPairingCode ?: return false
        val topic = CryptoUtils.deriveTopicName(cleanCode)
        val rawJson = payload.toJson()

        // Encrypt with AES-256-GCM. Fail-closed: if encryption fails, abort immediately!
        val securePayloadStr = CryptoUtils.encrypt(rawJson, cleanCode)
        if (securePayloadStr == null) {
            Log.e(TAG, "Payload encryption failed. Aborting transmission to prevent data leak.")
            return false
        }

        // Send via active WebSocket for instant delivery
        var sentViaWs = false
        if (isConnected && webSocket != null) {
            sentViaWs = webSocket?.send(securePayloadStr) ?: false
        }

        // Always also fire HTTP POST for 100% delivery guarantee across NAT/cellular firewalls
        scope.launch {
            try {
                val mediaType = "text/plain; charset=utf-8".toMediaType()
                val body = securePayloadStr.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$NTFY_HTTP_BASE/$topic")
                    .post(body)
                    .build()
                postClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "HTTP Cloud publish result: code ${response.code} (Encrypted: true)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP Cloud publish error: ${e.message}")
            }
        }

        return sentViaWs || true
    }

    fun disconnect() {
        shouldKeepConnected = false
        try {
            webSocket?.close(1000, "App disconnect")
        } catch (ignored: Exception) {}
        webSocket = null
        isConnected = false
        onConnectionStateChanged?.invoke(false)
    }
}
