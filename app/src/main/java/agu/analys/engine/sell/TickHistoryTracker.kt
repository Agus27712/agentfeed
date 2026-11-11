package agu.analys.engine.sell

import agu.analys.model.SellRiskSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

data class TickPoint(
    val timestamp: Long,
    val price: Double
)

object TickHistoryTracker {
    private const val MAX_WINDOW_MS = 10 * 60 * 1000L // 10 menit
    private const val MAX_TICKS_PER_SYMBOL = 300

    private val symbolHistory = ConcurrentHashMap<String, ConcurrentLinkedDeque<TickPoint>>()

    private fun makeKey(symbol: String, exchange: String): String {
        return "${exchange.uppercase().trim()}_${symbol.uppercase().trim()}"
    }

    fun clear() {
        symbolHistory.clear()
    }

    fun recordTick(
        symbol: String,
        price: Double,
        timestamp: Long = System.currentTimeMillis(),
        exchange: String = "TOKOCRYPTO"
    ) {
        if (price <= 0.0 || symbol.isBlank()) return
        val key = makeKey(symbol, exchange)
        val deque = symbolHistory.computeIfAbsent(key) { ConcurrentLinkedDeque() }

        val last = deque.peekLast()
        // Jangan duplikasi jika timestamp dan harga persis sama
        if (last != null && last.price == price && (timestamp - last.timestamp) < 500) {
            return
        }

        deque.addLast(TickPoint(timestamp, price))

        // Evict data lebih tua dari 10 menit atau jika melebihi batas maksimum entri
        val cutoff = timestamp - MAX_WINDOW_MS
        while (deque.size > MAX_TICKS_PER_SYMBOL || (deque.peekFirst()?.let { it.timestamp < cutoff } == true)) {
            deque.pollFirst()
        }

        // Jika map terlalu besar (> 100 simbol), bersihkan simbol dorman/stale untuk proteksi memori
        if (symbolHistory.size > 100) {
            val iterator = symbolHistory.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val newest = entry.value.peekLast()
                if (newest == null || newest.timestamp < cutoff) {
                    iterator.remove()
                }
            }
        }
    }

    fun getSnapshot(
        symbol: String,
        currentPrice: Double,
        peakPrice: Double? = null,
        sellPressureRatio: Double? = null,
        now: Long = System.currentTimeMillis(),
        exchange: String = "TOKOCRYPTO"
    ): SellRiskSnapshot {
        if (currentPrice <= 0.0) {
            return SellRiskSnapshot(currentPrice = currentPrice, timestamp = now)
        }

        val key = makeKey(symbol, exchange)
        val deque = symbolHistory[key]
        if (deque == null || deque.isEmpty()) {
            return SellRiskSnapshot(
                currentPrice = currentPrice,
                peakPrice = peakPrice,
                sellPressureRatio = sellPressureRatio,
                timestamp = now
            )
        }

        val list = deque.toList() // Snapshot list untuk iterasi aman
        val target1m = now - 60_000L
        val target5m = now - 300_000L

        // Cari tick terdekat dengan 1m lalu (toleransi 15s - 120s)
        val tick1m = list.filter { it.timestamp <= (now - 30_000L) }
            .minByOrNull { Math.abs(it.timestamp - target1m) }

        // Cari tick terdekat dengan 5m lalu (toleransi 180s - 420s)
        val tick5m = list.filter { it.timestamp <= (now - 120_000L) }
            .minByOrNull { Math.abs(it.timestamp - target5m) }

        val p1m = tick1m?.price
        val p5m = tick5m?.price

        // Hitung velocity penurunan (% per menit)
        val velocity = if (tick1m != null && p1m != null && p1m > 0.0) {
            val deltaMinutes = (now - tick1m.timestamp).coerceAtLeast(15_000L) / 60_000.0
            val dropPct = ((p1m - currentPrice) / p1m) * 100.0
            if (deltaMinutes > 0.0) dropPct / deltaMinutes else 0.0
        } else {
            null
        }

        // Tentukan peakPrice yang relevan: kombinasi parameter peakPrice dengan harga tertinggi di deque
        val highestInWindow = list.maxOfOrNull { it.price }
        val effectivePeak = when {
            peakPrice != null && peakPrice > 0.0 && highestInWindow != null -> Math.max(peakPrice, highestInWindow)
            peakPrice != null && peakPrice > 0.0 -> peakPrice
            else -> highestInWindow
        }

        return SellRiskSnapshot(
            currentPrice = currentPrice,
            price1mAgo = p1m,
            price5mAgo = p5m,
            peakPrice = effectivePeak,
            sellPressureRatio = sellPressureRatio,
            priceVelocityPctPerMinute = velocity,
            timestamp = now
        )
    }

    fun clear(symbol: String? = null, exchange: String? = null) {
        if (symbol != null) {
            val s = symbol.uppercase().trim()
            if (exchange != null) {
                symbolHistory.remove(makeKey(s, exchange))
            } else {
                symbolHistory.remove(makeKey(s, "TOKOCRYPTO"))
                symbolHistory.remove(s)
            }
        } else {
            symbolHistory.clear()
        }
    }
}
