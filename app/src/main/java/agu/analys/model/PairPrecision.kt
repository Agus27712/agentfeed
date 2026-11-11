package agu.analys.model

data class PairPrecision(
    val id: String,
    val symbol: String,
    val baseCurrency: String,
    val tradedCurrency: String,
    val priceDecimals: Int,
    val quantityDecimals: Int
)
