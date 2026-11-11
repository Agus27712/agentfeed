package agu.analys.engine

import agu.analys.model.CandleBar

object TestData {
    fun generateCandles(count: Int, startPrice: Double, trend: Double = 0.0, volatility: Double = 0.01): List<CandleBar> {
        val candles = mutableListOf<CandleBar>()
        var currentPrice = startPrice
        var time = System.currentTimeMillis() - (count * 60000L)
        
        for (i in 0 until count) {
            val open = currentPrice
            val close = currentPrice + (currentPrice * trend) + (currentPrice * (Math.random() - 0.5) * volatility)
            val high = maxOf(open, close) + (currentPrice * Math.random() * volatility * 0.5)
            val low = minOf(open, close) - (currentPrice * Math.random() * volatility * 0.5)
            val volume = 1000.0 + (Math.random() * 500.0)
            
            candles.add(CandleBar(time, open, high, low, close, volume))
            currentPrice = close
            time += 60000L
        }
        return candles
    }
}
