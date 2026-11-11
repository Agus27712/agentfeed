package agu.analys.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import agu.analys.AppContextProvider
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "real_trades")
data class RealTradeEntity(
    @PrimaryKey val id: String, // trade_id or order_id
    val symbol: String,         // e.g. "BTCIDR" or "btcidr"
    val price: Double,
    val qty: Double,
    val amount: Double,         // price * qty
    val time: Long,             // timestamp
    val side: String,           // "BUY" or "SELL"
    val isBuyer: Boolean,
    val strategyMode: String = "MANUAL",
    val holdingDurationMs: Long? = null,
    val entryPrice: Double? = null,
    val entryTimestamp: Long? = null,
    val pnlIdr: Double? = null,
    val pnlPercent: Double? = null,
    val isTrailingUsed: Boolean = false,
    val trailingPercent: Double? = null,
    val trailingPeakPrice: Double? = null,
    val trailingLockPrice: Double? = null,
    val signalSnapshotJson: String? = null,
    val exchange: String = "TOKOCRYPTO" // Bursa: Tokocrypto
)

@Entity(tableName = "real_open_orders")
data class RealOpenOrderEntity(
    @PrimaryKey val orderId: String,
    val symbol: String,
    val side: String, // "BUY" or "SELL"
    val type: String, // "LIMIT" or "MARKET"
    val price: Double,
    val quantity: Double,
    val executedQty: Double,
    val status: String,
    val time: Long,
    val exchange: String = "TOKOCRYPTO" // Bursa: Tokocrypto
)

@Entity(tableName = "signal_logs")
data class SignalLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symbol: String,                 // e.g. "BTCIDR"
    val action: String,                 // "BUY", "SELL", "HOLD"
    val strategyMode: String = "SCALPING", // "SCALPING", "SWING", "OFFICE_DAILY", "SECOND_WAVE", "TRENCHING"
    val confidence: Int,                // Confidence score at firing time (0-100)
    val sentiment: String = "",         // e.g. "BULLISH_REVERSAL"
    val entryPrice: Double,             // Price at time of firing
    val targetPrice1: Double = 0.0,
    val targetPrice2: Double = 0.0,
    val stopLoss: Double = 0.0,
    val firedAt: Long = System.currentTimeMillis(),
    val reasoning: String = "",         // Text explanation at firing
    val scalpingStage: String = "",     // e.g. "ENTRY", "STRONG_ENTRY"
    val exchange: String = "TOKOCRYPTO", // Bursa: Tokocrypto
    
    // Performance Outcome Tracking
    val outcomeStatus: String = "TRACKING", // "TRACKING", "HIT_TP1", "HIT_TP2", "HIT_SL", "INVALIDATED", "EXPIRED", "MANUAL_WIN", "MANUAL_LOSS"
    val maxProfitPct: Double = 0.0,         // Highest positive % reached during tracking
    val maxDrawdownPct: Double = 0.0,       // Worst negative % reached during tracking
    val exitPrice: Double? = null,          // Final price when resolved
    val realizedPnlPct: Double? = null,     // Realized profit/loss % outcome
    val peakPrice: Double = 0.0,            // Highest price observed
    val troughPrice: Double = 0.0,          // Lowest price observed
    val resolvedAt: Long? = null,           // Timestamp when resolved
    val resolutionNote: String? = null,     // Detail note e.g. "Hit TP1 at Rp 1.520.000.000 (+2.8%)"

    // Pipeline scalping KriptoYoi — dicatat saat sinyal pertama kali terpicu (untuk analisis akurasi per setup/skor)
    val scalpingSetup: String = "",         // BREAKOUT, BREAKOUT_RETEST, LIQUIDITY_SWEEP, TREND_PULLBACK
    val scalpingScore: Int = 0,             // Skor engine 0-100
    val scalpingScoreCategory: String = "", // NO_TRADE, WEAK, WATCH, STRONG, VERY_STRONG
    val scalpingRegime: String = ""         // Regime pasar dari MarketRegimeEngine
)

@Dao
interface RealTradeDao {
    @Query("SELECT * FROM real_trades ORDER BY time DESC")
    fun getAllTradesFlow(): Flow<List<RealTradeEntity>>

    @Query("SELECT * FROM real_trades WHERE exchange = :exchange ORDER BY time DESC")
    fun getTradesByExchangeFlow(exchange: String): Flow<List<RealTradeEntity>>

    @Query("SELECT * FROM real_trades WHERE symbol = :symbol ORDER BY time DESC")
    suspend fun getTradesBySymbol(symbol: String): List<RealTradeEntity>

    @Query("SELECT * FROM real_trades WHERE symbol = :symbol AND exchange = :exchange ORDER BY time DESC")
    suspend fun getTradesBySymbolAndExchange(symbol: String, exchange: String): List<RealTradeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrades(trades: List<RealTradeEntity>)

    @Query("DELETE FROM real_trades")
    suspend fun clearAllTrades()

    @Query("DELETE FROM real_trades WHERE exchange = :exchange")
    suspend fun clearTradesByExchange(exchange: String)

    /** Baris BUY yang dicatat lokal saat order dikirim (id "local_..."), belum digabung dengan trade server. */
    @Query("SELECT * FROM real_trades WHERE id LIKE 'local_%' AND exchange = :exchange")
    suspend fun getLocalTradesByExchange(exchange: String): List<RealTradeEntity>

    @Query("DELETE FROM real_trades WHERE id IN (:ids)")
    suspend fun deleteTradesByIds(ids: List<String>)

    // Real Open Orders Cache (Offline/Caching Support)
    @Query("SELECT * FROM real_open_orders ORDER BY time DESC")
    fun getOpenOrdersFlow(): Flow<List<RealOpenOrderEntity>>

    @Query("SELECT * FROM real_open_orders WHERE exchange = :exchange ORDER BY time DESC")
    fun getOpenOrdersByExchangeFlow(exchange: String): Flow<List<RealOpenOrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOpenOrders(orders: List<RealOpenOrderEntity>)

    @Query("DELETE FROM real_open_orders")
    suspend fun clearOpenOrders()

    @Query("DELETE FROM real_open_orders WHERE exchange = :exchange")
    suspend fun clearOpenOrdersByExchange(exchange: String)

    @Query("DELETE FROM real_open_orders WHERE orderId = :orderId")
    suspend fun deleteOpenOrderById(orderId: String)
}

@Dao
interface SignalLogDao {
    @Query("SELECT * FROM signal_logs ORDER BY firedAt DESC")
    fun getAllLogsFlow(): Flow<List<SignalLogEntity>>

    @Query("SELECT * FROM signal_logs WHERE exchange = :exchange ORDER BY firedAt DESC")
    fun getLogsByExchangeFlow(exchange: String): Flow<List<SignalLogEntity>>

    @Query("SELECT * FROM signal_logs WHERE symbol = :symbol ORDER BY firedAt DESC")
    fun getLogsBySymbolFlow(symbol: String): Flow<List<SignalLogEntity>>

    @Query("SELECT * FROM signal_logs WHERE symbol = :symbol AND exchange = :exchange ORDER BY firedAt DESC")
    fun getLogsBySymbolAndExchangeFlow(symbol: String, exchange: String): Flow<List<SignalLogEntity>>

    @Query("SELECT * FROM signal_logs WHERE outcomeStatus = 'TRACKING' ORDER BY firedAt DESC")
    fun getActiveTrackingLogsFlow(): Flow<List<SignalLogEntity>>

    @Query("SELECT * FROM signal_logs WHERE exchange = :exchange AND outcomeStatus = 'TRACKING' ORDER BY firedAt DESC")
    fun getActiveTrackingLogsByExchangeFlow(exchange: String): Flow<List<SignalLogEntity>>

    @Query("SELECT * FROM signal_logs WHERE outcomeStatus = 'TRACKING'")
    suspend fun getActiveTrackingLogs(): List<SignalLogEntity>

    @Query("SELECT * FROM signal_logs WHERE exchange = :exchange AND outcomeStatus = 'TRACKING'")
    suspend fun getActiveTrackingLogsForExchange(exchange: String): List<SignalLogEntity>

    @Query("SELECT * FROM signal_logs WHERE symbol = :symbol AND outcomeStatus = 'TRACKING' ORDER BY firedAt DESC")
    suspend fun getActiveTrackingLogsForSymbol(symbol: String): List<SignalLogEntity>

    @Query("SELECT * FROM signal_logs WHERE symbol = :symbol AND exchange = :exchange AND outcomeStatus = 'TRACKING' ORDER BY firedAt DESC")
    suspend fun getActiveTrackingLogsForSymbolAndExchange(symbol: String, exchange: String): List<SignalLogEntity>

    @Query("SELECT * FROM signal_logs WHERE symbol = :symbol ORDER BY firedAt DESC LIMIT 1")
    suspend fun getLatestLogForSymbol(symbol: String): SignalLogEntity?

    @Query("SELECT * FROM signal_logs WHERE symbol = :symbol AND exchange = :exchange ORDER BY firedAt DESC LIMIT 1")
    suspend fun getLatestLogForSymbolAndExchange(symbol: String, exchange: String): SignalLogEntity?

    @Query("SELECT * FROM signal_logs WHERE id = :id")
    suspend fun getLogById(id: Long): SignalLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SignalLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<SignalLogEntity>)

    @Update
    suspend fun updateLog(log: SignalLogEntity)

    @Query("DELETE FROM signal_logs WHERE id = :id")
    suspend fun deleteLogById(id: Long)

    @Query("DELETE FROM signal_logs WHERE id IN (:ids)")
    suspend fun deleteLogsByIds(ids: List<Long>)

    @Query("DELETE FROM signal_logs")
    suspend fun clearAllLogs()

    @Query("DELETE FROM signal_logs WHERE exchange = :exchange")
    suspend fun clearLogsByExchange(exchange: String)

    @Query("SELECT COUNT(*) FROM signal_logs")
    suspend fun getLogCount(): Int

    @Query("SELECT COUNT(*) FROM signal_logs WHERE exchange = :exchange")
    suspend fun getLogCountByExchange(exchange: String): Int
}

@Database(
    entities = [RealTradeEntity::class, RealOpenOrderEntity::class, SignalLogEntity::class, TradeHistoryRecordEntity::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun realTradeDao(): RealTradeDao
    abstract fun signalLogDao(): SignalLogDao
    abstract fun tradeHistoryRecordDao(): TradeHistoryRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 7 -> 8: tambah kolom setup/skor/regime engine scalping ke signal_logs.
         * Migrasi nyata (bukan destruktif) agar riwayat trade, log sinyal, dan jurnal tidak terhapus.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signal_logs ADD COLUMN scalpingSetup TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE signal_logs ADD COLUMN scalpingScore INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE signal_logs ADD COLUMN scalpingScoreCategory TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE signal_logs ADD COLUMN scalpingRegime TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 8 -> 9: integritas audit trade_history_records.
         * - `trailingPercentUsed` / `trailingPeakPriceUsed`: nilai trailing yang sebenarnya
         *   (sebelumnya konstanta 1,5 di getter → laporan bisa salah 10x).
         * - `isSample`: penanda record contoh (seed) agar bisa dikeluarkan dari statistik.
         * Migrasi nyata agar jurnal pengguna tidak terhapus.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trade_history_records ADD COLUMN trailingPercentUsed REAL")
                db.execSQL("ALTER TABLE trade_history_records ADD COLUMN trailingPeakPriceUsed REAL")
                db.execSQL("ALTER TABLE trade_history_records ADD COLUMN isSample INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trade_history_records ADD COLUMN peakTrackedFromCandle INTEGER NOT NULL DEFAULT 0")
                // Record lama yang menandai trailing aktif dianggap contoh/kontaminasi lama:
                // nilainya tidak dapat dipercaya lagi karena persentase sebenarnya tidak pernah disimpan.
                db.execSQL("UPDATE trade_history_records SET isSample = 1 WHERE tradeUuid LIKE 'seed-trade-%'")
            }
        }

        fun getInstance(): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val context = AppContextProvider.context
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agu_analys_db"
                )
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
