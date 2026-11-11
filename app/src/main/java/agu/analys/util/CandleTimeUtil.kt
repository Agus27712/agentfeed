package agu.analys.util

import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.Timeframe
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Utilitas untuk mengelola siklus waktu candle, countdown penutupan candle,
 * konfirmasi status candle closed, dan sintesis real-time tick tanpa delay.
 */
object CandleTimeUtil {

    fun timeframeDurationMs(timeframe: Timeframe): Long = when (timeframe) {
        Timeframe.M1 -> 60_000L
        Timeframe.M5 -> 300_000L
        Timeframe.M15 -> 900_000L
        Timeframe.H1 -> 3_600_000L
        Timeframe.H4 -> 14_400_000L
        Timeframe.D1 -> 86_400_000L
    }

    /**
     * Menghitung sisa detik sebelum candle pada timeframe aktif ditutup (closed).
     */
    fun getCandleRemainingSeconds(timeframe: Timeframe, nowMs: Long = System.currentTimeMillis()): Long {
        val duration = timeframeDurationMs(timeframe)
        val elapsed = nowMs % duration
        val remainingMs = (duration - elapsed).coerceAtLeast(0L)
        return (remainingMs / 1000L).coerceAtLeast(0L)
    }

    /**
     * Format hitung mundur penutupan candle: "MM:SS" atau "HH:MM:SS".
     */
    fun formatCandleCountdown(timeframe: Timeframe, nowMs: Long = System.currentTimeMillis()): String {
        val remSec = getCandleRemainingSeconds(timeframe, nowMs)
        val hours = remSec / 3600
        val mins = (remSec % 3600) / 60
        val secs = remSec % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    /**
     * Menghitung progres terbentuknya candle aktif (0.0f .. 1.0f).
     */
    fun getCandleProgress(timeframe: Timeframe, nowMs: Long = System.currentTimeMillis()): Float {
        val duration = timeframeDurationMs(timeframe)
        val elapsed = nowMs % duration
        return (elapsed.toFloat() / duration.toFloat()).coerceIn(0.0f, 1.0f)
    }

    /**
     * Menghitung latency / usia tick data secara real-time dalam milidetik.
     */
    fun getTickLatencyMs(tickTimestamp: Long, nowMs: Long = System.currentTimeMillis()): Long {
        if (tickTimestamp <= 0L) return 0L
        return (nowMs - tickTimestamp).coerceAtLeast(0L)
    }

    /**
     * Format badge usia/latensi data: "⚡ 0.2s" atau "⏱️ 1.4s" atau "⚠️ Delay 6.2s"
     */
    fun formatLatency(tickTimestamp: Long, nowMs: Long = System.currentTimeMillis()): String {
        val latMs = getTickLatencyMs(tickTimestamp, nowMs)
        val sec = latMs / 1000.0
        return if (sec < 0.1) {
            "⚡ <0.1s"
        } else if (sec < 2.0) {
            String.format(Locale.US, "⚡ %.1fs", sec)
        } else if (sec < 5.0) {
            String.format(Locale.US, "⏱️ %.1fs", sec)
        } else {
            String.format(Locale.US, "⚠️ Delay %.1fs", sec)
        }
    }

    /**
     * Tandai bar terakhir sebagai **belum tutup** bila masih berada di dalam window waktunya.
     *
     * REST klines bursa selalu menyertakan candle yang sedang berjalan, tetapi model [CandleBar]
     * memberi `isClosed = true` secara default. Akibatnya saat tidak ada tick live (mode REST-only
     * atau WebSocket terputus), bar sebagian menit diperlakukan sebagai bar penuh dan RVOL/VSA
     * menjadi bias rendah (P0-1).
     */
    fun markLastCandleOpenIfForming(
        candles: List<CandleBar>,
        timeframe: Timeframe,
        nowMs: Long = System.currentTimeMillis()
    ): List<CandleBar> {
        if (candles.isEmpty()) return candles
        val duration = timeframeDurationMs(timeframe)
        val last = candles.last()
        if (last.isClosed && last.timestamp + duration > nowMs) {
            return candles.dropLast(1) + last.copy(isClosed = false)
        }
        return candles
    }

    /**
     * Menggabungkan (sintesis) candle historis dengan tick harga realtime aktif.
     * Mengeliminasi delay REST API, sehingga indikator dan chart langsung update per-tick.
     */
    fun synthesizeRealtimeCandles(
        baseCandles: List<CandleBar>,
        liveTick: MarketTick?,
        timeframe: Timeframe,
        nowMs: Long = System.currentTimeMillis()
    ): List<CandleBar> {
        if (baseCandles.isEmpty()) {
            if (liveTick == null || liveTick.price <= 0.0) return emptyList()
            val duration = timeframeDurationMs(timeframe)
            val windowStart = (nowMs / duration) * duration
            return listOf(
                CandleBar(
                    timestamp = windowStart,
                    open = liveTick.price,
                    high = liveTick.price,
                    low = liveTick.price,
                    close = liveTick.price,
                    volume = 1.0,
                    isClosed = false
                )
            )
        }

        if (liveTick == null || liveTick.price <= 0.0) return baseCandles

        val duration = timeframeDurationMs(timeframe)
        val tickTime = if (liveTick.timestamp > 0) liveTick.timestamp else nowMs
        val lastCandle = baseCandles.last()
        // Jangkar ke grid candle bursa (mis. D1 mulai 00:00 WIB), bukan kelipatan UTC
        val currentWindowStart = lastCandle.timestamp + Math.floorDiv(tickTime - lastCandle.timestamp, duration) * duration

        val result = ArrayList<CandleBar>(baseCandles.size + 1)

        if (lastCandle.timestamp == currentWindowStart) {
            // Update the forming candle in-place
            for (i in 0 until baseCandles.size - 1) {
                result.add(baseCandles[i].copy(isClosed = true))
            }
            val updatedLast = lastCandle.copy(
                high = max(lastCandle.high, liveTick.price),
                low = min(lastCandle.low, liveTick.price),
                close = liveTick.price,
                isClosed = false
            )
            result.add(updatedLast)
        } else if (currentWindowStart > lastCandle.timestamp) {
            // The previous candle is closed, synthesize a new forming candle
            for (c in baseCandles) {
                result.add(c.copy(isClosed = true))
            }
            val newForming = CandleBar(
                timestamp = currentWindowStart,
                open = lastCandle.close,
                high = max(lastCandle.close, liveTick.price),
                low = min(lastCandle.close, liveTick.price),
                close = liveTick.price,
                volume = 0.0,
                isClosed = false
            )
            result.add(newForming)
        } else {
            // Tick is older or within window, preserve base candles
            for (c in baseCandles) {
                result.add(c)
            }
        }

        return result
    }
}
