package agu.analys.util

import agu.analys.model.MarketTick

/**
 * Penggabung peta tick untuk dua penyimpanan harga (MarketViewModel & MarketDataCoordinator).
 * Aturan: untuk simbol yang sama, tick dengan timestamp lebih baru yang menang,
 * jadi data lama tidak pernah menimpa data live.
 */
object MarketTickMerge {
    /** Mengembalikan [base] yang sama (instance identik) bila tidak ada yang berubah. */
    fun newest(base: Map<String, MarketTick>, incoming: Map<String, MarketTick>): Map<String, MarketTick> {
        if (incoming.isEmpty()) return base
        var result: MutableMap<String, MarketTick>? = null
        for ((key, tick) in incoming) {
            val current = base[key]
            if (current === tick) continue
            if (current == null || tick.timestamp >= current.timestamp) {
                if (result == null) result = base.toMutableMap()
                result[key] = tick
            }
        }
        return result ?: base
    }
}
