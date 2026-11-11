package agu.analys.domain.model

/**
 * Model data pasar (Market Data) tingkat domain.
 */
data class DomainMarketTick(
    val symbol: String,
    val price: Double,
    val change24h: Double,
    val high24h: Double,
    val low24h: Double,
    val volume24h: Double,
    val lastUpdated: Long
)

data class DomainCandle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class DomainOrderBookEntry(
    val price: Double,
    val amount: Double
)

data class DomainOrderBook(
    val symbol: String,
    val bids: List<DomainOrderBookEntry>,
    val asks: List<DomainOrderBookEntry>,
    val timestamp: Long
)
