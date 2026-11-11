package agu.analys.domain.model

enum class DomainOrderType {
    BUY,
    SELL
}

enum class DomainOrderStatus {
    OPEN,
    FILLED,
    CANCELLED
}

/**
 * Model data transaksi/order tingkat domain yang mendukung eksekusi real maupun simulasi.
 */
data class DomainOrder(
    val id: String,
    val symbol: String,
    val type: DomainOrderType,
    val isReal: Boolean,
    val price: Double,
    val quantity: Double,
    val totalIdr: Double,
    val status: DomainOrderStatus,
    val createdAt: Long
)
