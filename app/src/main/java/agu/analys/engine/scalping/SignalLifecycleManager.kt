package agu.analys.engine.scalping

import agu.analys.config.StrategyMode
import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SignalTransition(
    val symbol: String,
    val mode: StrategyMode,
    val previousState: LifecycleState,
    val newState: LifecycleState,
    val signal: AISignalState,
    val isNewDetected: Boolean,
    val isNewReady: Boolean
) {
    val hasTriggeringTransition: Boolean
        get() = (isNewDetected || isNewReady) && signal.action == SignalAction.BUY
}

data class TrackedSignal(
    val symbol: String,
    val mode: StrategyMode = StrategyMode.SCALPING,
    var state: LifecycleState = LifecycleState.IDLE,
    var detectedAt: Long = 0L,
    var lastUpdatedAt: Long = 0L,
    var entryPrice: Double = 0.0,
    var targetPrice: Double = 0.0,
    var stopLoss: Double = 0.0,
    var activeSignalState: AISignalState? = null,
    var transition: SignalTransition? = null,
    /** FIX: hysteresis counter — butuh N tick berturut-turut conf rendah sebelum INVALIDATED dari READY */
    var weakTickCount: Int = 0,
    /** P0 — setup yang sudah kedaluwarsa; selama kuncinya sama, siklus tetap EXPIRED. */
    var expiredSetupKey: String = ""
)

object SignalLifecycleManager {
    private val activeSignals = ConcurrentHashMap<String, TrackedSignal>()
    private val lock = ReentrantLock()

    // TTL scalping berasal dari `ScalpingRuntimeConfig.ttlBarsM1` (p0: 5 candle M1),
    // bukan konstanta jam dinding di sini. Nilai lama 10 menit tetap tercatat di riwayat.
    // Expire swing / macro signals older than 4 hours (dinaikkan dari 2 jam)
    private const val EXPIRY_MACRO_MS = 4 * 60 * 60 * 1000L
    // TRIGGERED ikut kedaluwarsa agar sinyal baru bisa diproses lagi (sebelumnya macet permanen).
    private const val EXPIRY_TRIGGERED_SCALPING_MS = 10 * 60 * 1000L
    private const val EXPIRY_TRIGGERED_MACRO_MS = 4 * 60 * 60 * 1000L
    // Hysteresis: butuh 3 tick berturut-turut conf lemah sebelum drop dari READY
    private const val WEAK_TICK_THRESHOLD = 3

    fun normalizeSymbol(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("_", "").replace("-", "")

    private fun cacheKey(symbol: String, mode: StrategyMode): String = "${normalizeSymbol(symbol)}#${mode.name}"

    fun process(
        symbol: String,
        currentPrice: Double,
        rawSignal: AISignalState,
        mode: StrategyMode = StrategyMode.SCALPING,
        // Waktu evaluasi (ms). `<= 0` = jam sistem. Wajib bisa diinjeksi agar batas TTL
        // dapat diuji tanpa menunggu 5 menit sungguhan.
        nowMs: Long = 0L,
        config: ScalpingRuntimeConfig = ScalpingRuntimeConfig.current
    ): TrackedSignal = lock.withLock {
        val now = if (nowMs > 0L) nowMs else System.currentTimeMillis()
        val key = cacheKey(symbol, mode)
        var tracked = activeSignals.getOrPut(key) {
            TrackedSignal(symbol = symbol, mode = mode)
        }
        val incomingSetupKey = SignalTtl.setupKey(rawSignal)

        // P0 — begitu setup berubah/hilang, kunci anti re-arm dilepas. Setup yang sama
        // (termasuk kembali pada tick berikutnya) tetap tertahan di EXPIRED.
        if (tracked.expiredSetupKey.isNotEmpty() && tracked.expiredSetupKey != incomingSetupKey) {
            tracked.expiredSetupKey = ""
        }

        // TRIGGERED yang sudah lama → reset agar siklus sinyal baru bisa berjalan lagi.
        // Jangkar: lastUpdatedAt di-set saat markTriggered().
        if (tracked.state == LifecycleState.TRIGGERED) {
            val trigExpiry = if (mode == StrategyMode.SCALPING) EXPIRY_TRIGGERED_SCALPING_MS else EXPIRY_TRIGGERED_MACRO_MS
            if (now - tracked.lastUpdatedAt > trigExpiry) {
                activeSignals.remove(key)
                tracked = TrackedSignal(symbol = symbol, mode = mode)
                activeSignals[key] = tracked
            }
        }

        val previousState = tracked.state

        // 1. Time-based Expiration
        // Scalping memakai TTL dari runtime config (p0: 5 candle M1), bukan 10 menit tetap.
        val expiryMs = if (mode == StrategyMode.SCALPING) {
            config.ttlBarsM1.coerceAtLeast(1) * SignalTtl.M1_BAR_MS
        } else {
            EXPIRY_MACRO_MS
        }
        if (tracked.state in listOf(LifecycleState.DETECTED, LifecycleState.CONFIRMING, LifecycleState.READY)) {
            if (tracked.detectedAt > 0L && now - tracked.detectedAt >= expiryMs) {
                tracked.state = LifecycleState.EXPIRED
                tracked.weakTickCount = 0
                // P0 — catat identitas episode yang kedaluwarsa. Selama setup + arah sama,
                // cabang progresi di bawah tidak boleh menaikkannya lagi.
                if (SignalTtl.hasSetup(rawSignal)) {
                    tracked.expiredSetupKey = incomingSetupKey
                }
            }
        }
        val isTerminalExpiry = mode == StrategyMode.SCALPING &&
            tracked.state == LifecycleState.EXPIRED &&
            tracked.expiredSetupKey.isNotEmpty() &&
            tracked.expiredSetupKey == incomingSetupKey

        // 2. Price-based Invalidation (drop below SL before triggered) — hard, instant
        val isPriceBelowStopLoss = (tracked.stopLoss > 0.0 && currentPrice <= tracked.stopLoss) ||
                (rawSignal.stopLoss > 0.0 && currentPrice <= rawSignal.stopLoss)
        if (isPriceBelowStopLoss) {
            if (tracked.state in listOf(LifecycleState.CONFIRMING, LifecycleState.READY, LifecycleState.DETECTED)) {
                tracked.state = LifecycleState.INVALIDATED
                tracked.weakTickCount = 0
            }
        }

        // 3. State progression
        if (isPriceBelowStopLoss) {
            updateSignalData(tracked, rawSignal, now)
        } else if (isTerminalExpiry) {
            // P0 — EXPIRED bersifat terminal untuk setup yang sama. Jangan biarkan satu tick
            // sesudah kedaluwarsa memulai episode baru dengan pola yang identik.
            updateSignalData(tracked, rawSignal, now)
        } else if (mode == StrategyMode.SCALPING) {
            // Mode SCALPING: rule spesifik berbasis scalpingStage
            //
            // Invarian P1: tanpa pola scalping yang valid (`scalpingSetup` kosong/`"NONE"`), tidak
            // ada stage yang boleh **menaikkan** siklus hidup ke DETECTED / CONFIRMING / READY.
            // Stage semacam itu dinormalisasi menjadi `WATCH` — kecuali `HOLD` (kondisi berbahaya)
            // yang tetap diturunkan sebagai `HOLD`.
            //
            // Sebelumnya `EARLY_ENTRY` & `WAIT_PULLBACK` dipromosikan ke DETECTED hanya berdasar
            // tekanan orderbook, sehingga radar menampilkan "TERDETEKSI" padahal
            // `scalpingSetup == "NONE"`.
            //
            // Normalisasi ini tidak mengubah perilaku sinyal yang sah: `WATCH` dan `HOLD` sudah
            // punya transisi yang benar di mesin ini (IDLE tetap IDLE, DETECTED turun ke IDLE,
            // READY dibatalkan), dan producer sungguhan (`ScalpingMtfEvaluator`) sudah tidak
            // pernah lagi memancarkan stage lebih tinggi tanpa pola. Jadi ini jaring pengaman —
            // termasuk untuk producer sinyal di masa depan.
            val stage = when {
                ScalpReadiness.hasSetup(rawSignal) -> rawSignal.scalpingStage
                rawSignal.scalpingStage == ScalpingStage.HOLD -> ScalpingStage.HOLD
                else -> ScalpingStage.WATCH
            }
            when (tracked.state) {
                LifecycleState.IDLE, LifecycleState.EXPIRED, LifecycleState.INVALIDATED -> {
                    when (stage) {
                        ScalpingStage.EARLY_ENTRY, ScalpingStage.WAIT_PULLBACK -> {
                            tracked.state = LifecycleState.DETECTED
                            tracked.detectedAt = now
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.ENTRY -> {
                            tracked.state = LifecycleState.CONFIRMING
                            tracked.detectedAt = now
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.STRONG_ENTRY -> {
                            tracked.state = LifecycleState.READY
                            tracked.detectedAt = now
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        else -> {
                            updateSignalData(tracked, rawSignal, now)
                        }
                    }
                }
                LifecycleState.DETECTED -> {
                    when (stage) {
                        ScalpingStage.ENTRY -> {
                            tracked.state = LifecycleState.CONFIRMING
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.STRONG_ENTRY -> {
                            tracked.state = LifecycleState.READY
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.HOLD -> {
                            tracked.state = LifecycleState.INVALIDATED
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.WATCH -> {
                            tracked.state = LifecycleState.IDLE
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        else -> updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.CONFIRMING -> {
                    when (stage) {
                        ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY -> {
                            tracked.state = LifecycleState.READY
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.HOLD -> {
                            tracked.state = LifecycleState.INVALIDATED
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.WATCH, ScalpingStage.EARLY_ENTRY -> {
                            updateSignalData(tracked, rawSignal, now)
                        }
                        else -> updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.READY -> {
                    when (stage) {
                        ScalpingStage.HOLD, ScalpingStage.WATCH -> {
                            tracked.state = LifecycleState.INVALIDATED
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        else -> updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.TRIGGERED -> {
                    // Dipertahankan sampai kedaluwarsa (reset di awal process) atau reset eksplisit.
                }
            }
        } else {
            // Mode NON-SCALPING (SWING / OFFICE_DAILY / dll)
            // Thresholds:
            // - CONF >= 75 -> READY
            // - CONF >= 65 -> CONFIRMING
            // - CONF >= 50 -> DETECTED
            // FIX: hysteresis saat keluar dari READY — butuh WEAK_TICK_THRESHOLD tick conf lemah
            val isBuy = rawSignal.action == SignalAction.BUY
            val conf = rawSignal.confidence

            when (tracked.state) {
                LifecycleState.IDLE, LifecycleState.EXPIRED, LifecycleState.INVALIDATED -> {
                    if (isBuy) {
                        when {
                            conf >= 75 -> {
                                tracked.state = LifecycleState.READY
                                tracked.detectedAt = now
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf >= 65 -> {
                                tracked.state = LifecycleState.CONFIRMING
                                tracked.detectedAt = now
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf >= 50 -> {
                                tracked.state = LifecycleState.DETECTED
                                tracked.detectedAt = now
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            else -> updateSignalData(tracked, rawSignal, now)
                        }
                    } else {
                        updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.DETECTED -> {
                    if (isBuy) {
                        when {
                            conf >= 75 -> {
                                tracked.state = LifecycleState.READY
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf >= 65 -> {
                                tracked.state = LifecycleState.CONFIRMING
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf < 45 -> {
                                tracked.state = LifecycleState.INVALIDATED
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            else -> updateSignalData(tracked, rawSignal, now)
                        }
                    } else {
                        tracked.state = LifecycleState.INVALIDATED
                        tracked.weakTickCount = 0
                        updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.CONFIRMING -> {
                    if (isBuy) {
                        when {
                            conf >= 75 -> {
                                tracked.state = LifecycleState.READY
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf < 50 -> {
                                tracked.state = LifecycleState.INVALIDATED
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            else -> updateSignalData(tracked, rawSignal, now)
                        }
                    } else {
                        tracked.state = LifecycleState.INVALIDATED
                        tracked.weakTickCount = 0
                        updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.READY -> {
                    // FIX HYSTERESIS: jangan langsung INVALIDATED saat conf < 50 sekali
                    // Butuh WEAK_TICK_THRESHOLD tick berturut-turut, kecuali conf sangat jelek (< 30)
                    if (!isBuy || conf < 30) {
                        tracked.state = LifecycleState.INVALIDATED
                        tracked.weakTickCount = 0
                        updateSignalData(tracked, rawSignal, now)
                    } else if (conf < 55) {
                        tracked.weakTickCount += 1
                        if (tracked.weakTickCount >= WEAK_TICK_THRESHOLD) {
                            tracked.state = LifecycleState.INVALIDATED
                            tracked.weakTickCount = 0
                        }
                        updateSignalData(tracked, rawSignal, now)
                    } else {
                        // conf masih sehat → reset counter
                        tracked.weakTickCount = 0
                        updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.TRIGGERED -> {
                    // Dipertahankan sampai kedaluwarsa (reset di awal process) atau reset eksplisit.
                }
            }
        }

        val newState = tracked.state
        val isNewDetected = previousState != LifecycleState.DETECTED && newState == LifecycleState.DETECTED
        val isNewReady = previousState != LifecycleState.READY && newState == LifecycleState.READY

        val transition = SignalTransition(
            symbol = symbol,
            mode = mode,
            previousState = previousState,
            newState = newState,
            signal = rawSignal,
            isNewDetected = isNewDetected,
            isNewReady = isNewReady
        )
        tracked.transition = transition

        return tracked.copy(transition = transition)
    }

    private fun updateSignalData(tracked: TrackedSignal, raw: AISignalState, now: Long) {
        tracked.lastUpdatedAt = now
        if (tracked.stopLoss == 0.0 || (raw.stopLoss > 0.0 && raw.stopLoss > tracked.stopLoss)) {
            tracked.stopLoss = raw.stopLoss
        }
        tracked.entryPrice = raw.entryPrice
        tracked.targetPrice = raw.targetPrice1
        tracked.activeSignalState = raw.copy(
            marketSymbol = tracked.symbol,
            lifecycleState = tracked.state
        )
    }

    fun getSignal(symbol: String, mode: StrategyMode? = null): AISignalState? = lock.withLock {
        val norm = normalizeSymbol(symbol)
        if (mode != null) {
            val key = cacheKey(norm, mode)
            val tracked = activeSignals[key] ?: return@withLock null
            if (tracked.state == LifecycleState.EXPIRED || tracked.state == LifecycleState.INVALIDATED) {
                return@withLock null
            }
            tracked.activeSignalState
        } else {
            val prefix = "$norm#"
            activeSignals.entries
                .firstOrNull { it.key.startsWith(prefix) && it.value.state != LifecycleState.EXPIRED && it.value.state != LifecycleState.INVALIDATED }
                ?.value?.activeSignalState
        }
    }

    fun markTriggered(symbol: String, mode: StrategyMode? = null) = lock.withLock {
        if (mode != null) {
            val key = cacheKey(symbol, mode)
            activeSignals[key]?.let {
                if (it.state == LifecycleState.READY || it.state == LifecycleState.CONFIRMING) {
                    it.state = LifecycleState.TRIGGERED
                    it.lastUpdatedAt = System.currentTimeMillis()
                    it.weakTickCount = 0
                }
            }
        } else {
            // N7: prefix WAJIB lewat `normalizeSymbol` — key disimpan lewat `cacheKey` yang
            // membuang pemisah (`/ _ -`). Memakai `symbol.uppercase()` mentah membuat
            // `"BTC_IDR#"` tidak pernah cocok dengan key `"BTCIDR#SCALPING"`, sehingga sinyal
            // diam-diam tidak pernah berstatus TRIGGERED (jalur real trade mengirim `btc_idr`).
            val prefix = "${normalizeSymbol(symbol)}#"
            activeSignals.forEach { (k, it) ->
                if (k.startsWith(prefix) || k.equals(symbol, ignoreCase = true)) {
                    if (it.state == LifecycleState.READY || it.state == LifecycleState.CONFIRMING) {
                        it.state = LifecycleState.TRIGGERED
                        it.lastUpdatedAt = System.currentTimeMillis()
                        it.weakTickCount = 0
                    }
                }
            }
        }
    }

    fun reset(symbol: String, mode: StrategyMode? = null) = lock.withLock {
        if (mode != null) {
            activeSignals.remove(cacheKey(symbol, mode))
        } else {
            // N7: sama seperti `markTriggered` — prefix harus lewat `normalizeSymbol`, kalau tidak
            // `reset("BTC/IDR")` tidak menghapus apa pun dan state lama tertinggal di mesin.
            val prefix = "${normalizeSymbol(symbol)}#"
            activeSignals.keys.removeAll { it.startsWith(prefix) || it.equals(symbol, ignoreCase = true) }
        }
    }
}
