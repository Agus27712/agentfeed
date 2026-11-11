package agu.analys.service

import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * WebSocket User Data Stream Resmi Tokocrypto:
 * Menggunakan token `user-listen-token` resmi dari Tokocrypto (POST /open/v1/user-listen-token).
 * Mendengarkan update eksekusi order (fill/cancel), update balance, dan event akun.
 *
 * Endpoint: wss://stream-cloud.tokocrypto.site/ws/<listenKey>
 *
 * Catatan: listenKey hanya berlaku 60 menit dan satu koneksi maksimal 24 jam.
 * Setelah listenKey kedaluwarsa, minta token baru dengan
 * TokocryptoTradeApi.requestUserListenToken() lalu panggil start() lagi.
 * Tidak ada koneksi ke Binance.
 */
class TokocryptoUserWebSocket(
    private val scope: CoroutineScope,
    private val onAccountUpdate: (JSONObject) -> Unit,
    private val onOrderUpdate: (JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val client get() = NetworkClientProvider.webSocketClient
    @Volatile private var socket: WebSocket? = null
    // Naik setiap start()/stop(); listener dengan generation lama otomatis diabaikan (tanpa race saat socket baru dibuat)
    @Volatile private var generation = 0
    private var reconnectJob: Job? = null
    private var currentListenKey = ""
    private var reconnectAttempt = 0
    private val lastMessageAt = AtomicLong(0L)

    private val wsHost = "wss://stream-cloud.tokocrypto.site/ws"

    fun start(listenKey: String) {
        if (listenKey.isBlank()) return
        stop(false)
        currentListenKey = listenKey
        reconnectAttempt = 0
        connect()
    }

    fun stop(notify: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        generation++
        val old = socket
        socket = null
        old?.close(1000, "User stream stopped")
        currentListenKey = ""
        if (notify) onDisconnected()
    }

    private fun connect() {
        if (currentListenKey.isBlank()) return
        val request = Request.Builder()
            .url("$wsHost/$currentListenKey")
            .header("User-Agent", "Mozilla/5.0 TokoUserStream/3.5")
            .build()
        val gen = generation
        socket = client.newWebSocket(request, Listener { gen == generation })
    }

    private fun reconnect() {
        if (currentListenKey.isBlank() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val waitMs = min(15_000L, (1000L * (1L shl min(reconnectAttempt, 4))))
            reconnectAttempt++
            delay(waitMs)
            if (isActive && currentListenKey.isNotBlank()) connect()
        }
    }

    private inner class Listener(private val isCurrent: () -> Boolean) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) {
                webSocket.close(1000, "Superseded")
                return
            }
            reconnectAttempt = 0
            lastMessageAt.set(System.currentTimeMillis())
            onConnected()
            Timber.i("TokocryptoUserWS: Connected to user stream")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            lastMessageAt.set(System.currentTimeMillis())
            try {
                val root = JSONObject(text)
                // Event bisa dibungkus { "stream": ..., "data": {...} } atau langsung
                val payload = root.optJSONObject("data") ?: root
                when (payload.optString("e", "")) {
                    "outboundAccountPosition", "balanceUpdate", "ACCOUNT_UPDATE" -> onAccountUpdate(payload)
                    "executionReport", "ORDER_TRADE_UPDATE" -> onOrderUpdate(payload)
                }
            } catch (e: Exception) {
                Timber.w(e, "Error parsing Tokocrypto User WS message")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) return
            Timber.w(t, "TokocryptoUserWS failure: ${t.message}")
            onDisconnected()
            reconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            Timber.i("TokocryptoUserWS closed: $code $reason")
            onDisconnected()
            reconnect()
        }
    }
}