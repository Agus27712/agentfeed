package agu.analys.engine.scalping

/**
 * Threshold terpusat engine scalping KriptoYoi (P2).
 *
 * Semua nilai berbasis **persen / ratio** (exchange-agnostic: pair IDR & USDT/IDR Tokocrypto).
 * Engine lain (Setup / Score / Risk / Regime) boleh baca dari sini agar tidak hardcode tersebar.
 *
 * Bobot score & kategori tetap di [SignalScoringEngine] / [ScoreBreakdown]; file ini
 * mengumpulkan **batas keputusan** (RVOL, ADX, CHOP, R:R, spread, dsb).
 */
object ScalpingConfig {

    // --- Score gate (mapping ke LONG) ---
    /** Skor minimum agar step4 / arah LONG dipertimbangkan (setup non-retest). */
    const val MIN_SCORE_LONG = 60
    /** Skor minimum untuk stage STRONG_ENTRY. */
    const val MIN_SCORE_STRONG = 75
    /**
     * Skor minimum khusus [BREAKOUT_RETEST].
     * Replay Tokocrypto: retest ~80% trade dengan avg skor ~67 tapi expectancy jelek.
     * Gate lebih tinggi dari MIN_SCORE_LONG agar hanya retest konfluensi kuat yang lolos.
     */
    const val MIN_SCORE_BREAKOUT_RETEST = 75

    // --- Risk / R:R ---
    const val MIN_NET_RR = 1.15
    /**
     * Target net R:R default.
     *
     * Dinaikkan 1.25 → **2.0** (2026-10-12) berdasar uji data riil 30 hari, 40 pair Tokocrypto:
     * dengan target 1.25 win-rate minimum agar impas adalah `w* = 1/(1+1.25) ≈ 44%`, sedangkan
     * engine hanya mencapai ~22–34%. Menaikkan target menurunkan `w*` dan, digabung lantai risiko
     * berbasis friksi ([MIN_FRICTION_MULTIPLE]), mengubah avg R dari negatif menjadi sedikit positif.
     */
    const val TARGET_NET_RR = 2.0
    /**
     * Lantai risiko **absolut** (persen harga) — cadangan bila friksi sangat kecil.
     *
     * Tetap 0,5 karena penjaga utama adalah [MIN_FRICTION_MULTIPLE] yang adaptif per bursa.
     * Uji 2026-10-12: lantai absolut 3,0% dikombinasikan dengan penjaga relatif memberi hasil
     * **lebih buruk** di pair IDR berbiaya rendah (penjaga relatif jadi tidak berperan).
     */
    const val MIN_RISK_PCT = 0.5
    /**
     * S3 — **batas keras jarak stop loss** sebagai kelipatan ATR.
     *
     * Ini lantai keselamatan sisi lain: [MIN_FRICTION_MULTIPLE] memaksa SL cukup lebar agar
     * satu kali stop tidak menelan banyak R, sedangkan konstanta ini memastikan SL tidak
     * pernah lebih jauh dari N× ATR. Untuk AMP (ATR 2,03%) nilainya ≈ 4% — cukup untuk
     * menahan noise normal, tapi tetap ada lantai kalau harga lanjut turun.
     *
     * PENTING: bila lantai friksi menghasilkan jarak **lebih lebar** dari batas ini,
     * lantai friksi yang menang (lihat `ScalpingRiskEngine`: `maxRiskAbs` dinaikkan ke
     * `minRiskAbs`). Jadi batas ini tidak pernah mempersempit SL di bawah lantai friksi.
     */
    const val MAX_RISK_ATR = 2.0
    const val MAX_TP2_R = 5.0
    const val DEFAULT_SLIPPAGE_PCT = 0.08
    /**
     * **Lantai risiko berbasis friksi** — kelipatan minimum jarak stop loss terhadap biaya
     * round-trip (fee all-in + 2× slippage).
     *
     * Alasan (uji 2026-10-12): dengan `minRiskPct` 0,5% dan friksi Tokocrypto USDT 0,9688%,
     * satu kali kena SL kehilangan `0,5% + 0,97% = 1,47%` ≈ **2,9R** — unit risiko kalah besar
     * dari biaya. Baseline 30 hari × 28 pair: **0 dari 28 pair** punya avg R positif (avg R −1,22).
     *
     * Nilai **6,0** dipilih dari uji sweep 2026-10-12 (30 hari, 40 pair Tokocrypto):
     * terdapat **plateau lebar 5–10×**, dengan 6× memberi hasil terbaik untuk pair USDT
     * (avg R +0,0695, **6 dari 6 pair positif**) dan masih baik untuk IDR maker (+0,0430).
     * Di bawah 5× hasilnya belum konsisten positif; di atas 10× jumlah trade menyusut
     * tanpa perbaikan berarti. Lihat `AUDIT-2026-11-08.md` §9.
     *
     * Efek lantai: USDT 0,9688% → ≈5,81% · IDR taker 0,8144% → ≈4,89% · IDR maker 0,6144% → ≈3,69%.
     */
    const val MIN_FRICTION_MULTIPLE = 6.0

    // --- Extended move (S9) ---
    /**
     * S9 — gerbang *extended move*: koin yang sudah naik ≥ nilai ini (%) dalam 24 jam
     * ditahan dari sinyal BUY.
     *
     * Kasus nyata (AMP/USDT): masuk saat koin sudah **+28,09%** dalam 24 jam, yaitu tepat di
     * dekat puncak harian, dengan MACD mendatar dan volume mengering — lalu harga turun
     * −2,76% selama 9 menit sebelum berbalik. Membeli *pullback* di ekor kenaikan ekstrem
     * bukan breakout segar; itu mengejar sisa gerakan yang sebagian besar sudah terjadi.
     *
     * **Default 0 = gerbang NONAKTIF**, dan ini disengaja. Bukti replay GNS/USDT
     * (2000 bar M1, 2026-10): dengan ambang 25% sinyal ready turun 20 → 10 dan seluruh
     * trade yang tersisa **tidak pernah resolve** — padahal tanpa gerbang trade-trade itu
     * **4/4 menang, avg R +0,178, tanpa drawdown**. Artinya ambang 25% memotong justru
     * fase tren yang sehat. Satu pair bukan dasar untuk mengubah perilaku entry, maka
     * gerbang diserahkan ke pengguna sampai ada walk-forward multi-pair (lihat P4.1).
     *
     * Dapat diubah dari Pengaturan (`max_change_24h_pct`).
     */
    const val MAX_CHANGE_24H_PCT = 0.0

    // --- Volume (RVOL) ---
    const val RVOL_LOW = 0.7
    const val RVOL_NORMAL = 1.0
    const val RVOL_HIGH = 1.5
    const val RVOL_EXTREME = 2.5
    /** Minimum RVOL untuk konfirmasi breakout. */
    const val RVOL_BREAKOUT_MIN = 1.5
    /** Minimum RVOL untuk sweep / pullback recovery. */
    const val RVOL_SETUP_MIN = 1.2
    /** Minimum RVOL untuk BREAKOUT_RETEST (ketat — replay: retest longgar = −EV). */
    const val RVOL_RETEST_MIN = 1.5

    // --- Order flow ---
    /** Buy pressure (bid/ask volume ratio) minimum breakout. */
    const val BUY_PRESSURE_BREAKOUT = 1.15
    const val BUY_PRESSURE_SUPPORTIVE = 1.05
    const val BUY_PRESSURE_STRONG = 1.25
    /** Buy pressure minimum saat orderbook ada, untuk retest. */
    const val BUY_PRESSURE_RETEST = 1.15
    /** Order imbalance (bid-ask)/(bid+ask), range -1..+1. */
    const val IMBALANCE_MILD = 0.10
    const val IMBALANCE_STRONG = 0.28

    // --- Regime (CHOP / ADX / ATR%) ---
    const val CHOP_RANGING = 61.8
    /** CHOP di atas ini = ranging keras (hindari trend-follow murni). */
    const val CHOP_HARD_RANGING = 65.0
    /**
     * P0 — batas CHOP untuk regime trending.
     *
     * p0 memakai `adx >= 25 AND chop < 55`. Tanpa batas atas CHOP, ADX tinggi di pasar
     * yang tetap berputar-putar tetap dilabeli trending. Nilai ini **bukan** pengganti
     * [CHOP_RANGING]: yang pertama menentukan tren, yang kedua menentukan ranging.
     */
    const val CHOP_TREND_MAX = 55.0
    const val ADX_TRENDING = 25.0
    const val ADX_WEAK = 20.0
    const val ATR_PCT_HIGH = 3.5
    const val ATR_PCT_LOW = 0.8
    const val ATR_PCT_SCALP_SWEET_LOW = 0.3
    const val ATR_PCT_SCALP_SWEET_HIGH = 1.5

    // --- P0 KriptoYoi: TTL, MTF, BTC gatekeeper ---
    /**
     * P0 — umur sinyal scalping dalam candle M1 (p0: 5 candle = 5 menit).
     *
     * Diukur dari jam dinding, bukan jumlah tick: tick bisa datang 5× per menit atau
     * tertinggal saat app tidur. Setelah batas ini sinyal menjadi `EXPIRED` dan **setup
     * yang sama tidak boleh langsung di-arm ulang** (lihat `SignalTtl`).
     */
    const val TTL_BARS_M1 = 5
    /** P0 — jumlah TF bullish minimum dari matriks H1/M15/M5/M1. */
    const val MTF_MIN_BULLISH = 3
    /** P0 — H1 dan M15 tidak boleh bearish saat mencari LONG. */
    const val MTF_BLOCK_HIGH_TF_BEARISH = true
    /** P0 — BTC gatekeeper aktif secara default (dapat dimatikan lewat config). */
    const val BTC_VETO_ENABLED = true
    /** P0 — veto bila return BTC 5M terburuk dari dua candle terakhir < nilai ini (%). */
    const val BTC_RETURN_VETO_PCT = -0.6
    /** P0 — veto bila ATR ratio BTC > nilai ini dan 2 dari 3 candle terakhir bearish. */
    const val BTC_ATR_RATIO_VETO = 2.5
    /** P0 — periode EMA BTC pada candle 5M. */
    const val BTC_EMA_PERIOD = 20

    /**
     * S4 — apakah ATR% berada di band yang ideal untuk scalping.
     *
     * Di bawah [ATR_PCT_SCALP_SWEET_LOW] pergerakan terlalu tipis untuk menutup friksi;
     * di atas [ATR_PCT_SCALP_SWEET_HIGH] stop rapat mudah tersapu wick (kasus AMP: ATR 2,03%).
     * Konstanta ini sebelumnya dideklarasikan tetapi tidak pernah dipakai.
     */
    fun isAtrInScalpSweetBand(atrPct: Double): Boolean =
        atrPct >= ATR_PCT_SCALP_SWEET_LOW && atrPct <= ATR_PCT_SCALP_SWEET_HIGH

    /** Label singkat posisi ATR% relatif terhadap band scalp ideal. */
    fun atrBandLabel(atrPct: Double): String = when {
        atrPct <= 0.0 -> "tidak diketahui"
        atrPct < ATR_PCT_SCALP_SWEET_LOW -> "terlalu tenang"
        atrPct > ATR_PCT_SCALP_SWEET_HIGH -> "terlalu liar"
        else -> "ideal"
    }

    // --- Structure / setup jarak (%) ---
    /** Jarak maksimum ke level untuk dianggap "retest". */
    const val RETEST_MAX_DIST_PCT = 1.2
    /** Harga masih di atas level breakout (toleransi kecil). */
    const val BREAKOUT_ABOVE_FACTOR = 0.998
    /** Faktor minimum harga di atas level retest (0.995 = -0.5%). */
    const val RETEST_ABOVE_FACTOR = 0.995
    /** Kekuatan struktur minimum untuk retest heuristik. */
    const val RETEST_MIN_STRUCTURE_STRENGTH = 60
    const val PULLBACK_SUPPORT_MAX_DIST_PCT = 1.2

    // --- Order book / spread (selaras OrderBookAnalyzer defaults) ---
    const val SPREAD_TOLERANCE_PCT = 0.40
    const val SPREAD_GUARD_MAX_PCT = 1.20
    const val ORDERBOOK_STALE_MS = 30_000L

    // --- MTF ---
    /** Timeframe yang dipakai confluence scalping (kode internal). */
    val MTF_KEYS_DEFAULT: List<String> = listOf("1D", "4H", "1H", "15M", "5M", "1M")

    // --- Historical edge stub ---
    const val HISTORICAL_EDGE_INSUFFICIENT =
        "Belum cukup data historis untuk Historical Edge. Jangan menampilkan probabilitas palsu."

    /** Skor minimum step4 tergantung setup. */
    fun minScoreForSetup(setupName: String): Int = when (setupName) {
        "BREAKOUT_RETEST" -> MIN_SCORE_BREAKOUT_RETEST
        else -> MIN_SCORE_LONG
    }
}
