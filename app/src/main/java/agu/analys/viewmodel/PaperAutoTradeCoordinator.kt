package agu.analys.viewmodel

import agu.analys.config.StrategyMode
import agu.analys.engine.scalping.AutoBuySizing
import agu.analys.engine.scalping.PaperAtr
import agu.analys.engine.scalping.PaperAutoBuyAllocation
import agu.analys.engine.scalping.PaperExitDecision
import agu.analys.engine.scalping.PaperExitFsmState
import agu.analys.engine.scalping.PaperExitPhase
import agu.analys.engine.scalping.PaperExitReason
import agu.analys.engine.scalping.PaperExitSnapshot
import agu.analys.engine.scalping.PaperExitStateMachine
import agu.analys.engine.scalping.PaperRehydrate
import agu.analys.engine.scalping.PaperRehydrateAction
import agu.analys.engine.scalping.PaperRehydrateScope
import agu.analys.engine.scalping.PaperStopResolver
import agu.analys.engine.scalping.PaperStopSource
import agu.analys.engine.scalping.PaperTradeRecord
import agu.analys.engine.scalping.ScalpExitParams
import agu.analys.engine.scalping.SignalTransition
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.trading.PaperExitMomentumWindow
import agu.analys.trading.PaperFirstBarResolver
import agu.analys.trading.PaperHandBackResolver
import agu.analys.trading.PaperHandBackSource
import agu.analys.trading.PaperIntrabarPeakResolver
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.util.AppLogManager
import agu.analys.util.ExchangeRateManager
import agu.analys.util.PriceFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto-buy & auto-exit **paper trade** (P2) — decision support, bukan bot profit.
 *
 * Batas keras yang dipegang file ini:
 * - Hanya menyentuh posisi **simulasi** (`isReal = false`). Tidak ada jalur ke eksekusi
 *   uang riil dari sinyal scalp (larangan `AUDIT-2026-11-08.md` §P2 dan `AGENTS.md`).
 * - Ukuran posisi dihitung lewat [agu.analys.engine.scalping.PaperAutoTradeConfig],
 *   tidak pernah melebihi daya beli saldo kas simulasi.
 * - Exit memakai parameter `exit` dari [agu.analys.engine.scalping.ScalpingRuntimeConfig],
 *   yang sama dengan yang diekspor ke `AUDIT-2026-11-08.md`.
 *
 * **Kepemilikan exit.** Saat engine P2 aktif untuk sebuah pair, jalur auto-sell lama
 * (`checkTrailingForMode` → TP1/TP2/SL dari `positionStore`) sengaja **dimatikan** untuk
 * pair itu, supaya tidak terjadi jual ganda dan supaya satu-satunya sumber kebenaran exit
 * adalah mesin yang sudah diuji paritas terhadap replay.
 */
object PaperAutoTradeRuntime {
    /** State machine exit per `exchange|symbol` untuk posisi paper yang sedang berjalan. */
    private val machines = ConcurrentHashMap<String, PaperExitStateMachine>()

    /** Waktu buka posisi per key — dipakai untuk mengisi `openedAt` di jurnal. */
    private val openedAtMap = ConcurrentHashMap<String, Long>()

    /** Bucket menit terakhir yang sudah dihitung sebagai satu bar M1, per key. */
    private val lastBarBucketMap = ConcurrentHashMap<String, Long>()

    /** Simbol asli (apa adanya) per key — key sendiri menormalkan simbol sehingga lossy. */
    private val rawSymbolMap = ConcurrentHashMap<String, String>()

    /**
     * Key posisi yang sedang dievaluasi exit.
     *
     * [evaluatePaperAutoExit] dipanggil dari dua tempat sekaligus (thread latar price-feed dan
     * thread utama). Tanpa penjaga, dua pemeriksaan bisa sama-sama lolos lalu menutup posisi
     * dua kali. Set ini memastikan hanya satu evaluasi berjalan per posisi.
     */
    private val evaluatingKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Progres FSM terakhir yang **sudah** ditulis ke disk, per key (P4.4).
     *
     * Dipakai untuk memutuskan apakah penulisan berikutnya perlu: perubahan yang mengubah perilaku
     * exit (flag BE/trailing/partial, SL naik, jumlah bar) ditulis segera, sedangkan pergeseran
     * `peak` saja ditahan [FSM_WRITE_THROTTLE_MS] karena `evaluatePaperAutoExit` dipanggil tiap tick.
     */
    private val lastPersistedFsmMap = ConcurrentHashMap<String, PaperExitFsmState>()

    /** Waktu (ms) penulisan FSM terakhir per key — pasangan [lastPersistedFsmMap]. */
    private val lastFsmWriteAtMap = ConcurrentHashMap<String, Long>()

    fun key(exchange: String, symbol: String): String =
        "${exchange.uppercase()}|${symbol.uppercase().replace("_", "")}"

    /**
     * Klaim hak evaluasi untuk satu posisi.
     * @return `true` bila berhasil diklaim — pemanggil **wajib** memanggil [endEvaluation].
     */
    fun beginEvaluation(exchange: String, symbol: String): Boolean =
        evaluatingKeys.add(key(exchange, symbol))

    fun endEvaluation(exchange: String, symbol: String) {
        evaluatingKeys.remove(key(exchange, symbol))
    }

    fun machineFor(exchange: String, symbol: String): PaperExitStateMachine? =
        machines[key(exchange, symbol)]

    fun phaseFor(exchange: String, symbol: String): PaperExitPhase? =
        machines[key(exchange, symbol)]?.phase

    fun hasActivePosition(exchange: String, symbol: String): Boolean =
        machines.containsKey(key(exchange, symbol))

    /**
     * Apakah engine paper (P2) yang memegang hak exit untuk pair ini.
     *
     * Jalur auto-sell lama (`checkTrailingForMode`, `deployTrailingOrder`) wajib memakai
     * predikat ini supaya tidak ada dua mesin yang menjual posisi simulasi yang sama.
     * Mematikan `auto_sell_enabled` saja **tidak cukup**: pemeriksaan trailing lama
     * (`updateTrailingPrice`) hanya melihat `isTrailingEnabled`, bukan flag itu.
     *
     * `autoExitEnabled = false` berarti pengguna mematikan auto-exit paper — kepemilikan
     * **dikembalikan** ke jalur lama, karena kalau tidak, posisi akan menggantung tanpa
     * mesin yang menjualnya ([TradingViewModel.evaluatePaperAutoExit] berhenti lebih awal).
     */
    fun ownsExit(exchange: String, symbol: String, autoExitEnabled: Boolean): Boolean =
        autoExitEnabled && hasActivePosition(exchange, symbol)

    /**
     * Simbol asli yang punya posisi paper terbuka di bursa ini.
     *
     * Dipakai `startTrailingPolling` agar posisi paper tetap dievaluasi walau bukan pair
     * yang sedang dibuka di layar.
     */
    fun activeSymbols(exchange: String): List<String> {
        val prefix = "${exchange.uppercase()}|"
        return machines.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { rawSymbolMap[it] }
    }

    /**
     * Pasangan (bursa, simbol asli) untuk **semua** mesin aktif di semua bursa.
     *
     * Dipakai serah-terima P9: mematikan auto-exit harus melepas posisi di bursa mana pun,
     * bukan hanya bursa yang sedang dipilih. Urutan di-sort supaya log dan uji deterministik.
     */
    fun activePositions(): List<Pair<String, String>> =
        machines.keys
            .mapNotNull { k ->
                val raw = rawSymbolMap[k] ?: return@mapNotNull null
                k.substringBefore('|') to raw
            }
            .sortedWith(compareBy({ it.first }, { it.second }))

    /**
     * @param openedAt waktu buka posisi asli. Dipakai rehidrasi setelah restart supaya
     *   `openedAt` di jurnal tetap waktu masuk yang sebenarnya, bukan waktu app dibuka.
     *   `0` berarti "sekarang".
     * @param markFirstTickAsNewBar `true` hanya di jalur rehidrasi. `put` menandai bucket menit
     *   berjalan sebagai "sudah dihitung", sehingga tanpa ini tick pertama setelah restart dianggap
     *   bukan bar M1 baru dan time-stop baru dievaluasi sampai batas menit berikutnya. Set `true`
     *   membuat tick pertama ikut menambah `barsHeld`, sehingga posisi yang seharusnya sudah
     *   kehabisan waktu langsung ditutup.
     */
    fun put(
        exchange: String,
        symbol: String,
        machine: PaperExitStateMachine,
        openedAt: Long = 0L,
        markFirstTickAsNewBar: Boolean = false
    ) {
        val k = key(exchange, symbol)
        machines[k] = machine
        rawSymbolMap[k] = symbol
        val now = System.currentTimeMillis()
        openedAtMap[k] = if (openedAt > 0L) openedAt else now
        lastBarBucketMap[k] = if (markFirstTickAsNewBar) FIRST_TICK_BUCKET else now / 60_000L
    }

    /**
     * Catat bahwa [state] sudah ada di disk tanpa menulis apa pun.
     *
     * Dipakai setelah rehidrasi: state di disk dan di memori sudah sinkron, jadi penulisan pertama
     * berikutnya tidak perlu dipaksa.
     */
    fun seedPersistedFsm(exchange: String, symbol: String, state: PaperExitFsmState) {
        val k = key(exchange, symbol)
        lastPersistedFsmMap[k] = state
        lastFsmWriteAtMap[k] = System.currentTimeMillis()
    }

    /**
     * Putuskan apakah progres [state] perlu ditulis ke disk sekarang.
     *
     * Menulis **selalu** bila [force], atau bila ada perubahan yang mengubah perilaku exit
     * (flag proteksi, SL naik, jumlah bar). Bila hanya `peak` yang bergeser — dan `peak` bergerak
     * tiap tick — penulisan ditahan sampai [FSM_WRITE_THROTTLE_MS] lewat.
     *
     * Bila mengembalikan `true`, pencatatan internal langsung diperbarui. Aman karena pemanggil
     * menulis secara sinkron tepat setelah ini.
     */
    fun shouldWriteFsm(
        exchange: String,
        symbol: String,
        state: PaperExitFsmState,
        now: Long,
        force: Boolean = false
    ): Boolean {
        val k = key(exchange, symbol)
        val last = lastPersistedFsmMap[k]
        val lastAt = lastFsmWriteAtMap[k] ?: 0L
        val semanticChange = last == null ||
            state.isBeArmed != last.isBeArmed ||
            state.isTrailArmed != last.isTrailArmed ||
            state.isPartialDone != last.isPartialDone ||
            state.barsHeld != last.barsHeld ||
            state.activeStopLoss > last.activeStopLoss + FSM_SL_EPS
        if (!force && !semanticChange && now - lastAt < FSM_WRITE_THROTTLE_MS) return false
        lastPersistedFsmMap[k] = state
        lastFsmWriteAtMap[k] = now
        return true
    }

    fun openedAt(exchange: String, symbol: String): Long =
        openedAtMap[key(exchange, symbol)] ?: 0L

    /**
     * Tandai bucket menit untuk evaluasi per-tick.
     *
     * P8 — hasilnya memisahkan "dievaluasi sebagai bar baru" dari "boleh menambah `barsHeld`":
     * tick pertama sesudah rehidrasi adalah bar baru (time-stop harus langsung dicek) tetapi
     * menitnya sudah dihitung `PaperRehydrate.mergedBarsHeld`.
     */
    fun markBarBucket(exchange: String, symbol: String, now: Long): BarBucketMark {
        val k = key(exchange, symbol)
        val bucket = now / 60_000L
        val last = lastBarBucketMap[k]
        lastBarBucketMap[k] = bucket
        return BarBucketMark(
            isNewBar = last == null || last != bucket,
            firstTickAfterRehydrate = last == FIRST_TICK_BUCKET
        )
    }

    fun clear(exchange: String, symbol: String) {
        val k = key(exchange, symbol)
        machines.remove(k)
        openedAtMap.remove(k)
        lastBarBucketMap.remove(k)
        rawSymbolMap.remove(k)
        lastPersistedFsmMap.remove(k)
        lastFsmWriteAtMap.remove(k)
    }

    fun clearAll() {
        machines.clear()
        openedAtMap.clear()
        lastBarBucketMap.clear()
        rawSymbolMap.clear()
        evaluatingKeys.clear()
        lastPersistedFsmMap.clear()
        lastFsmWriteAtMap.clear()
    }

    /** Bucket sentinel: tidak mungkin sama dengan `now / 60_000`, jadi tick pertama selalu "bar baru". */
    private const val FIRST_TICK_BUCKET = -1L

    /**
     * P8 — hasil [markBarBucket]: apakah tick ini bar baru, dan apakah ia tick pertama sesudah
     * rehidrasi (menitnya sudah dihitung `PaperRehydrate.mergedBarsHeld`).
     */
    data class BarBucketMark(
        val isNewBar: Boolean,
        val firstTickAfterRehydrate: Boolean
    )

    /** Toleransi kenaikan SL agar noise floating point tidak memicu penulisan. */
    private const val FSM_SL_EPS = 1e-9

    /** Jeda minimum penulisan saat **hanya** `peak` yang berubah. */
    private const val FSM_WRITE_THROTTLE_MS = 2_000L
}

/**
 * Apakah engine paper (P2) yang memegang hak exit untuk pair ini.
 *
 * Dipakai jalur auto-sell lama (`checkTrailingForMode`, `deployTrailingOrder`) untuk
 * menyerahkan kepemilikan exit sepenuhnya ke [PaperExitStateMachine] selama engine aktif.
 */
fun TradingViewModel.paperEngineOwnsExit(exchange: String, symbol: String): Boolean =
    PaperAutoTradeRuntime.ownsExit(exchange, symbol, prefs.paperAutoTradeConfig.autoExitEnabled)

/**
 * ATR% bar M1 terhadap harga acuan — dipakai mesin exit untuk S8 (BE/trailing proporsional ATR).
 *
 * Rumusnya ada di [PaperAtr] (murni, bisa diuji); ini hanya pembungkus yang mengambil ATR dari
 * state engine.
 *
 * @return 0.0 bila ATR belum tersedia, sehingga mesin exit jatuh ke nilai persen tetap.
 */
private fun TradingViewModel.atrPctAt(referencePrice: Double): Double =
    PaperAtr.of(engine.indicators.value.atr, referencePrice)

/**
 * Bangun & pasang state machine exit paper untuk satu posisi simulasi.
 *
 * Dipakai bersama oleh **auto-buy** ([tryPaperAutoBuy]) dan **beli manual**
 * ([syncSimulationTradeToPositionStore]) supaya semua posisi paper memakai mesin exit yang sama.
 *
 * Sebelumnya mesin ini hanya dibuat untuk auto-buy, sehingga posisi yang dibeli manual berjalan
 * **tanpa SL/BE/trailing terkelola** — persis kasus AMP/USDT: menahan −2,76% selama 9 menit
 * tanpa stop apa pun.
 *
 * **S3 — SL keras wajib.** Bila tidak ada SL valid dari sinyal (`0 < SL < entry`), SL dihitung
 * dari **lantai friksi** (`minFrictionMultiple × friksi round-trip`) supaya posisi tidak pernah
 * menggantung tanpa lantai risiko. Bila `minFrictionMultiple = 0` dan sinyal tidak punya SL,
 * posisi sengaja dibiarkan tanpa mesin (perilaku lama) dan dicatat di log.
 *
 * @param force `true` menimpa mesin yang sudah ada (dipakai auto-buy yang nilainya otoritatif
 *   dari sinyal transisi). `false` tidak menimpa — pemanggil manual aman dipanggil berulang.
 * @return `true` bila mesin terpasang (atau sudah ada).
 */
fun TradingViewModel.armPaperExitEngine(
    symbol: String,
    exchange: String,
    entryPrice: Double,
    stopLoss: Double,
    tp1: Double,
    tp2: Double,
    atrPct: Double = 0.0,
    force: Boolean = false
): Boolean {
    val exchangeKey = exchange.uppercase()
    if (!prefs.paperAutoTradeConfig.autoExitEnabled) return false
    if (entryPrice <= 0.0 || !entryPrice.isFinite()) return false

    val pair = TradingPair.fromCustomSymbol(raw = symbol, exchange = exchange)
    if (!force && PaperAutoTradeRuntime.machineFor(exchangeKey, pair.symbol) != null) return true

    val runtime = prefs.scalpingRuntimeConfig
    val fees = prefs.feeConfigFor(pair.symbol)

    // S3 + P2 — SL keras. SL sinyal dipakai HANYA bila sudah memenuhi lantai friksi; bila lebih
    // rapat (mis. sinyal SWING/INTRADAY yang SL-nya 1,5–2,8%), SL **dilebarkan** ke lantai.
    // Lantai ini adalah invarian yang sama yang ditegakkan `ScalpingRiskEngine` untuk sinyal
    // scalping — jalur posisi paper dulu melewatinya begitu saja, sehingga satu SL bisa bernilai
    // −1,3R…−2R padahal friksi round-trip ≈ 0,97%.
    val stopPlan = PaperStopResolver.resolveForEntry(
        signalSl = stopLoss,
        entry = entryPrice,
        buyFeePct = fees.buyTakerPct,
        sellFeePct = fees.sellTakerPct,
        slippagePct = runtime.slippagePct,
        minFrictionMultiple = runtime.minFrictionMultiple
    )
    val floorPct = stopPlan.floorPct
    val resolvedStop = stopPlan.stop
    if (!resolvedStop.usable) {
        AppLogManager.service(
            "PaperAutoExit",
            "⏭️ [${pair.symbol}] Beli tanpa SL yang bisa dipakai & lantai friksi nonaktif — " +
                "posisi dibiarkan tanpa mesin exit."
        )
        return false
    }
    if (resolvedStop.source == PaperStopSource.FRICTION_FLOOR) {
        AppLogManager.service(
            "PaperAutoExit",
            "🛡️ [${pair.symbol}] SL sinyal ${PriceFormatter.formatPrice(stopLoss, quoteAsset = pair.quoteAsset)} " +
                "tidak memenuhi lantai friksi ${String.format(java.util.Locale.US, "%.2f", floorPct)}% " +
                "(${String.format(java.util.Locale.US, "%.1f", runtime.minFrictionMultiple)}× friksi) — " +
                "SL dilebarkan ke ${PriceFormatter.formatPrice(resolvedStop.price, quoteAsset = pair.quoteAsset)}."
        )
    }
    val effectiveSl = resolvedStop.price
    if (effectiveSl <= 0.0 || effectiveSl >= entryPrice) return false

    val tp1v = if (tp1 > 0.0) tp1 else entryPrice
    val tp2v = if (tp2 > 0.0) tp2 else entryPrice * 1.02

    val machine = PaperExitStateMachine(
        entry = entryPrice,
        stopLoss = effectiveSl,
        tp1 = tp1v,
        tp2 = tp2v,
        buyFeePct = fees.buyTakerPct,
        sellFeePct = fees.sellTakerPct,
        slippagePct = runtime.slippagePct,
        params = runtime.exit,
        atrPct = atrPct
    )
    PaperAutoTradeRuntime.put(exchangeKey, pair.symbol, machine)
    // Penanda persisten: satu-satunya jejak bahwa exit pair ini dipegang engine paper.
    // Tanpa ini posisi menggantung setelah restart (lihat [PaperRehydrate]).
    positionStore.setPaperEngineOwned(
        symbol = pair.symbol, isReal = false, exchange = exchangeKey, owned = true
    )
    // Simpan progres awal (termasuk `atrPct`) supaya restart berikutnya tidak kehilangan
    // ambang BE/trailing proporsional ATR (P4.4).
    persistPaperFsm(exchangeKey, pair.symbol, machine, force = true)

    // Kepemilikan exit tunggal: matikan jalur auto-sell lama untuk pair ini supaya tidak ada
    // dua mesin yang menjual posisi yang sama.
    positionStore.setAutoSellParams(
        symbol = pair.symbol,
        enabled = false,
        tp1Price = tp1v,
        tp1Percent = 50.0,
        tp2Price = tp2v,
        tp2Percent = 50.0,
        isReal = false,
        exchange = exchangeKey,
        stopLossPrice = effectiveSl
    )
    // `setHolding` mempertahankan `isTrailingEnabled` dari posisi sebelumnya, jadi bendera
    // trailing lama bisa terbawa ke posisi paper baru. Bersihkan sejak tick pertama.
    positionStore.setTrailingStop(
        symbol = pair.symbol,
        enabled = false,
        trailingPercent = 0.0,
        isReal = false,
        exchange = exchangeKey
    )

    startTrailingPolling()
    return true
}

/**
 * Tulis progres state machine exit paper ke disk (P4.4).
 *
 * Dipanggil dari jalur yang sering berjalan (`evaluatePaperAutoExit` dievaluasi **per tick harga**),
 * jadi keputusan menulis diserahkan ke [PaperAutoTradeRuntime.shouldWriteFsm]: perubahan yang
 * mengubah perilaku exit ditulis segera, pergeseran `peak` saja ditahan beberapa detik.
 *
 * Kegagalan tulis tidak boleh menghentikan trading — `apply()` di `SharedPreferences` asinkron dan
 * tidak melempar. Yang penting mesin di memori tetap benar; disk hanya untuk bertahan dari restart.
 *
 * @param force `true` untuk penulisan pertama setelah mesin dipasang.
 */
fun TradingViewModel.persistPaperFsm(
    exchange: String,
    symbol: String,
    machine: PaperExitStateMachine,
    force: Boolean = false
) {
    val pair = TradingPair.fromCustomSymbol(raw = symbol, exchange = exchange)
    val snapshot = machine.snapshot()
    val now = System.currentTimeMillis()
    if (!PaperAutoTradeRuntime.shouldWriteFsm(exchange, pair.symbol, snapshot, now, force)) return
    positionStore.setPaperFsmState(
        symbol = pair.symbol,
        isReal = false,
        exchange = exchange,
        stateJson = snapshot.toJsonString()
    )
}

/**
 * Buka posisi **simulasi** otomatis saat sinyal scalp terkonfirmasi.
 *
 * Syarat (semua harus terpenuhi):
 * 1. toggle auto-buy paper aktif di Pengaturan,
 * 2. aksi sinyal `BUY`,
 * 3. **mode transisi `SCALPING`** — posisi paper & mesin exit ini hanya untuk horizon scalping (P2),
 * 4. `scalpingStage` memenuhi ambang minimum yang dipilih,
 * 5. belum ada posisi simulasi terbuka untuk pair tersebut,
 * 6. jumlah posisi paper bersamaan belum mencapai `maxConcurrentPositions` (global, `0` = tanpa batas),
 * 7. kuantitas hasil perhitungan > 0.
 *
 * @return `true` bila order simulasi berhasil dikirim.
 */
fun TradingViewModel.tryPaperAutoBuy(transition: SignalTransition, exchange: String): Boolean {
    val cfg = prefs.paperAutoTradeConfig
    if (!cfg.autoBuyEnabled) return false

    val signal = transition.signal
    if (signal.action != SignalAction.BUY) return false

    // P2 — posisi paper + mesin exit ini adalah produk **SCALPING**: horizon M1, time-stop
    // `maxHoldBarsM1` (≈2 jam), dan lantai friksi dirancang untuk horizon itu. Evaluator
    // SWING/INTRADAY/OFFICE_DAILY memasang `ScalpingStage.ENTRY` yang sama untuk sinyal BUY,
    // sehingga sinyal mereka **lolos** `stageQualifies` tanpa gerbang ini. Akibatnya jurnal paper
    // — dasar seluruh keputusan "apakah scalp punya edge" — tercampur strategi ber-horizon harian,
    // dan time-stop scalping memotongnya sebagai `TIMEOUT` palsu.
    if (transition.mode != StrategyMode.SCALPING) {
        if (cfg.stageQualifies(signal.scalpingStage)) {
            AppLogManager.service(
                "PaperAutoBuy",
                "⏭️ [${transition.symbol}] Auto-buy dilewati: mode ${transition.mode.name} bukan " +
                    "SCALPING — posisi paper & mesin exit hanya untuk scalping."
            )
        }
        return false
    }
    if (!cfg.stageQualifies(signal.scalpingStage)) return false

    val price = signal.entryPrice
    if (price <= 0.0) return false

    val pair = TradingPair.fromCustomSymbol(raw = transition.symbol, exchange = exchange)
    val existing = positionStore.get(pair.symbol, isReal = false, exchange = exchange)
    if (existing.isHolding) return false

    // 2026-10-26 — jeda entri-ulang: setelah sell paper, sinyal BUY berikut langsung
    // memicu beli ulang tanpa evaluasi baru. Contoh terukur: WINUSDT 25/10/2026 — entri
    // ulang 60 detik setelah sell pada pair yang sedang trending naik, dua kali di hari
    // yang sama. Keadaannya dibaca dari catatan sell persisten, bukan state memori, supaya
    // jeda tetap berlaku setelah app di-restart. Beli manual tidak digated (keputusan sadar).
    val lastSoldAt = positionStore.lastPaperSoldAt(pair.symbol, exchange)
    if (!cfg.reentryAllowed(lastSoldAt, System.currentTimeMillis())) {
        val sisaMenit = cfg.reentryWaitMinutesRemaining(lastSoldAt, System.currentTimeMillis())
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        AppLogManager.service(
            "PaperAutoBuy",
            "⏳ [${pair.symbol}] Auto-buy ditunda: jeda entri-ulang ${cfg.reentryCooldownMinutes} mnt " +
                "belum habis (sisa ±$sisaMenit mnt) — sell terakhir ${fmt.format(java.util.Date(lastSoldAt))}."
        )
        return false
    }

    // Batas jumlah posisi paper bersamaan (global, lintas bursa). Kas yang sudah terpotong posisi
    // yang masih hold tetap "terpakai", jadi tanpa gerbang ini auto-buy bisa terus menumpuk posisi
    // sampai order berikutnya ditolak karena saldo kurang — bukan karena sinyalnya buruk.
    // Gerbang ini sengaja hanya di jalur auto-buy; beli manual tetap keputusan sadar pengguna.
    val activePaperPositions = positionStore.countActivePaperPositions()
    if (cfg.positionLimitReached(activePaperPositions)) {
        AppLogManager.service(
            "PaperAutoBuy",
            "⏭️ [${pair.symbol}] Auto-buy dilewati: batas posisi bersamaan tercapai " +
                "($activePaperPositions/${cfg.maxConcurrentPositions}). " +
                "Tutup posisi lain atau naikkan batas di Pengaturan."
        )
        return false
    }

    val available = simCoordinator.wallet.value.getAvailableQuote(pair.quoteAsset)

    // Biaya & slippage diambil dari sumber yang **sama** dengan yang dipakai order engine
    // (`SimulationCoordinator.setSlippagePct` <- `prefs.scalpingRuntimeConfig.slippagePct`).
    // Kalau keduanya berbeda, ukuran yang dicek di sini tidak sama dengan yang benar-benar
    // dipotong — persis kelas bug yang membuat auto-buy selalu ditolak.
    val quoteIsUsdt = PriceFormatter.isUsdtQuote(pair.quoteAsset)
    val usdtIdrRate = ExchangeRateManager.currentRate()
    val fees = prefs.feeConfigFor(pair.symbol)
    val runtimeConfig = prefs.scalpingRuntimeConfig

    // Sizing dan mesin exit wajib memakai SL yang sama. Mode RISK_BASED dulu menghitung jarak
    // dari SL sinyal mentah, padahal mesin melebarkannya ke lantai friksi — risiko aktual bisa
    // jauh melebihi `riskPctPerTrade`. Resolver ini murni dan bisa dipakai jalur real nanti.
    val sizingStopPlan = PaperStopResolver.resolveForEntry(
        signalSl = signal.stopLoss,
        entry = price,
        buyFeePct = fees.buyTakerPct,
        sellFeePct = fees.sellTakerPct,
        slippagePct = runtimeConfig.slippagePct,
        minFrictionMultiple = runtimeConfig.minFrictionMultiple
    )
    val sizingStop = if (sizingStopPlan.stop.usable) sizingStopPlan.stop.price else signal.stopLoss

    val sizing = cfg.plan(
        availableQuote = available,
        price = price,
        stopLoss = sizingStop,
        quoteIsUsdt = quoteIsUsdt,
        usdtIdrRate = usdtIdrRate,
        feeRate = fees.buyTakerPct / 100.0,
        slippagePct = runtimeConfig.slippagePct
    )

    val (qty, notionalQuote) = when (sizing) {
        is AutoBuySizing.Skip -> {
            AppLogManager.service(
                "PaperAutoBuy",
                "⏭️ [${pair.symbol}] Auto-buy dilewati: ${sizing.reason} " +
                    "(kas ${pair.quoteAsset} tersedia ${PriceFormatter.formatPrice(available, quoteAsset = pair.quoteAsset)})."
            )
            return false
        }

        is AutoBuySizing.Ok -> sizing.quantity to sizing.notionalQuote
    }

    // Catat nominal yang benar-benar dipakai, supaya "Rp 100.000" tidak lagi bisa disalahartikan
    // sebagai $100.000 di log.
    val nominalNote = when (cfg.allocation) {
        PaperAutoBuyAllocation.FIXED_QUOTE -> {
            val idr = PriceFormatter.formatMoney(cfg.fixedIdrAmount, "IDR")
            if (quoteIsUsdt) {
                "$idr → ${PriceFormatter.formatPrice(notionalQuote, quoteAsset = pair.quoteAsset)}" +
                    " (kurs ${PriceFormatter.formatPrice(usdtIdrRate, quoteAsset = "IDR")})"
            } else {
                "nominal $idr"
            }
        }

        PaperAutoBuyAllocation.RISK_BASED ->
            "risiko ${String.format(java.util.Locale.US, "%.1f", cfg.riskPctPerTrade)}% → " +
                PriceFormatter.formatPrice(notionalQuote, quoteAsset = pair.quoteAsset)
    }

    val res = simCoordinator.submitOrder(
        pair = pair,
        currentPrice = price,
        side = SimulationOrderSide.BUY,
        type = SimulationOrderType.MARKET,
        price = price,
        stopPrice = 0.0,
        quantity = qty,
        strategyMode = transition.mode.name,
        entryPrice = price,
        entryTimestamp = System.currentTimeMillis()
    )

    if (res !is SimulationOrderResult.Success) {
        val msg = (res as? SimulationOrderResult.Error)?.message ?: "tidak diketahui"
        AppLogManager.service("PaperAutoBuy", "❌ [${pair.symbol}] Auto-buy simulasi gagal: $msg")
        return false
    }

    val exchangeKey = exchange.uppercase()
    // Harga entry yang otoritatif adalah harga fill, bukan harga sinyal. `submitOrder` sudah
    // memanggil `syncSimulationTradeToPositionStore`, jadi jangan menimpa posisi yang baru
    // dibukukan di sana. Fallback `markBought` hanya untuk pemanggil yang tidak punya callback.
    val order = res.order
    val fillPrice = order.filledAvgPrice.takeIf { it.isFinite() && it > 0.0 } ?: price
    val filledQty = order.filledQuantity.takeIf { it.isFinite() && it > 0.0 } ?: qty
    if (!positionStore.get(pair.symbol, isReal = false, exchange = exchangeKey).isHolding) {
        positionStore.markBought(
            symbol = pair.symbol,
            entryPrice = fillPrice,
            invested = filledQty * fillPrice,
            quantity = filledQty,
            isReal = false,
            exchange = exchangeKey
        )
    }

    val fillStopPlan = PaperStopResolver.resolveForEntry(
        signalSl = signal.stopLoss,
        entry = fillPrice,
        buyFeePct = fees.buyTakerPct,
        sellFeePct = fees.sellTakerPct,
        slippagePct = runtimeConfig.slippagePct,
        minFrictionMultiple = runtimeConfig.minFrictionMultiple
    )

    // Satu keputusan kepemilikan: engine hanya memiliki exit bila ia benar-benar terpasang.
    // `p2OwnsExit` lama melihat SL sinyal mentah, sehingga bisa menyalakan legacy dengan SL
    // sintetis `entry * 0.99` saat SL sinyal tidak valid. `force = true` membuat parameter
    // transisi otoritatif atas arm yang mungkin sudah dijalankan callback sinkronisasi.
    val engineArmed = cfg.autoExitEnabled && armPaperExitEngine(
        symbol = pair.symbol,
        exchange = exchangeKey,
        entryPrice = fillPrice,
        stopLoss = signal.stopLoss,
        tp1 = signal.targetPrice1,
        tp2 = signal.targetPrice2,
        atrPct = atrPctAt(fillPrice),
        force = true
    )
    val engineOwnsExit = PaperAutoTradeRuntime.ownsExit(
        exchange = exchangeKey,
        symbol = pair.symbol,
        autoExitEnabled = cfg.autoExitEnabled
    )

    if (!engineOwnsExit) {
        val tp1Percent = 50.0
        positionStore.setAutoSellParams(
            symbol = pair.symbol,
            // Fallback legacy juga memakai SL hasil resolver, bukan SL sinyal mentah.
            enabled = fillStopPlan.stop.usable,
            tp1Price = signal.targetPrice1,
            tp1Percent = tp1Percent,
            tp2Price = signal.targetPrice2,
            tp2Percent = 100.0 - tp1Percent,
            isReal = false,
            exchange = exchangeKey,
            stopLossPrice = fillStopPlan.stop.price
        )
        if (!fillStopPlan.stop.usable) {
            AppLogManager.service(
                "PaperAutoBuy",
                "⚠️ [${pair.symbol}] Auto-buy terbuka tanpa stop yang valid — auto-sell legacy " +
                    "TIDAK dinyalakan. Pasang SL manual atau aktifkan lantai friksi."
            )
        }
    } else if (!engineArmed) {
        AppLogManager.service(
            "PaperAutoExit",
            "⚠️ [${pair.symbol}] Mesin exit sudah aktif dari jalur sinkronisasi; " +
                "pemasangan ulang dengan parameter transisi gagal."
        )
    }

    positionCoordinator.refreshPosition(pair.symbol)

    val modeLabel = runtimeConfig.exit.mode.label
    val exitLabel = when {
        engineOwnsExit -> modeLabel
        fillStopPlan.stop.usable -> "auto-sell bawaan"
        else -> "TANPA exit (SL tidak tersedia)"
    }
    AppLogManager.trade(
        "PaperAutoBuy",
        "🤖 [${pair.symbol}] Auto-buy SIMULASI ${String.format(java.util.Locale.US, "%.8f", filledQty)} ${pair.baseAsset} @ " +
            "${PriceFormatter.formatPrice(fillPrice, quoteAsset = pair.quoteAsset)} | $nominalNote | " +
            "Stage ${signal.scalpingStage.name} | Exit: $exitLabel"
    )

    return true
}

/**
 * Evaluasi exit paper untuk satu tick harga pada posisi simulasi yang sedang berjalan.
 *
 * Pemeriksaan level (SL / TP2 / TP1 partial / BE / trailing / early) dijalankan tiap tick
 * supaya proteksi tidak menunggu akhir bar. Hitungan bar M1 dan time-stop hanya maju sekali
 * per menit, lihat [PaperExitStateMachine.onBar] parameter `isNewBar`.
 *
 * Jendela momentum **tidak** diteruskan pemanggil. P6: dulu pemanggil foreground mengirim
 * `recentCandles.takeLast(15)` sementara pemanggil latar mengirim kosong, sehingga `EARLY_PROFIT`
 * hanya mungkin terjadi pada posisi yang kebetulan sedang dibuka pair-nya. Sekarang kedua
 * pemanggil membaca dari sumber yang sama: [PaperExitMomentumWindow] atas cache M1.
 *
 * @return keputusan exit bila posisi baru saja ditutup, atau `null`.
 */
fun TradingViewModel.evaluatePaperAutoExit(
    symbol: String,
    exchange: String,
    currentPrice: Double
): PaperExitDecision? {
    // Kunci per posisi: cegah dua pemanggil (thread utama & latar) mengevaluasi bersamaan
    // sehingga posisi tertutup dua kali atau jurnalnya hilang.
    if (!PaperAutoTradeRuntime.beginEvaluation(exchange, symbol)) return null
    try {
        return evaluatePaperAutoExitLocked(symbol, exchange, currentPrice)
    } finally {
        PaperAutoTradeRuntime.endEvaluation(exchange, symbol)
    }
}

private fun TradingViewModel.evaluatePaperAutoExitLocked(
    symbol: String,
    exchange: String,
    currentPrice: Double
): PaperExitDecision? {
    val machine = PaperAutoTradeRuntime.machineFor(exchange, symbol)
    if (machine == null) {
        // Tidak ada posisi paper berjalan -> pastikan kartu FSM kosong.
        publishPaperExitSnapshot(exchange = exchange, symbol = symbol, snapshot = null)
        return null
    }

    // Posisi simulasi sudah tidak ada (mis. dijual manual dari layar lain) -> lepas state
    // machine supaya kartu FSM tidak menampilkan posisi hantu, dan supaya kepemilikan exit
    // tidak terkunci selamanya oleh mesin yang sudah tidak punya posisi.
    val pair = TradingPair.fromCustomSymbol(raw = symbol, exchange = exchange)
    if (!positionStore.get(pair.symbol, isReal = false, exchange = exchange).isHolding) {
        PaperAutoTradeRuntime.clear(exchange, symbol)
        positionStore.setPaperEngineOwned(symbol = pair.symbol, isReal = false, exchange = exchange, owned = false)
        // Cabang ini melepas mesin tanpa lewat `markSold`, jadi progres tersimpan harus dibuang
        // di sini juga — kalau tidak, key akan tertinggal untuk posisi berikutnya.
        positionStore.clearPaperFsmState(symbol = pair.symbol, isReal = false, exchange = exchange)
        publishPaperExitSnapshot(exchange = exchange, symbol = symbol, snapshot = null)
        return null
    }

    if (currentPrice <= 0.0) {
        publishPaperExitSnapshot(exchange = exchange, symbol = symbol, snapshot = PaperExitSnapshot.of(machine, symbol, exchange, currentPrice))
        return null
    }

    if (!prefs.paperAutoTradeConfig.autoExitEnabled) {
        publishPaperExitSnapshot(exchange = exchange, symbol = symbol, snapshot = PaperExitSnapshot.of(machine, symbol, exchange, currentPrice))
        return null
    }

    val now = System.currentTimeMillis()
    val barMark = PaperAutoTradeRuntime.markBarBucket(exchange, symbol, now)
    val isNewBar = barMark.isNewBar
    // P8 — tick pertama sesudah rehidrasi dievaluasi sebagai bar baru (time-stop langsung dicek)
    // tetapi TIDAK menambah barsHeld: menitnya sudah dihitung mergedBarsHeld.
    val countAsNewBar = PaperFirstBarResolver.countsAsNewBar(
        isNewBar = barMark.isNewBar,
        firstTickAfterRehydrate = barMark.firstTickAfterRehydrate
    )
    // P6 — SATU pembacaan cache M1 untuk dua kebutuhan: jendela momentum dan high intrabar.
    // Dua pembacaan terpisah bisa melihat isi cache yang berbeda pada tick yang sama.
    val m1Candles = agu.analys.util.MtfCacheManager.getCachedCandles(
        symbol = symbol,
        timeframe = agu.analys.model.Timeframe.M1,
        exchange = exchange
    )
    // P6 — jendela momentum dari SATU sumber, untuk kedua pemanggil: cache M1, bukan
    // `recentCandles` milik layar yang sedang terbuka. Bila cache tidak segar, jendela kosong
    // dan engine menahan diri dari early-profit — perlakuan yang sama di jalur latar maupun
    // jalur foreground, jadi alasan exit tidak lagi bergantung pada pair yang dibuka pengguna.
    val window = PaperExitMomentumWindow.resolve(candles = m1Candles, nowMs = now)
    // P6 — high intrabar M1: bar sintetis dari tick saja membuat `peak` tak pernah melihat
    // high M1, sehingga trailing SL jalur latar lebih longgar daripada replay. `low` tetap
    // `currentPrice` supaya SL dieksekusi pada harga tick saat pemeriksaan berjalan.
    val intrabarHigh = PaperIntrabarPeakResolver.effectiveHigh(
        candles = m1Candles,
        openedAtMs = PaperAutoTradeRuntime.openedAt(exchange, symbol),
        nowMs = now,
        currentPrice = currentPrice
    )
    val bar = CandleBar(
        timestamp = now,
        open = currentPrice,
        high = intrabarHigh,
        low = currentPrice,
        close = currentPrice,
        volume = 0.0
    )
    val decision = machine.onBar(
        bar = bar,
        window14 = window,
        isNewBar = isNewBar,
        countAsNewBar = countAsNewBar
    )
    if (decision == null) {
        // Posisi masih ditahan -> simpan progres supaya restart berikutnya tidak kembali ke nol.
        // Tidak perlu `force`: perubahan yang berarti ditulis segera oleh shouldWriteFsm.
        persistPaperFsm(exchange, symbol, machine)
        publishPaperExitSnapshot(exchange = exchange, symbol = symbol, snapshot = PaperExitSnapshot.of(machine, symbol, exchange, currentPrice))
        return null
    }

    val closed = finalizePaperExit(symbol, exchange, machine, decision)
    if (!closed) {
        // SELL gagal: posisi, FSM, penanda engine, dan snapshot harus tetap ada. Menghapusnya
        // di sini membuat posisi menggantung tanpa exit dan tanpa jejak di UI.
        persistPaperFsm(exchange, symbol, machine)
        publishPaperExitSnapshot(
            exchange = exchange,
            symbol = symbol,
            snapshot = PaperExitSnapshot.of(machine, symbol, exchange, currentPrice)
        )
        return null
    }
    publishPaperExitSnapshot(exchange = exchange, symbol = symbol, snapshot = null)
    return decision
}

/**
 * Tutup posisi paper: kirim SELL simulasi, catat jurnal, lalu lepas state machine.
 *
 * Hanya menyentuh akun simulasi (`isReal = false`).
 */
fun TradingViewModel.finalizePaperExit(
    symbol: String,
    exchange: String,
    machine: PaperExitStateMachine,
    decision: PaperExitDecision
): Boolean {
    val pair = TradingPair.fromCustomSymbol(raw = symbol, exchange = exchange)
    val posQty = positionStore.get(pair.symbol, isReal = false, exchange = exchange).quantity
    val heldQty = if (posQty > 0.0) posQty else simCoordinator.wallet.value.getAvailableCoin(pair.baseAsset)

    val triggerType = when (decision.reason) {
        PaperExitReason.SL -> "STOP_LOSS"
        PaperExitReason.BE_SL -> "BE_STOP"
        PaperExitReason.TRAIL_SL -> "TRAILING"
        PaperExitReason.TP2 -> "TP2"
        PaperExitReason.EARLY_PROFIT -> "EARLY_PROFIT"
        PaperExitReason.TIMEOUT -> "TIME_STOP"
        PaperExitReason.BAD_RISK -> "BAD_RISK"
    }

    val exitPrice = if (decision.exitPrice > 0.0) decision.exitPrice else machine.entry

    val dispatched = heldQty > 0.0 && executeAutoSellOrder(
        symbol = pair.symbol,
        price = exitPrice,
        quantity = heldQty,
        triggerType = triggerType,
        isReal = false,
        isPartial = false,
        exchange = exchange
    )

    // Catat ke jurnal hanya bila exit benar-benar tereksekusi di akun simulasi.
    if (dispatched) {
        val now = System.currentTimeMillis()
        appendPaperTradeRecord(
            PaperTradeRecord(
                id = now,
                symbol = pair.symbol,
                exchange = exchange.uppercase(),
                mode = machine.params.mode,
                reason = decision.reason,
                result = decision.result,
                entry = machine.entry,
                exitPrice = exitPrice,
                stopLoss = machine.stopLoss,
                tp1 = machine.tp1,
                tp2 = machine.tp2,
                r = decision.r,
                ambiguous = decision.ambiguous,
                barsHeld = machine.barsHeld,
                openedAt = PaperAutoTradeRuntime.openedAt(exchange, symbol),
                closedAt = now
            )
        )

        AppLogManager.trade(
            "PaperAutoExit",
            "🏁 [${pair.symbol}] Paper exit ${decision.reason.displayLabel()} " +
                "(${machine.params.mode.label}) | R net ${String.format(java.util.Locale.US, "%.2f", decision.r)} " +
                "@ ${PriceFormatter.formatPrice(exitPrice, quoteAsset = pair.quoteAsset)}" +
                if (decision.ambiguous) " | ⚠️ bar ambigu (SL & TP2 satu bar)" else ""
        )
    } else {
        AppLogManager.trade(
            "PaperAutoExit",
            "❌ [${pair.symbol}] Sinyal exit ${decision.reason.displayLabel()} GAGAL dieksekusi; " +
                "posisi dan FSM dipertahankan untuk dicoba lagi (qty tersedia $heldQty)."
        )
        // Jangan lepas mesin/FSM. `evaluatePaperAutoExitLocked` akan mempertahankan snapshot dan
        // mencoba lagi pada tick berikutnya selama kondisinya masih berlaku.
        return false
    }

    PaperAutoTradeRuntime.clear(exchange, symbol)
    positionStore.setPaperEngineOwned(symbol = pair.symbol, isReal = false, exchange = exchange, owned = false)
    // Progres FSM sudah tidak berlaku setelah posisi ditutup. `markSold` juga menghapus key ini,
    // tetapi jalur ini harus tetap benar walau `markSold` tidak sempat jalan.
    positionStore.clearPaperFsmState(symbol = pair.symbol, isReal = false, exchange = exchange)
    positionCoordinator.refreshPosition(pair.symbol)
    return true
}

/** Ringkas parameter exit yang sedang aktif, untuk ditampilkan di UI. */
fun TradingViewModel.currentExitParams(): ScalpExitParams = prefs.scalpingRuntimeConfig.exit

/**
 * Potret posisi paper untuk **pair tertentu**.
 *
 * [TradingViewModel.paperExitSnapshot] sengaja tidak dipakai di halaman detail: StateFlow itu
 * di-`publish` dari tick pair mana pun (`MarketDataCoordinator.onPriceUpdate` memanggil
 * [evaluatePaperAutoExit] untuk semua simbol yang diperbarui), sehingga isinya bisa milik pair
 * lain. Kartu detail harus selalu membaca posisi pair yang sedang dibuka.
 *
 * @return `null` bila pair ini tidak punya posisi simulasi terbuka.
 */
fun TradingViewModel.paperSnapshotFor(
    exchange: String,
    symbol: String,
    currentPrice: Double
): PaperExitSnapshot? {
    val machine = PaperAutoTradeRuntime.machineFor(exchange, symbol) ?: return null
    return PaperExitSnapshot.of(machine, symbol, exchange, currentPrice)
}

/**
 * Bangun ulang state machine exit paper setelah app restart.
 *
 * ## Masalah yang ditutup
 *
 * [PaperAutoTradeRuntime] menyimpan state machine **hanya di memori**, sedangkan posisinya
 * persisten di `SpotPositionStore`, dan `auto_sell_enabled` sudah dimatikan saat engine
 * mengambil alih exit. Setelah app di-kill, tidak ada mesin yang menjual posisi itu:
 * engine paper kosong, jalur auto-sell lama mati, posisi menggantung.
 *
 * ## Cara kerja
 *
 * Penanda persisten `_paper_engine` (ditulis [tryPaperAutoBuy]) dipakai untuk mengenali
 * posisi yang exit-nya memang dipegang engine paper. Untuk setiap posisi:
 * - [PaperRehydrateAction.Restore] → mesin dibangun ulang, `openedAt` asli dikembalikan;
 * - [PaperRehydrateAction.HandBackToLegacy] → `auto_sell_enabled` dinyalakan kembali dan
 *   penanda dilepas, supaya jalur auto-sell lama yang menjualnya;
 * - [PaperRehydrateAction.Skip] → dilewati.
 *
 * Posisi lama yang **belum** punya penanda (dibeli sebelum perbaikan ini) sengaja tidak
 * disentuh: SL-nya tersimpan sebagai `entry * 0.99` yang salah, jadi memulihkannya akan
 * memalsukan stop-out.
 *
 * ## Cakupan bursa (P1)
 *
 * Rehidrasi mengiterasi **semua** bursa yang punya posisi paper tersimpan, bukan hanya bursa yang
 * sedang aktif. Bursa aktif didahulukan supaya posisi yang sedang dilihat pengguna pulih lebih dulu.
 * Urutannya ditentukan [PaperRehydrateScope] (fungsi murni, bisa diuji).
 *
 * Mesin untuk bursa non-aktif memang ikut dibangun, tetapi **baru bisa dievaluasi saat bursa itu
 * dipilih**: aplikasi hanya punya umpan harga untuk bursa yang sedang dipakai. Keadaan itu dicatat
 * eksplisit di log supaya posisi yang "diam" tidak terlihat seperti bug tanpa sebab.
 *
 * ## Progres FSM (P4.4)
 *
 * Selain parameter posisi, rehidrasi kini membaca [PaperExitFsmState] tersimpan (`_paper_fsm`)
 * sehingga `peak`, SL aktif, status BE/trailing/partial, `barsHeld`, dan `atrPct` tidak kembali
 * ke nol. Bila state itu tidak lolos validasi, mesin tetap dibangun dengan `entry`/`SL` asli dan
 * hanya progresnya yang dibuang — dicatat sebagai peringatan, bukan kegagalan senyap.
 *
 * @return jumlah mesin yang berhasil dipulihkan.
 */
fun TradingViewModel.rehydratePaperAutoExit(): Int {
    val cfg = prefs.paperAutoTradeConfig

    // P1 — rehidrasi WAJIB mencakup semua bursa yang punya posisi paper, bukan hanya bursa aktif.
    // Membaca `prefs.marketDataSource.name` saja membuat posisi di bursa lain kehilangan mesin
    // exitnya selamanya setelah restart. Urutannya ditentukan resolver murni [PaperRehydrateScope]
    // (bursa aktif lebih dulu) supaya keputusan ini bisa diuji tanpa ViewModel.
    val stored = positionStore.getPaperEnginePositions(isReal = false)
    val scope = PaperRehydrateScope.decide(
        storedExchanges = stored.map { it.exchange },
        activeExchange = prefs.marketDataSource.name
    )
    if (scope.isEmpty()) return 0

    val targets = scope.flatMap { ex ->
        stored.filter { it.exchange.equals(ex, ignoreCase = true) }.map { ex to it.symbol }
    }
    if (targets.isEmpty()) return 0

    val runtime = prefs.scalpingRuntimeConfig
    val now = System.currentTimeMillis()
    var restored = 0
    var handedBack = 0
    var progressRestored = 0

    for ((exchange, raw) in targets) {
        // Jangan timpa mesin yang sudah hidup (mis. auto-buy terjadi sebelum rehidrasi jalan).
        if (PaperAutoTradeRuntime.machineFor(exchange, raw) != null) continue

        val pair = TradingPair.fromCustomSymbol(raw = raw, exchange = exchange)
        val pos = positionStore.get(pair.symbol, isReal = false, exchange = exchange)
        val fees = prefs.feeConfigFor(pair.symbol)

        val action = PaperRehydrate.decide(
            entry = pos.entryPrice,
            stopLoss = pos.stopLossPrice,
            tp1 = pos.tp1Price,
            tp2 = pos.tp2Price,
            autoExitEnabled = cfg.autoExitEnabled,
            buyFeePct = fees.buyTakerPct,
            sellFeePct = fees.sellTakerPct,
            slippagePct = runtime.slippagePct,
            params = runtime.exit,
            storedFsm = PaperExitFsmState.fromJson(
                positionStore.getPaperFsmState(symbol = pair.symbol, isReal = false, exchange = exchange)
            ),
            openedAt = pos.openedAt,
            now = now
        )

        when (action) {
            is PaperRehydrateAction.Restore -> {
                PaperAutoTradeRuntime.put(
                    exchange,
                    pair.symbol,
                    action.machine,
                    openedAt = pos.openedAt,
                    // Time-stop harus bisa jatuh pada tick pertama bila jam tahan sudah lewat
                    // selama app tertutup (lihat PaperRehydrate.mergedBarsHeld).
                    markFirstTickAsNewBar = true
                )
                PaperAutoTradeRuntime.seedPersistedFsm(exchange, pair.symbol, action.machine.snapshot())
                restored++
                if (action.progressRestored) {
                    progressRestored++
                    AppLogManager.service(
                        "PaperAutoExit",
                        "♻️ [${pair.symbol}] Progres FSM dipulihkan: ${action.machine.phase.label}, " +
                            "SL ${PriceFormatter.formatPrice(action.machine.activeStopLoss, quoteAsset = pair.quoteAsset)}, " +
                            "${action.machine.barsHeld} bar."
                    )
                }
                if (action.warning != null) {
                    AppLogManager.service(
                        "PaperAutoExit",
                        "⚠️ [${pair.symbol}] Progres FSM direset (${action.warning}) — " +
                            "entry/SL asli dipertahankan, mesin mulai dari kondisi awal."
                    )
                }
            }

            is PaperRehydrateAction.HandBackToLegacy -> {
                // P3 — jangan pernah menyalakan auto-sell dengan SL yang **tidak valid** (SL ≥ entry).
                //
                // Dulu cabang ini menulis SL tersimpan apa adanya lalu menyalakan auto-sell. Bila
                // rata-rata DCA sudah turun di bawah SL lama, `stopLossPrice >= entryPrice` — dan pada
                // tick berikutnya jalur lama menjual SELURUH posisi di harga pasar, dilabeli
                // `STOP_LOSS` di jurnal, padahal tidak ada stop yang tersentuh.
                //
                // Sekarang SL diambil dari resolver yang sama dengan jalur pasang mesin: yang lebih
                // lebar antara SL tersimpan dan lantai friksi. Bila lantai friksi nonaktif dan SL
                // tersimpan tidak valid, auto-sell sengaja **dibiarkan mati** — posisi tanpa stop jauh
                // lebih baik daripada penjualan pasar yang salah label.
                val posFees = prefs.feeConfigFor(pair.symbol)
                val resolvedHandBack = PaperStopResolver.resolve(
                    signalSl = pos.stopLossPrice,
                    entry = pos.entryPrice,
                    floorPct = PaperStopResolver.frictionFloorPct(
                        buyFeePct = posFees.buyTakerPct,
                        sellFeePct = posFees.sellTakerPct,
                        slippagePct = runtime.slippagePct,
                        minFrictionMultiple = runtime.minFrictionMultiple
                    )
                )
                positionStore.setAutoSellParams(
                    symbol = pair.symbol,
                    enabled = resolvedHandBack.usable,
                    tp1Price = pos.tp1Price,
                    tp1Percent = pos.tp1Percent,
                    tp2Price = pos.tp2Price,
                    tp2Percent = pos.tp2Percent,
                    isReal = false,
                    exchange = exchange,
                    stopLossPrice = resolvedHandBack.price
                )
                positionStore.setPaperEngineOwned(
                    symbol = pair.symbol, isReal = false, exchange = exchange, owned = false
                )
                // Mesin tidak lagi memegang exit, jadi progres tersimpannya tidak akan dipakai.
                positionStore.clearPaperFsmState(symbol = pair.symbol, isReal = false, exchange = exchange)
                handedBack++
                val slNote = when (resolvedHandBack.source) {
                    PaperStopSource.SIGNAL ->
                        "SL ${PriceFormatter.formatPrice(resolvedHandBack.price, quoteAsset = pair.quoteAsset)} dipertahankan."
                    PaperStopSource.FRICTION_FLOOR ->
                        "SL tersimpan ${PriceFormatter.formatPrice(pos.stopLossPrice, quoteAsset = pair.quoteAsset)} " +
                            "tidak valid (harus 0 < SL < entry ${PriceFormatter.formatPrice(pos.entryPrice, quoteAsset = pair.quoteAsset)}) — " +
                            "dipakai SL lantai friksi ${PriceFormatter.formatPrice(resolvedHandBack.price, quoteAsset = pair.quoteAsset)}."
                    PaperStopSource.NONE ->
                        "⚠️ SL tersimpan tidak valid DAN lantai friksi nonaktif — auto-sell TIDAK dinyalakan " +
                            "supaya tidak menjual posisi di harga pasar dengan label STOP_LOSS yang salah. " +
                            "Pasang SL manual atau aktifkan lantai friksi."
                }
                AppLogManager.service(
                    "PaperAutoExit",
                    "↩️ [${pair.symbol}] Rehidrasi dilewati (${action.reason}) — exit diserahkan ke jalur " +
                        "auto-sell lama. $slNote"
                )
            }

            is PaperRehydrateAction.Skip -> {
                positionStore.clearPaperFsmState(symbol = pair.symbol, isReal = false, exchange = exchange)
                AppLogManager.service(
                    "PaperAutoExit",
                    "⏭️ [${pair.symbol}] Rehidrasi dilewati: ${action.reason}."
                )
            }
        }
    }

    // P1 — katakan terus terang bila ada posisi paper di bursa yang sedang tidak aktif. Mesinnya
    // sudah dipulihkan, tetapi harganya belum bisa dievaluasi: aplikasi hanya punya umpan harga
    // untuk bursa yang sedang dipakai. Tanpa catatan ini, pengguna hanya melihat posisi yang
    // "diam" tanpa tahu sebabnya.
    val activeEx = prefs.marketDataSource.name.trim().uppercase()
    val dormant = scope.filter { !it.equals(activeEx, ignoreCase = true) }
    if (dormant.isNotEmpty()) {
        AppLogManager.service(
            "PaperAutoExit",
            "⚠️ Posisi paper terdeteksi di bursa non-aktif (${dormant.joinToString(", ")}): " +
                "mesin exitnya sudah dipulihkan, tetapi baru akan dievaluasi saat bursa itu dipilih " +
                "di Pengaturan — harga hanya tersedia untuk bursa yang sedang aktif."
        )
    }

    if (restored > 0) {
        startTrailingPolling()
        AppLogManager.trade(
            "PaperAutoExit",
            "♻️ Rehidrasi setelah restart: $restored posisi paper dipulihkan" +
                if (progressRestored > 0) " ($progressRestored dengan progres FSM utuh)" else "" +
                if (handedBack > 0) ", $handedBack diserahkan ke jalur lama." else "."
        )
    }
    return restored
}

/**
 * **P9** — serahkan semua posisi paper ke jalur auto-sell lama saat auto-exit dimatikan.
 *
 * ## Masalah yang ditutup
 *
 * Saat engine mengambil alih exit, `auto_sell_enabled` sengaja dimatikan untuk pair itu.
 * Bila pengguna mematikan auto-exit saat posisi masih terbuka, `paperEngineOwnsExit` menjadi
 * `false` sehingga jalur lama **boleh** berjalan — tetapi blok SL/TP-nya masih terkunci
 * `isAutoSellEnabled`, jadi yang tersisa hanya trailing. Posisi menggantung tanpa SL/TP sampai
 * app di-restart, dan setelah restart `PaperRehydrate.HandBackToLegacy` justru menyalakan
 * auto-sell — aksi pengguna yang sama berakhir berbeda tergantung restart.
 *
 * ## Cara kerja
 *
 * Cermin dari cabang [PaperRehydrateAction.HandBackToLegacy] di [rehydratePaperAutoExit],
 * tetapi berjalan saat runtime (tanpa restart) dan memakai [PaperHandBackResolver] supaya SL
 * mesin yang sudah naik ke BE/trailing tidak melebar mundur ke lantai friksi:
 * - `auto_sell_enabled` dinyalakan kembali dengan SL hasil resolver (TP dibiarkan apa adanya),
 * - penanda `_paper_engine` dan progres FSM `_paper_fsm` dibuang,
 * - mesin di memori dilepas.
 *
 * Posisi yang sudah tidak ada / mesin yatim tetap dibersihkan tanpa menyentuh auto-sell.
 *
 * Catatan cakupan: posisi yang diserahkan mengikuti perilaku jalur lama yang berlaku umum —
 * dievaluasi saat pair-nya dibuka, atau di latar bila trailing aktif. Persis seperti keadaan
 * setelah restart; tidak ada pemantauan latar tambahan yang diciptakan di sini.
 *
 * @return jumlah posisi yang benar-benar diserahkan (mesin dilepas dengan auto-sell diisi).
 */
fun TradingViewModel.handBackAllPaperEnginesToLegacy(): Int {
    val runtimeConfig = prefs.scalpingRuntimeConfig
    var handedBack = 0
    var withoutStop = 0

    for ((exchange, rawSymbol) in PaperAutoTradeRuntime.activePositions()) {
        val machine = PaperAutoTradeRuntime.machineFor(exchange, rawSymbol)
        val pair = TradingPair.fromCustomSymbol(raw = rawSymbol, exchange = exchange)
        val pos = positionStore.get(pair.symbol, isReal = false, exchange = exchange)

        if (machine == null || !pos.isHolding) {
            // Mesin yatim / posisi sudah hilang: bersihkan jejak tanpa menyentuh auto-sell.
            PaperAutoTradeRuntime.clear(exchange, rawSymbol)
            positionStore.setPaperEngineOwned(symbol = pair.symbol, isReal = false, exchange = exchange, owned = false)
            positionStore.clearPaperFsmState(symbol = pair.symbol, isReal = false, exchange = exchange)
            continue
        }

        val fees = prefs.feeConfigFor(pair.symbol)
        val stop = PaperHandBackResolver.resolveStopLoss(
            entryPrice = pos.entryPrice,
            storedSl = pos.stopLossPrice,
            activeSl = machine.activeStopLoss,
            bePx = machine.netBreakevenPrice,
            floorPct = PaperStopResolver.frictionFloorPct(
                buyFeePct = fees.buyTakerPct,
                sellFeePct = fees.sellTakerPct,
                slippagePct = runtimeConfig.slippagePct,
                minFrictionMultiple = runtimeConfig.minFrictionMultiple
            )
        )

        positionStore.setAutoSellParams(
            symbol = pair.symbol,
            enabled = stop.usable,
            tp1Price = pos.tp1Price,
            tp1Percent = pos.tp1Percent,
            tp2Price = pos.tp2Price,
            tp2Percent = pos.tp2Percent,
            isReal = false,
            exchange = exchange,
            stopLossPrice = stop.price
        )
        positionStore.setPaperEngineOwned(symbol = pair.symbol, isReal = false, exchange = exchange, owned = false)
        positionStore.clearPaperFsmState(symbol = pair.symbol, isReal = false, exchange = exchange)
        PaperAutoTradeRuntime.clear(exchange, rawSymbol)
        handedBack++
        if (!stop.usable) withoutStop++

        val entryText = PriceFormatter.formatPrice(pos.entryPrice, quoteAsset = pair.quoteAsset)
        val stopText = PriceFormatter.formatPrice(stop.price, quoteAsset = pair.quoteAsset)
        val note = when (stop.source) {
            PaperHandBackSource.PROFIT, PaperHandBackSource.BREAKEVEN ->
                "SL $stopText dari mesin (di atas entry $entryText) dipertahankan — " +
                    if (stop.source == PaperHandBackSource.PROFIT) "profit terkunci." else "breakeven."
            PaperHandBackSource.ACTIVE_STOP ->
                "SL aktif mesin $stopText dipakai."
            PaperHandBackSource.SIGNAL ->
                "SL tersimpan $stopText dipertahankan."
            PaperHandBackSource.FRICTION_FLOOR ->
                "SL tersimpan ${PriceFormatter.formatPrice(pos.stopLossPrice, quoteAsset = pair.quoteAsset)} " +
                    "tidak valid — dipakai lantai friksi $stopText."
            PaperHandBackSource.NONE ->
                "⚠️ Tidak ada SL yang valid — auto-sell TIDAK dinyalakan supaya tidak menjual " +
                    "posisi di harga pasar dengan label STOP_LOSS yang salah. Pasang SL manual " +
                    "atau aktifkan lantai friksi."
        }
        AppLogManager.service(
            "PaperAutoExit",
            "↩️ [${pair.symbol}] Auto-exit dimatikan — exit diserahkan ke jalur auto-sell lama. $note"
        )
    }

    if (handedBack > 0) {
        AppLogManager.service(
            "PaperAutoExit",
            "↩️ Auto-exit paper dimatikan: $handedBack posisi diserahkan ke jalur auto-sell lama" +
                if (withoutStop > 0) " ($withoutStop tanpa SL — pasang manual)." else "."
        )
    }
    checkAndStopTrailingServiceIfEmpty()
    return handedBack
}

/** Label alasan exit untuk notifikasi/jurnal. */
fun PaperExitReason.displayLabel(): String = when (this) {
    PaperExitReason.SL -> "Stop Loss"
    PaperExitReason.BE_SL -> "Breakeven Stop"
    PaperExitReason.TRAIL_SL -> "Trailing Stop"
    PaperExitReason.TP2 -> "Target Profit 2"
    PaperExitReason.EARLY_PROFIT -> "Early Profit"
    PaperExitReason.TIMEOUT -> "Timeout"
    PaperExitReason.BAD_RISK -> "Risiko tidak valid"
}
