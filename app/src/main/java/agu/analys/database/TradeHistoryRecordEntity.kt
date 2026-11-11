package agu.analys.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entitas database Room untuk merekam siklus penuh riwayat perdagangan (Trade Lifecycle Record):
 * 1. Pengeluaran Sinyal Buy (Mode strategi apa & perhitungannya bagaimana)
 * 2. User Buy (Waktu eksekusi beli, harga masuk, jumlah coin & total modal IDR)
 * 3. Durasi Hold (Durasi menahan koin, harga puncak, max profit/drawdown)
 * 4. Sell (Waktu keluar, harga jual, alasan keluar, nominal profit/loss IDR & persentase %)
 */
@Entity(tableName = "trade_history_records")
data class TradeHistoryRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tradeUuid: String, // UUID unik untuk melacak siklus trade dari buy hingga sell
    val symbol: String, // e.g. "BTCIDR", "ETHIDR"
    val isRealTrade: Boolean = false, // true = Real, false = Simulasi
    val strategyMode: String = "SCALPING", // SCALPING, SWING, OFFICE_DAILY, SECOND_WAVE, TRENCHING, MANUAL
    val exchange: String = "TOKOCRYPTO", // Bursa: Tokocrypto

    // TAHAP 1: PENGELUARAN SINYAL BUY (MODE & PERHITUNGAN TEKNIKAL)
    val signalTime: Long = System.currentTimeMillis(), // Waktu sinyal buy dikeluarkan oleh sistem
    val signalPrice: Double = 0.0, // Harga koin saat sinyal buy terpicu
    val signalConfidence: Int = 0, // Skor keyakinan AI (0-100)
    val signalCalculationSummary: String = "", // Ringkasan perhitungan teknikal (RSI, MACD, EMA, Depth, Pressure)
    val signalReasons: String = "", // Alasan pemicu sinyal dari AI Evaluator
    val signalSnapshotJson: String? = null, // JSON snapshot lengkap metrik indikator teknikal
    val targetPrice1: Double = 0.0,
    val targetPrice2: Double = 0.0,
    val stopLossPrice: Double = 0.0,

    // TAHAP 2: USER BUY (EKSEKUSI PEMBELIAN)
    val buyTime: Long = System.currentTimeMillis(), // Waktu user buy / order terisi (FILLED)
    val buyPrice: Double = 0.0, // Harga entry pembelian user
    val buyQuantity: Double = 0.0, // Jumlah volume koin yang dibeli
    val buyTotalIdr: Double = 0.0, // Total nominal modal IDR yang masuk
    val buyFeeIdr: Double = 0.0, // Estimasi biaya / fee beli
    val buyOrderType: String = "LIMIT", // "LIMIT" atau "MARKET"

    // TAHAP 3: DURASI HOLD & EVOLUSI HARGA
    val status: String = "HOLDING", // "HOLDING" (aktif menahan) atau "CLOSED" (selesai terjual)
    val holdingDurationMs: Long = 0L, // Durasi menahan posisi dalam milidetik
    val peakPriceDuringHold: Double = 0.0, // Harga puncak tertinggi yang sempat tercapai saat hold
    val troughPriceDuringHold: Double = 0.0, // Harga terendah yang sempat tersentuh saat hold
    val maxProfitPctDuringHold: Double = 0.0, // Persentase profit tertinggi sementara (%)
    val maxDrawdownPctDuringHold: Double = 0.0, // Persentase drawdown terendah sementara (%)
    /**
     * P0-6 — sumber Peak/Trough.
     * `true`  = diambil dari high/low candle bursa (akurat, tidak bergantung sampling tick).
     * `false` = hanya dari harga tick yang tersampling (nilai bisa understate).
     */
    val peakTrackedFromCandle: Boolean = false,
    val isTrailingUsed: Boolean = false,
    val trailingLockPrice: Double? = null,
    /**
     * P0-5 — persentase trailing yang BENAR-BENAR dipakai mesin exit.
     * Sebelumnya nilai ini tidak pernah disimpan dan getter `trailingPercent` mengembalikan
     * konstanta 1,5 sehingga laporan bisa salah 10x (config nyata: 0,15).
     */
    val trailingPercentUsed: Double? = null,
    /** P0-5 — harga puncak yang menjadi acuan trailing saat posisi ditutup. */
    val trailingPeakPriceUsed: Double? = null,

    /**
     * P2-11 — penanda record contoh (seed) agar bisa dibedakan dari trade nyata dan
     * dikeluarkan dari statistik agregat (win rate, PnL, rata-rata hold).
     */
    val isSample: Boolean = false,

    // TAHAP 4: SELL & REALISASI PROFIT / LOSS
    val sellTime: Long? = null, // Waktu user sell / eksekusi keluar
    val sellPrice: Double? = null, // Harga eksekusi jual
    val sellQuantity: Double? = null, // Jumlah volume koin yang dijual
    val sellTotalIdr: Double? = null, // Total penerimaan IDR hasil penjualan
    val sellFeeIdr: Double? = null, // Biaya / fee jual
    val sellReason: String? = null, // Alasan keluar: "HIT_TP1", "HIT_TP2", "TRAILING_STOP", "STOP_LOSS", "MANUAL_SELL"
    val pnlIdr: Double? = null, // Nominal untung / rugi bersih (IDR) setelah fee
    val pnlPercent: Double? = null, // Persentase untung / rugi bersih (%)
    val isProfit: Boolean? = null // true jika pnlIdr >= 0, false jika minus
) {
    val baseAsset: String get() {
        val clean = symbol.uppercase().replace("_", "").trim()
        return clean.removeSuffix("USDT")
            .removeSuffix("USDC")
            .removeSuffix("BUSD")
            .removeSuffix("BIDR")
            .removeSuffix("IDR")
            .ifEmpty { symbol }
    }
    val quoteAsset: String get() {
        val clean = symbol.uppercase().replace("_", "").trim()
        return when {
            clean.endsWith("USDT") -> "USDT"
            clean.endsWith("USDC") -> "USDC"
            clean.endsWith("BUSD") -> "BUSD"
            clean.endsWith("BIDR") -> "BIDR"
            clean.endsWith("IDR") -> "IDR"
            else -> "IDR"
        }
    }
    val isReal: Boolean get() = isRealTrade
    val isWin: Boolean? get() = isProfit
    val buyTimestamp: Long get() = buyTime
    val sellTimestamp: Long? get() = sellTime
    val signalTimestamp: Long get() = signalTime
    val technicalBreakdown: String get() = signalCalculationSummary
    val signalReasoning: String get() = signalReasons
    val peakPrice: Double get() = peakPriceDuringHold
    val troughPrice: Double get() = troughPriceDuringHold
    val maxProfitPercent: Double get() = maxProfitPctDuringHold
    val maxDrawdownPercent: Double get() = maxDrawdownPctDuringHold
    /**
     * P0-5 — persentase trailing sebenarnya. `null` bila trailing memang tidak dipakai
     * (bukan lagi konstanta 1,5 yang tidak mencerminkan konfigurasi).
     */
    val trailingPercent: Double? get() = if (isTrailingUsed) trailingPercentUsed else null

    fun getFormattedDuration(): String = formatDuration(holdingDurationMs)

    companion object {
        fun formatDuration(durationMs: Long): String {
            if (durationMs <= 0) return "< 1m"
            val totalSeconds = durationMs / 1000
            val seconds = totalSeconds % 60
            val minutes = (totalSeconds / 60) % 60
            val hours = (totalSeconds / 3600) % 24
            val days = totalSeconds / 86400

            return when {
                days > 0 -> "${days}h ${hours}j ${minutes}m"
                hours > 0 -> "${hours}j ${minutes}m ${seconds}d"
                minutes > 0 -> "${minutes}m ${seconds}d"
                else -> "${seconds}d"
            }
        }
    }
}

@Dao
interface TradeHistoryRecordDao {
    @Query("SELECT * FROM trade_history_records ORDER BY buyTime DESC")
    fun getAllRecordsFlow(): Flow<List<TradeHistoryRecordEntity>>

    @Query("SELECT * FROM trade_history_records WHERE exchange = :exchange ORDER BY buyTime DESC")
    fun getRecordsByExchangeFlow(exchange: String): Flow<List<TradeHistoryRecordEntity>>

    @Query("SELECT * FROM trade_history_records WHERE symbol = :symbol ORDER BY buyTime DESC")
    fun getRecordsBySymbolFlow(symbol: String): Flow<List<TradeHistoryRecordEntity>>

    @Query("SELECT * FROM trade_history_records WHERE exchange = :exchange AND symbol = :symbol ORDER BY buyTime DESC")
    fun getRecordsBySymbolAndExchangeFlow(symbol: String, exchange: String): Flow<List<TradeHistoryRecordEntity>>

    @Query("SELECT * FROM trade_history_records WHERE symbol = :symbol OR symbol = :altSymbol ORDER BY buyTime DESC")
    suspend fun getRecordsForSymbol(symbol: String, altSymbol: String): List<TradeHistoryRecordEntity>

    @Query("SELECT * FROM trade_history_records WHERE exchange = :exchange AND (symbol = :symbol OR symbol = :altSymbol) ORDER BY buyTime DESC")
    suspend fun getRecordsForSymbolAndExchange(symbol: String, altSymbol: String, exchange: String): List<TradeHistoryRecordEntity>

    @Query("SELECT * FROM trade_history_records WHERE status = 'HOLDING' ORDER BY buyTime DESC")
    fun getHoldingRecordsFlow(): Flow<List<TradeHistoryRecordEntity>>

    @Query("SELECT * FROM trade_history_records WHERE exchange = :exchange AND status = 'HOLDING' ORDER BY buyTime DESC")
    fun getHoldingRecordsByExchangeFlow(exchange: String): Flow<List<TradeHistoryRecordEntity>>

    @Query("SELECT * FROM trade_history_records WHERE status = 'HOLDING'")
    suspend fun getHoldingRecords(): List<TradeHistoryRecordEntity>

    @Query("SELECT * FROM trade_history_records WHERE exchange = :exchange AND status = 'HOLDING'")
    suspend fun getHoldingRecordsByExchange(exchange: String): List<TradeHistoryRecordEntity>

    @Query("SELECT * FROM trade_history_records WHERE symbol = :symbol AND isRealTrade = :isReal AND status = 'HOLDING' ORDER BY buyTime DESC LIMIT 1")
    suspend fun getActiveHoldingForSymbol(symbol: String, isReal: Boolean): TradeHistoryRecordEntity?

    @Query("SELECT * FROM trade_history_records WHERE symbol = :symbol AND exchange = :exchange AND isRealTrade = :isReal AND status = 'HOLDING' ORDER BY buyTime DESC LIMIT 1")
    suspend fun getActiveHoldingForSymbolAndExchange(symbol: String, isReal: Boolean, exchange: String): TradeHistoryRecordEntity?

    @Query("SELECT * FROM trade_history_records WHERE tradeUuid = :uuid LIMIT 1")
    suspend fun getRecordByUuid(uuid: String): TradeHistoryRecordEntity?

    @Query("SELECT * FROM trade_history_records WHERE id = :id LIMIT 1")
    suspend fun getRecordById(id: Long): TradeHistoryRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: TradeHistoryRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<TradeHistoryRecordEntity>)

    @Update
    suspend fun updateRecord(record: TradeHistoryRecordEntity)

    @Query("DELETE FROM trade_history_records WHERE id = :id")
    suspend fun deleteRecordById(id: Long)

    @Query("DELETE FROM trade_history_records")
    suspend fun clearAllRecords()

    @Query("DELETE FROM trade_history_records WHERE exchange = :exchange")
    suspend fun clearRecordsByExchange(exchange: String)

    @Query("SELECT COUNT(*) FROM trade_history_records")
    suspend fun getRecordCount(): Int

    @Query("SELECT COUNT(*) FROM trade_history_records WHERE exchange = :exchange")
    suspend fun getRecordCountByExchange(exchange: String): Int
}
