package agu.analys.engine.indicators

import agu.analys.model.CandleBar
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.indicators.ATRIndicator
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.adx.ADXIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure indicator math — SEMUA indikator (RSI, EMA, MACD, Bollinger Bands, ATR) dihitung lewat
 * engine TA4J (RSIIndicator, EMAIndicator, MACDIndicator, BollingerBands*Indicator, ATRIndicator).
 * Rumus manual (mis. emaFallback/emaSeriesFallback/calculateRsiFallback) HANYA dipakai sebagai
 * fallback kalau TA4J melempar exception atau menghasilkan NaN/Infinite — bukan jalur utama.
 *
 * Scalping extensions (P0): relativeVolume, choppiness, adx — exchange-agnostic (IDR/USDT).
 */
object IndicatorMath {

    /**
     * Konversi List<CandleBar> menjadi TA4J BarSeries
     */
    fun toBarSeries(candles: List<CandleBar>, name: String = "series"): BarSeries {
        val series = BaseBarSeriesBuilder().withName(name).build()
        if (candles.isEmpty()) return series

        var lastTime = 0L
        for (c in candles) {
            val time = if (c.timestamp > lastTime) c.timestamp else lastTime + 1000L
            lastTime = time
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.of("UTC"))
            val open = if (c.open > 0) c.open else c.close
            val high = max(c.high, max(open, c.close))
            val low = min(c.low, min(open, c.close))
            val volume = max(0.0, c.volume)
            series.addBar(Duration.ofMinutes(1), zdt, open, high, low, c.close, volume)
        }
        return series
    }

    private fun closesToBarSeries(closes: DoubleArray, name: String = "closes"): BarSeries {
        val series = BaseBarSeriesBuilder().withName(name).build()
        if (closes.isEmpty()) return series
        val start = Instant.now().minusSeconds(closes.size.toLong() * 60L)
        for (i in closes.indices) {
            val c = closes[i]
            val zdt = ZonedDateTime.ofInstant(start.plusSeconds(i.toLong() * 60L), ZoneId.of("UTC"))
            series.addBar(Duration.ofMinutes(1), zdt, c, c, c, c, 0.0)
        }
        return series
    }

    fun rsi(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.size <= period) return 50.0
        return try {
            val series = toBarSeries(history)
            if (series.barCount <= period) return calculateRsiFallback(history, period)
            val closePrice = ClosePriceIndicator(series)
            val rsiIndicator = RSIIndicator(closePrice, period)
            val value = rsiIndicator.getValue(series.endIndex).doubleValue()
            if (value.isNaN() || value.isInfinite()) calculateRsiFallback(history, period) else value
        } catch (_: Exception) {
            calculateRsiFallback(history, period)
        }
    }

    private fun calculateRsiFallback(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.size <= period) return 50.0

        var gain = 0.0
        var loss = 0.0

        for (i in 1..period) {
            val change = history[i].close - history[i - 1].close
            if (change >= 0.0) gain += change else loss -= change
        }

        var averageGain = gain / period
        var averageLoss = loss / period

        for (i in period + 1 until history.size) {
            val change = history[i].close - history[i - 1].close
            val currentGain = if (change > 0.0) change else 0.0
            val currentLoss = if (change < 0.0) -change else 0.0

            averageGain = ((averageGain * (period - 1)) + currentGain) / period
            averageLoss = ((averageLoss * (period - 1)) + currentLoss) / period
        }

        if (averageLoss == 0.0) return if (averageGain == 0.0) 50.0 else 100.0

        val rs = averageGain / averageLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun ema(values: DoubleArray, period: Int): Double {
        if (period <= 0 || values.isEmpty()) return 0.0
        return try {
            val series = closesToBarSeries(values)
            val emaIndicator = EMAIndicator(ClosePriceIndicator(series), period)
            val value = emaIndicator.getValue(series.endIndex).doubleValue()
            if (value.isNaN() || value.isInfinite()) emaFallback(values, period) else value
        } catch (_: Exception) {
            emaFallback(values, period)
        }
    }

    fun ema(values: List<Double>, period: Int): Double = ema(values.toDoubleArray(), period)

    private fun emaFallback(values: DoubleArray, period: Int): Double {
        if (period <= 0 || values.isEmpty()) return 0.0
        val start = max(0, values.size - period * 3)
        var ema = values[start]
        val multiplier = 2.0 / (period + 1.0)

        for (i in start + 1 until values.size) {
            ema = (values[i] - ema) * multiplier + ema
        }
        return ema
    }

    @JvmName("emaCandles")
    fun ema(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.isEmpty()) return 0.0
        return try {
            val series = toBarSeries(history)
            val closePrice = ClosePriceIndicator(series)
            val emaIndicator = EMAIndicator(closePrice, period)
            val value = emaIndicator.getValue(series.endIndex).doubleValue()
            if (value.isNaN() || value.isInfinite()) ema(history.map { it.close }, period) else value
        } catch (_: Exception) {
            ema(history.map { it.close }, period)
        }
    }

    fun emaSeries(values: DoubleArray, period: Int): DoubleArray {
        if (period <= 0 || values.isEmpty()) return DoubleArray(0)
        return try {
            val series = closesToBarSeries(values)
            val emaIndicator = EMAIndicator(ClosePriceIndicator(series), period)
            val result = DoubleArray(values.size) { i -> emaIndicator.getValue(i).doubleValue() }
            if (result.any { it.isNaN() || it.isInfinite() }) emaSeriesFallback(values, period) else result
        } catch (_: Exception) {
            emaSeriesFallback(values, period)
        }
    }

    fun emaSeries(values: List<Double>, period: Int): DoubleArray = emaSeries(values.toDoubleArray(), period)

    private fun emaSeriesFallback(values: DoubleArray, period: Int): DoubleArray {
        if (period <= 0 || values.isEmpty()) return DoubleArray(0)

        val result = DoubleArray(values.size)
        val multiplier = 2.0 / (period + 1.0)
        var ema = values[0]
        result[0] = ema

        for (i in 1 until values.size) {
            ema = (values[i] - ema) * multiplier + ema
            result[i] = ema
        }
        return result
    }

    data class MacdResult(val macd: DoubleArray, val signal: DoubleArray) {
        val size: Int get() = min(macd.size, signal.size)
        val isEmpty: Boolean get() = size == 0
        fun isNotEmpty(): Boolean = size > 0

        val lastMacd: Double get() = if (macd.isNotEmpty()) macd.last() else 0.0
        val lastSignal: Double get() = if (signal.isNotEmpty()) signal.last() else 0.0

        fun last(): Pair<Double, Double> {
            if (isEmpty) throw NoSuchElementException("MacdResult is empty")
            return macd.last() to signal.last()
        }

        fun lastOrNull(): Pair<Double, Double>? {
            return if (isNotEmpty()) macd.last() to signal.last() else null
        }

        operator fun get(index: Int): Pair<Double, Double> {
            return macd[index] to signal[index]
        }
    }

    fun macdSeries(closes: DoubleArray, fastPeriod: Int, slowPeriod: Int, signalPeriod: Int): MacdResult {
        if (closes.isEmpty() || fastPeriod <= 0 || slowPeriod <= 0 || signalPeriod <= 0) {
            return MacdResult(DoubleArray(0), DoubleArray(0))
        }
        return try {
            val series = closesToBarSeries(closes)
            val closePrice = ClosePriceIndicator(series)
            val macdIndicator = MACDIndicator(closePrice, fastPeriod, slowPeriod)
            val signalIndicator = EMAIndicator(macdIndicator, signalPeriod)
            val macdLine = DoubleArray(closes.size) { i -> macdIndicator.getValue(i).doubleValue() }
            val signalLine = DoubleArray(closes.size) { i -> signalIndicator.getValue(i).doubleValue() }
            val invalid = macdLine.any { it.isNaN() || it.isInfinite() } || signalLine.any { it.isNaN() || it.isInfinite() }
            if (invalid) macdSeriesFallback(closes, fastPeriod, slowPeriod, signalPeriod) else MacdResult(macdLine, signalLine)
        } catch (_: Exception) {
            macdSeriesFallback(closes, fastPeriod, slowPeriod, signalPeriod)
        }
    }

    fun macdSeries(closes: List<Double>, fastPeriod: Int, slowPeriod: Int, signalPeriod: Int): MacdResult =
        macdSeries(closes.toDoubleArray(), fastPeriod, slowPeriod, signalPeriod)

    private fun macdSeriesFallback(closes: DoubleArray, fastPeriod: Int, slowPeriod: Int, signalPeriod: Int): MacdResult {
        val emaFast = emaSeriesFallback(closes, fastPeriod)
        val emaSlow = emaSeriesFallback(closes, slowPeriod)

        val macdLine = DoubleArray(closes.size)
        for (i in closes.indices) {
            macdLine[i] = emaFast[i] - emaSlow[i]
        }

        val signalLine = emaSeriesFallback(macdLine, signalPeriod)
        return MacdResult(macdLine, signalLine)
    }

    @JvmName("bollingerCandles")
    fun bollinger(history: List<CandleBar>, period: Int): Pair<Double, Double> {
        if (period <= 0 || history.size < period) return 0.0 to 0.0
        return try {
            val series = toBarSeries(history)
            val closePrice = ClosePriceIndicator(series)
            val sma = SMAIndicator(closePrice, period)
            val stdDev = StandardDeviationIndicator(closePrice, period)
            val upper = BollingerBandsUpperIndicator(BollingerBandsMiddleIndicator(sma), stdDev)
            val lower = BollingerBandsLowerIndicator(BollingerBandsMiddleIndicator(sma), stdDev)
            val upVal = upper.getValue(series.endIndex).doubleValue()
            val lowVal = lower.getValue(series.endIndex).doubleValue()
            if (upVal.isNaN() || lowVal.isNaN()) bollinger(history.map { it.close }, period) else lowVal to upVal
        } catch (_: Exception) {
            bollinger(history.map { it.close }, period)
        }
    }

    fun bollinger(closes: DoubleArray, period: Int): Pair<Double, Double> {
        if (period <= 0 || closes.size < period) return 0.0 to 0.0

        val start = closes.size - period
        var sum = 0.0

        for (i in start until closes.size) {
            sum += closes[i]
        }
        val mean = sum / period

        var varianceSum = 0.0
        for (i in start until closes.size) {
            val diff = closes[i] - mean
            varianceSum += diff * diff
        }

        val deviation = sqrt(varianceSum / period)
        return (mean - (2 * deviation)) to (mean + (2 * deviation))
    }

    fun bollinger(closes: List<Double>, period: Int): Pair<Double, Double> {
        if (period <= 0 || closes.size < period) return 0.0 to 0.0

        val start = closes.size - period
        var sum = 0.0

        for (i in start until closes.size) {
            sum += closes[i]
        }
        val mean = sum / period

        var varianceSum = 0.0
        for (i in start until closes.size) {
            val diff = closes[i] - mean
            varianceSum += diff * diff
        }

        val deviation = sqrt(varianceSum / period)
        return (mean - (2 * deviation)) to (mean + (2 * deviation))
    }

    fun atr(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.size <= 1) return 0.0
        return try {
            val series = toBarSeries(history)
            val atrIndicator = ATRIndicator(series, period)
            val value = atrIndicator.getValue(series.endIndex).doubleValue()
            if (value.isNaN() || value.isInfinite()) calculateAtrFallback(history, period) else value
        } catch (_: Exception) {
            calculateAtrFallback(history, period)
        }
    }

    private fun calculateAtrFallback(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.size <= 1) return 0.0
        if (history.size <= period) {
            var sumTr = 0.0
            for (i in 1 until history.size) {
                val high = history[i].high
                val low = history[i].low
                val prevClose = history[i - 1].close
                val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
                sumTr += tr
            }
            return sumTr / (history.size - 1)
        }

        var sumTr = 0.0
        for (i in 1..period) {
            val high = history[i].high
            val low = history[i].low
            val prevClose = history[i - 1].close
            val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
            sumTr += tr
        }

        var currentAtr = sumTr / period

        for (i in period + 1 until history.size) {
            val high = history[i].high
            val low = history[i].low
            val prevClose = history[i - 1].close
            val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
            currentAtr = ((currentAtr * (period - 1)) + tr) / period
        }

        return currentAtr
    }

    fun rollingVwap(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.isEmpty()) return 0.0

        val start = max(0, history.size - period)
        var sumPV = 0.0
        var sumV = 0.0

        for (i in start until history.size) {
            val candle = history[i]
            val typical = (candle.high + candle.low + candle.close) / 3.0
            sumPV += typical * candle.volume
            sumV += candle.volume
        }

        return if (sumV > 0.0) sumPV / sumV else history.last().close
    }

    data class DivergenceResult(
        val hasBullishDivergence: Boolean = false,
        val hasBearishDivergence: Boolean = false,
        val detail: String = ""
    )

    fun detectDivergence(history: List<CandleBar>, rsiPeriod: Int = 14, lookback: Int = 25): DivergenceResult {
        if (history.size < lookback + rsiPeriod) return DivergenceResult()
        val slice = history.takeLast(lookback + rsiPeriod)
        val rsiValues = mutableListOf<Double>()
        for (i in rsiPeriod until slice.size) {
            val sub = slice.subList(0, i + 1)
            rsiValues.add(rsi(sub, rsiPeriod))
        }
        if (rsiValues.size < 8) return DivergenceResult()
        val priceLows = slice.takeLast(rsiValues.size).map { it.low }

        val currentLow = priceLows.last()
        val currentRsi = rsiValues.last()

        val half = rsiValues.size / 2
        var prevLowIdx = -1
        var minLow = Double.MAX_VALUE
        for (i in 0 until half) {
            if (priceLows[i] < minLow) {
                minLow = priceLows[i]
                prevLowIdx = i
            }
        }

        if (prevLowIdx >= 0) {
            val prevLow = priceLows[prevLowIdx]
            val prevRsi = rsiValues[prevLowIdx]
            if (currentLow <= prevLow * 1.008 && currentRsi > prevRsi + 2.5 && currentRsi < 68.0) {
                return DivergenceResult(
                    hasBullishDivergence = true,
                    detail = "Bullish Divergence: Harga uji low tapi RSI naik dari ${prevRsi.toInt()} ke ${currentRsi.toInt()}"
                )
            }
        }
        return DivergenceResult()
    }

    // -------------------------------------------------------------------------
    // Scalping P0 extensions — RVOL / CHOP / ADX (exchange-agnostic)
    // -------------------------------------------------------------------------

    /**
     * Fraksi waktu yang sudah berjalan pada sebuah bar (0..1].
     *
     * Bar yang sedang berjalan baru mengumpulkan **sebagian** volume menit. Tanpa normalisasi,
     * angka itu dibandingkan dengan rata-rata bar penuh → RVOL selalu terlihat rendah
     * (mis. 0,76x padahal sebenarnya 1,46x).
     *
     * @return 1.0 bila timestamp/durasi tidak valid (anggap bar sudah penuh)
     */
    fun elapsedFraction(barStartMs: Long, barDurationMs: Long, nowMs: Long): Double {
        if (barStartMs <= 0L || barDurationMs <= 0L) return 1.0
        val elapsed = (nowMs - barStartMs).toDouble()
        if (elapsed <= 0.0) return MIN_ELAPSED_FRACTION
        return (elapsed / barDurationMs.toDouble()).coerceIn(MIN_ELAPSED_FRACTION, 1.0)
    }

    /**
     * Proyeksikan volume bar yang belum tutup ke setara bar penuh (`volume / fraksiWaktu`).
     * Bila bar sudah tutup (fraksi = 1.0) nilainya tidak berubah.
     */
    fun projectVolumeToFullBar(volume: Double, elapsedFraction: Double): Double {
        if (volume <= 0.0) return 0.0
        val f = elapsedFraction.coerceIn(MIN_ELAPSED_FRACTION, 1.0)
        return volume / f
    }

    /** Batas bawah fraksi waktu — cegah pembagian dengan angka mendekati nol di awal bar. */
    private const val MIN_ELAPSED_FRACTION = 0.05

    /**
     * Relative Volume: volume bar terakhir / rata-rata volume [period] bar **yang sudah tutup**.
     *
     * Bar yang masih berjalan diproyeksikan dulu ke setara bar penuh supaya pembilang dan
     * penyebut berada pada basis waktu yang sama. Default 1.0 jika data kurang.
     *
     * @param barDurationMs durasi satu bar (M1 = 60_000). Dipakai untuk proyeksi bar berjalan.
     * @param nowMs waktu acuan; `<= 0` berarti pakai `System.currentTimeMillis()`.
     */
    fun relativeVolume(
        candles: List<CandleBar>,
        period: Int = 20,
        barDurationMs: Long = 60_000L,
        nowMs: Long = 0L
    ): Double {
        if (period <= 0 || candles.size < 2) return 1.0
        val last = candles.last()
        val rawCurrent = last.volume.coerceAtLeast(0.0)
        val current = if (last.isClosed) {
            rawCurrent
        } else {
            val ref = if (nowMs > 0L) nowMs else System.currentTimeMillis()
            projectVolumeToFullBar(rawCurrent, elapsedFraction(last.timestamp, barDurationMs, ref))
        }

        // Penyebut HANYA dari bar yang sudah tutup, dan tidak boleh ikut memuat bar terakhir.
        val closed = candles.filter { it.isClosed }
        val hist = if (last.isClosed) closed.dropLast(1) else closed
        val pool = hist.ifEmpty { candles.dropLast(1) }
        if (pool.isEmpty()) return 1.0
        val window = pool.takeLast(min(period, pool.size))
        val avg = window.map { it.volume.coerceAtLeast(0.0) }.average()
        if (avg <= 0.0) return 1.0
        return current / avg
    }

    /**
     * Choppiness Index (classic):
     * 100 * log10( sum(ATR1 over n) / (highestHigh - lowestLow) ) / log10(n)
     * Range tipikal ~0–100; tinggi = ranging/choppy, rendah = trending.
     */
    fun choppiness(candles: List<CandleBar>, period: Int = 14): Double {
        if (period < 2 || candles.size < period + 1) return 50.0
        val slice = candles.takeLast(period + 1)
        var sumTr = 0.0
        var highest = Double.NEGATIVE_INFINITY
        var lowest = Double.POSITIVE_INFINITY
        for (i in 1 until slice.size) {
            val c = slice[i]
            val prevClose = slice[i - 1].close
            val tr = max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            sumTr += tr
            if (c.high > highest) highest = c.high
            if (c.low < lowest) lowest = c.low
        }
        val range = highest - lowest
        if (range <= 0.0 || sumTr <= 0.0) return 50.0
        val raw = 100.0 * log10(sumTr / range) / log10(period.toDouble())
        return raw.coerceIn(0.0, 100.0)
    }

    /**
     * ADX via TA4J ADXIndicator; fallback manual Wilder-style jika TA4J gagal.
     */
    fun adx(history: List<CandleBar>, period: Int = 14): Double {
        if (period <= 0 || history.size < period * 2) return 0.0
        return try {
            val series = toBarSeries(history)
            if (series.barCount < period * 2) return calculateAdxFallback(history, period)
            val adxIndicator = ADXIndicator(series, period)
            val value = adxIndicator.getValue(series.endIndex).doubleValue()
            if (value.isNaN() || value.isInfinite()) calculateAdxFallback(history, period) else value.coerceIn(0.0, 100.0)
        } catch (_: Exception) {
            calculateAdxFallback(history, period)
        }
    }

    /** Manual ADX (simplified Wilder) — fallback only. */
    private fun calculateAdxFallback(history: List<CandleBar>, period: Int): Double {
        if (history.size < period + 2) return 0.0
        val plusDm = DoubleArray(history.size)
        val minusDm = DoubleArray(history.size)
        val tr = DoubleArray(history.size)
        for (i in 1 until history.size) {
            val up = history[i].high - history[i - 1].high
            val down = history[i - 1].low - history[i].low
            plusDm[i] = if (up > down && up > 0) up else 0.0
            minusDm[i] = if (down > up && down > 0) down else 0.0
            val high = history[i].high
            val low = history[i].low
            val prevClose = history[i - 1].close
            tr[i] = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
        }
        if (history.size <= period) return 0.0

        var smoothTr = 0.0
        var smoothPlus = 0.0
        var smoothMinus = 0.0
        for (i in 1..period) {
            smoothTr += tr[i]
            smoothPlus += plusDm[i]
            smoothMinus += minusDm[i]
        }

        val dxList = mutableListOf<Double>()
        for (i in period + 1 until history.size) {
            smoothTr = smoothTr - (smoothTr / period) + tr[i]
            smoothPlus = smoothPlus - (smoothPlus / period) + plusDm[i]
            smoothMinus = smoothMinus - (smoothMinus / period) + minusDm[i]
            val plusDi = if (smoothTr > 0) 100.0 * smoothPlus / smoothTr else 0.0
            val minusDi = if (smoothTr > 0) 100.0 * smoothMinus / smoothTr else 0.0
            val diSum = plusDi + minusDi
            val dx = if (diSum > 0) 100.0 * abs(plusDi - minusDi) / diSum else 0.0
            dxList += dx
        }
        if (dxList.isEmpty()) return 0.0
        // Smoothed ADX: average of last `period` DX if available
        val adxWindow = dxList.takeLast(min(period, dxList.size))
        return adxWindow.average().coerceIn(0.0, 100.0)
    }
}
