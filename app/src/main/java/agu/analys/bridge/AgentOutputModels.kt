package agu.analys.bridge

import agu.analys.model.ScalpingMtfSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * Central event bus buat push sinyal & portofolio ke WebSocket/SSE (Agent Bridge V3).
 */
object AgentEventBus {
    // Replay 10 agar client baru terkoneksi langsung mendapatkan riwayat sinyal terbaru
    private val _signals = MutableSharedFlow<AgentSignalOutput>(replay = 10, extraBufferCapacity = 100)
    val signals = _signals.asSharedFlow()

    private val _portfolio = MutableSharedFlow<AgentPortfolioOutput>(replay = 1, extraBufferCapacity = 10)
    val portfolio = _portfolio.asSharedFlow()

    suspend fun emitSignal(s: AgentSignalOutput) = _signals.emit(s)
    suspend fun emitPortfolio(p: AgentPortfolioOutput) = _portfolio.emit(p)
}

@Serializable
data class AgentSignalOutput(
    val ts: Long,
    val exchange: String,
    val symbol: String,
    val quote: String,
    val mode: String,
    val action: String,
    val confidence: Float,
    val price: Double,
    val tp1: Double? = null,
    val tp2: Double? = null,
    val sl: Double? = null,
    val reason: String,
    val technical: TechnicalBreakdown? = null,
    val feeTruth: FeeTruthInfo? = null,
    val type: String = "signal" // Memudahkan filter bagi client WebSocket
)

@Serializable
data class TechnicalBreakdown(
    val rsi: Float? = null,
    val macd: String? = null,
    val emaTrend: String? = null,
    val volumeSpike: Boolean = false,
    val orderbookPressure: String? = null,
    // ---- 2026-11-06: data nyata dari snapshot engine (`AISignalState.mtf`) ----
    // `rsi`/`macd` tetap null karena snapshot sinyal memang tidak membawa angka
    // indikator mentah — jangan dianggap "feed rusak"; null di sini berarti
    // "tidak tersedia", dan agen dilarang mengarang isinya.
    val mtfDirection: String? = null,
    val mtfStatusTitle: String? = null,
    val mtfCheckpointsOk: Int? = null,
    val scalpingSetup: String? = null,
    val scalpingRegime: String? = null
)

/**
 * Pembentuk [TechnicalBreakdown] dari apa yang **benar-benar** dimiliki sinyal
 * (snapshot MTF terstruktur), bukan dari field yang dulu selalu dibiarkan null.
 *
 * Sebelum 2026-11-06 hanya `orderbookPressure` yang pernah diisi sehingga agen
 * melihat `rsi:null, macd:null, emaTrend:null, volumeSpike:false` padahal
 * `AISignalState.mtf` memuat arah tren, status konfluensi, dan setup — data itu
 * kini ikut terbit. `volumeSpike` diisi dari checkpoint **Volume** (konfirmasi
 * volume valid, bukan tebakan RVOL — RVOL mentah tidak dibawa sinyal).
 */
object AgentSignalBreakdown {
    fun build(
        orderbookPressure: String?,
        mtf: ScalpingMtfSnapshot? = null,
        scalpingSetup: String? = null,
        scalpingRegime: String? = null
    ): TechnicalBreakdown {
        val mtfNyata = mtf?.takeIf { it != ScalpingMtfSnapshot() }
        if (mtfNyata == null) {
            return TechnicalBreakdown(
                orderbookPressure = orderbookPressure,
                scalpingSetup = scalpingSetup?.takeIf { it.isNotBlank() },
                scalpingRegime = scalpingRegime?.takeIf { it.isNotBlank() }
            )
        }
        val volumeConfirmed = mtfNyata.checkpoints
            .firstOrNull { it.code == "VOL" || it.label.contains("Volume", ignoreCase = true) }
            ?.isOk
            ?: (mtfNyata.setupOk && mtfNyata.triggerOk)
        return TechnicalBreakdown(
            // rsi/macd tetap null: snapshot sinyal tidak membawa angka indikator mentah
            rsi = null,
            macd = null,
            emaTrend = mtfNyata.biasDirection.uppercase().takeIf { it.isNotBlank() },
            volumeSpike = volumeConfirmed,
            orderbookPressure = orderbookPressure,
            mtfDirection = mtfNyata.biasDirection.uppercase().takeIf { it.isNotBlank() },
            mtfStatusTitle = mtfNyata.statusTitle
                .takeIf { it.isNotBlank() && it != "BELUM TERSEDIA" },
            mtfCheckpointsOk = mtfNyata.completedCount.takeIf { it > 0 },
            scalpingSetup = scalpingSetup?.takeIf { it.isNotBlank() },
            scalpingRegime = scalpingRegime?.takeIf { it.isNotBlank() }
        )
    }
}

@Serializable
data class FeeTruthInfo(
    val feeRate: Double,
    val netPrice: Double,
    val exchange: String
)

@Serializable
data class AgentPortfolioOutput(
    val ts: Long,
    val exchange: String,
    val totalIdrValue: Double,
    val totalUsdtValue: Double,
    val assets: List<AgentAsset>,
    val type: String = "portfolio"
)

@Serializable
data class AgentAsset(
    val symbol: String,
    val free: Double,
    val locked: Double,
    val avgBuyPrice: Double,
    val currentPrice: Double,
    val pnlPercent: Double,
    val pnlValue: Double
)

@Serializable
data class AgentMarketContext(
    val ts: Long,
    val exchange: String,
    val topVolume: List<String>,
    val activeSignals: List<AgentSignalOutput>,
    val type: String = "market"
)

@Serializable
data class AgentEnvelope(
    val event: String, // signal | portfolio | market | ping
    val data: String, // json stringified inner payload
    val ts: Long = System.currentTimeMillis()
)
