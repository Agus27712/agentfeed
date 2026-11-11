package agu.analys.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.ui.animation.PriceAnimationMode
import agu.analys.ui.theme.AccentColorPreset
import agu.analys.ui.theme.AnimationSpeed
import agu.analys.ui.theme.CandleColorStyle
import agu.analys.ui.theme.ThemeStyle
import org.json.JSONObject

/** Local user configuration. Runtime market data is deliberately kept elsewhere. */
class AppPreferences(context: Context) {
    /** Penyimpanan token bridge — terpisah, tidak ikut pemulihan KeyStore prefs utama. */
    private val bridgePrefs: android.content.SharedPreferences =
        context.getSharedPreferences(PREFS_BRIDGE_NAME, Context.MODE_PRIVATE)

    private val prefs: android.content.SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Handle corrupt KeyStore / invalid key after app upgrade gracefully
        timber.log.Timber.w(e, "EncryptedSharedPreferences failed, attempting keystore recovery")
        try {
            context.deleteSharedPreferences(PREFS_NAME)
            // Hapus MasterKey corrupt dari AndroidKeyStore agar retry tidak gagal dengan key yang sama.
            try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            } catch (delErr: Exception) {
                timber.log.Timber.w(delErr, "Gagal hapus MasterKey corrupt dari AndroidKeyStore")
            }
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Fallback terakhir ke plaintext: fungsional tapi TIDAK aman untuk API key/secret.
            //
            // Penanda `isKeyStoreRecoveryOccurred` HANYA di-set di sini (bukan saat recovery
            // berhasil) supaya artinya tepat: "kredensial sedang tersimpan tanpa enkripsi".
            // UI memakai penanda ini untuk memperingatkan pengguna dan memblokir trading riil.
            timber.log.Timber.e("EncryptedSharedPreferences recovery gagal total — fallback ke plaintext (kredensial tidak terenkripsi!)")
            isKeyStoreRecoveryOccurred = true
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    var strategyMode: StrategyMode
        get() = runCatching {
            val name = prefs.getString(KEY_STRATEGY_MODE, null)
            if (name != null) StrategyMode.valueOf(name)
            else StrategyMode.SWING
        }.getOrDefault(StrategyMode.SWING)
        set(value) {
            prefs.edit().putString(KEY_STRATEGY_MODE, value.name).apply()
            isScalpingMode = (value == StrategyMode.SCALPING)
        }

    var isRealBuyModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_REAL_BUY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_REAL_BUY_MODE, value).apply()

    var securityPinHash: String
        get() = prefs.getString(KEY_SECURITY_PIN_HASH, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SECURITY_PIN_HASH, value).apply()

    var failedPinAttempts: Int
        get() = prefs.getInt(KEY_FAILED_PIN_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_PIN_ATTEMPTS, value).apply()

    fun recordFailedPinAttempt(): Int {
        val next = failedPinAttempts + 1
        failedPinAttempts = next
        return next
    }

    var isPinResetRequired: Boolean
        get() = prefs.getBoolean(KEY_PIN_RESET_REQUIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_PIN_RESET_REQUIRED, value).apply()

    fun resetFailedPinAttempts() {
        failedPinAttempts = 0
    }

    fun setSecurityPin(pin: String) {
        securityPinHash = hashPin(pin)
        isPinResetRequired = false
        resetFailedPinAttempts()
    }

    fun verifySecurityPin(pin: String): Boolean {
        val current = securityPinHash
        if (current.isBlank()) return false
        return current == hashPin(pin)
    }

    init {
        try {
            val cfg = scalpingRuntimeConfig
            agu.analys.engine.scalping.ScalpingRuntimeConfig.current = cfg
        } catch (_: Exception) {}
        // Mode INTRADAY punya holder sendiri; tanpa hidrasi ini slider intraday "hilang" setiap
        // restart karena evaluator kembali membaca IntradayConfig.DEFAULT.
        try {
            val intradayCfg = intradayRuntimeConfig
            agu.analys.engine.intraday.IntradayRuntimeConfig.current = intradayCfg
        } catch (_: Exception) {}
        // Mode SWING punya holder sendiri; tanpa hidrasi ini slider swing "hilang" setiap restart
        // karena evaluator kembali membaca SwingConfig.DEFAULT.
        try {
            val swingCfg = swingRuntimeConfig
            agu.analys.engine.swing.SwingRuntimeConfig.current = swingCfg
        } catch (_: Exception) {}
    }

    fun hasSecurityPin(): Boolean = securityPinHash.isNotBlank()

    fun hasTokocryptoCredentials(): Boolean = tokocryptoApiKey.isNotBlank() && tokocryptoSecretKey.isNotBlank()

    fun wipeAllRealSecurityData() {
        prefs.edit()
            .remove(KEY_SECURITY_PIN_HASH)
            .remove(KEY_TOKOCRYPTO_API_KEY)
            .remove(KEY_TOKOCRYPTO_SECRET_KEY)
            .remove(KEY_RECENT_HISTORY_BASES)
            .remove(KEY_PIN_RESET_REQUIRED)
            .putBoolean(KEY_REAL_BUY_MODE, false)
            .putInt(KEY_FAILED_PIN_ATTEMPTS, 0)
            .apply()
    }

    var isRealBuyMode: Boolean
        get() = prefs.getBoolean(KEY_REAL_BUY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_REAL_BUY_MODE, value).apply()

    var tokocryptoApiKey: String
        get() = prefs.getString(KEY_TOKOCRYPTO_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKOCRYPTO_API_KEY, value.trim()).apply()

    var tokocryptoSecretKey: String
        get() = prefs.getString(KEY_TOKOCRYPTO_SECRET_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKOCRYPTO_SECRET_KEY, value.trim()).apply()

    /**
     * Base asset (btc, sol, xrp, …) yang pernah punya saldo / di-trade.
     * Dipakai biar histori tetap di-fetch setelah koin dijual habis.
     */
    fun getRecentHistoryBases(): List<String> =
        prefs.getString(KEY_RECENT_HISTORY_BASES, "")
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != "idr" && it != "usdt" }
            .distinct()

    fun rememberHistoryBases(bases: Collection<String>) {
        if (bases.isEmpty()) return
        val merged = (bases.map { it.lowercase().trim() }.filter {
            it.isNotBlank() && it != "idr" && it != "usdt"
        } + getRecentHistoryBases()).distinct().take(12)
        prefs.edit().putString(KEY_RECENT_HISTORY_BASES, merged.joinToString(",")).apply()
    }

    fun rememberHistoryBase(base: String) {
        rememberHistoryBases(listOf(base))
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "").orEmpty().ifEmpty { agu.analys.BuildConfig.GEMINI_API_KEY }
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    var aiProvider: AiProvider
        get() = runCatching { AiProvider.valueOf(prefs.getString(KEY_AI_PROVIDER, AiProvider.GEMINI.name).orEmpty()) }.getOrDefault(AiProvider.GEMINI)
        set(value) = prefs.edit().putString(KEY_AI_PROVIDER, value.name).apply()

    var marketDataSource: MarketDataSource
        get() = runCatching { MarketDataSource.valueOf(prefs.getString(KEY_MARKET_SOURCE, MarketDataSource.TOKOCRYPTO.name).orEmpty()) }.getOrDefault(MarketDataSource.TOKOCRYPTO)
        set(value) = prefs.edit().putString(KEY_MARKET_SOURCE, value.name).apply()

    var isScalpingMode: Boolean
        get() = prefs.getBoolean(KEY_SCALPING_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCALPING_MODE, value).apply()

    /** True bila pengguna sudah menyimpan biaya sendiri (menimpa default bursa). */
    val hasCustomTradingFees: Boolean
        get() = prefs.getBoolean(KEY_FEES_CUSTOM, false)

    /**
     * Biaya trading yang dipakai mesin.
     *
     * Bila pengguna belum pernah mengubahnya, nilai diambil dari **default bursa yang sedang
     * aktif** ([MarketDataSource.defaultFeeConfig]).
     *
     * Sebelum perbaikan ini, getter selalu memakai satu preset lama (0,21%/0,42%) untuk **semua**
     * bursa — sehingga di Tokocrypto USDT mesin menghitung biaya kurang dari separuh nilai
     * sebenarnya (0,4044%/sisi), dan setiap Net R:R / gate RR / perkiraan untung jadi terlalu
     * optimistis.
     */
    var tradingFees: TradingFeeConfig
        get() {
            val fallback = marketDataSource.defaultFeeConfig
            if (!hasCustomTradingFees) return fallback
            return TradingFeeConfig(
                buyMakerPct = prefs.getString(KEY_BUY_MAKER, null)?.toDoubleOrNull() ?: fallback.buyMakerPct,
                buyTakerPct = prefs.getString(KEY_BUY_TAKER, null)?.toDoubleOrNull() ?: fallback.buyTakerPct,
                sellMakerPct = prefs.getString(KEY_SELL_MAKER, null)?.toDoubleOrNull() ?: fallback.sellMakerPct,
                sellTakerPct = prefs.getString(KEY_SELL_TAKER, null)?.toDoubleOrNull() ?: fallback.sellTakerPct
            )
        }
        set(value) = prefs.edit()
            .putString(KEY_BUY_MAKER, value.buyMakerPct.toString())
            .putString(KEY_BUY_TAKER, value.buyTakerPct.toString())
            .putString(KEY_SELL_MAKER, value.sellMakerPct.toString())
            .putString(KEY_SELL_TAKER, value.sellTakerPct.toString())
            .putBoolean(KEY_FEES_CUSTOM, true)
            .apply()

    /**
     * Biaya yang **tepat** untuk satu simbol pada bursa aktif.
     *
     * Berbeda dari [tradingFees] yang hanya satu nilai global, fungsi ini memilih tarif menurut
     * kuotasi pair (USDT vs IDR) mengikuti `FEE_TRUTH.md`. Pakai ini bila simbolnya sudah diketahui.
     */
    fun feeConfigFor(symbol: String): TradingFeeConfig =
        feeConfigFor(symbol, marketDataSource.name)

    /**
     * Sama seperti [feeConfigFor], tetapi bursa disebut eksplisit.
     *
     * Dipakai pemantau latar yang memegang posisi dari **kedua** bursa sekaligus; membaca
     * bursa aktif di sana membuat posisi bursa lain dinilai dengan tarif yang salah.
     */
    fun feeConfigFor(symbol: String, exchange: String): TradingFeeConfig {
        if (hasCustomTradingFees) return tradingFees
        val quote = agu.analys.util.PriceFormatter.extractQuote(symbol)
        return agu.analys.config.FeeSchedule.configFor(exchange, quote)
    }

    /** Kembalikan biaya ke default bursa aktif. Dipanggil saat pengguna berganti bursa. */
    fun resetTradingFeesToDefault() {
        prefs.edit()
            .remove(KEY_BUY_MAKER)
            .remove(KEY_BUY_TAKER)
            .remove(KEY_SELL_MAKER)
            .remove(KEY_SELL_TAKER)
            .putBoolean(KEY_FEES_CUSTOM, false)
            .apply()
    }

    var scalpingRuntimeConfig: agu.analys.engine.scalping.ScalpingRuntimeConfig
        get() {
            val jsonStr = prefs.getString(KEY_SCALPING_RUNTIME_CONFIG, null)
            val config = if (!jsonStr.isNullOrBlank()) {
                agu.analys.engine.scalping.ScalpingRuntimeConfig.fromJsonString(jsonStr)
                    ?: agu.analys.engine.scalping.ScalpingRuntimeConfig.DEFAULT
            } else {
                agu.analys.engine.scalping.ScalpingRuntimeConfig.DEFAULT
            }
            agu.analys.engine.scalping.ScalpingRuntimeConfig.current = config
            return config
        }
        set(value) {
            agu.analys.engine.scalping.ScalpingRuntimeConfig.current = value
            prefs.edit().putString(KEY_SCALPING_RUNTIME_CONFIG, value.toJson().toString()).apply()
        }

    var scalpingSensitivityPreset: String
        get() = prefs.getString(KEY_SCALPING_PRESET, "DEFAULT") ?: "DEFAULT"
        set(value) = prefs.edit().putString(KEY_SCALPING_PRESET, value).apply()

    /**
     * Konfigurasi runtime mode INTRADAY (slider). Pola sama dengan [scalpingRuntimeConfig]:
     * persisten sebagai JSON, dihidrasi ke holder `IntradayRuntimeConfig.current` saat dibaca.
     * Bawaan = `IntradayConfig.DEFAULT` supaya angka replay paket A–F tetap reproducible.
     */
    var intradayRuntimeConfig: agu.analys.engine.intraday.IntradayConfig
        get() {
            val jsonStr = prefs.getString(KEY_INTRADAY_RUNTIME_CONFIG, null)
            val config = if (!jsonStr.isNullOrBlank()) {
                agu.analys.engine.intraday.IntradayRuntimeConfig.fromJsonString(jsonStr)
                    ?: agu.analys.engine.intraday.IntradayConfig.DEFAULT
            } else {
                agu.analys.engine.intraday.IntradayConfig.DEFAULT
            }
            agu.analys.engine.intraday.IntradayRuntimeConfig.current = config
            return config
        }
        set(value) {
            agu.analys.engine.intraday.IntradayRuntimeConfig.current = value
            prefs.edit()
                .putString(
                    KEY_INTRADAY_RUNTIME_CONFIG,
                    agu.analys.engine.intraday.IntradayRuntimeConfig.toJsonString(value)
                )
                .apply()
        }

    var intradaySensitivityPreset: String
        get() = prefs.getString(KEY_INTRADAY_PRESET, "STANDARD") ?: "STANDARD"
        set(value) = prefs.edit().putString(KEY_INTRADAY_PRESET, value).apply()

    /**
     * Konfigurasi runtime mode SWING (slider). Pola sama dengan [intradayRuntimeConfig]:
     * persisten sebagai JSON, dihidrasi ke holder `SwingRuntimeConfig.current` saat dibaca.
     * Bawaan = `SwingConfig.DEFAULT` (= literal `SwingEvaluator` sebelum slider ada).
     */
    var swingRuntimeConfig: agu.analys.engine.swing.SwingConfig
        get() {
            val jsonStr = prefs.getString(KEY_SWING_RUNTIME_CONFIG, null)
            val config = if (!jsonStr.isNullOrBlank()) {
                agu.analys.engine.swing.SwingRuntimeConfig.fromJsonString(jsonStr)
                    ?: agu.analys.engine.swing.SwingConfig.DEFAULT
            } else {
                agu.analys.engine.swing.SwingConfig.DEFAULT
            }
            agu.analys.engine.swing.SwingRuntimeConfig.current = config
            return config
        }
        set(value) {
            agu.analys.engine.swing.SwingRuntimeConfig.current = value
            prefs.edit()
                .putString(
                    KEY_SWING_RUNTIME_CONFIG,
                    agu.analys.engine.swing.SwingRuntimeConfig.toJsonString(value)
                )
                .apply()
        }

    var swingSensitivityPreset: String
        get() = prefs.getString(KEY_SWING_PRESET, "STANDARD") ?: "STANDARD"
        set(value) = prefs.edit().putString(KEY_SWING_PRESET, value).apply()

    /**
     * Pengaturan auto-buy / auto-exit **paper trade** (P2).
     *
     * Selalu simulasi — tidak ada jalur ke eksekusi uang riil di sini.
     */
    var paperAutoTradeConfig: agu.analys.engine.scalping.PaperAutoTradeConfig
        get() = agu.analys.engine.scalping.PaperAutoTradeConfig(
            autoBuyEnabled = prefs.getBoolean(KEY_PAPER_AUTO_BUY_ENABLED, false),
            autoExitEnabled = prefs.getBoolean(KEY_PAPER_AUTO_EXIT_ENABLED, true),
            allocation = agu.analys.engine.scalping.PaperAutoBuyAllocation.fromKey(
                prefs.getString(KEY_PAPER_AUTO_BUY_ALLOCATION, null)
            ),
            // Nominal dalam Rupiah. Kunci preferensi lama sengaja dipertahankan supaya nilai
            // yang sudah tersimpan tetap terbaca — yang berubah hanya tafsirnya (Rupiah,
            // bukan kas kuotasi pair).
            fixedIdrAmount = prefs.getString(KEY_PAPER_AUTO_BUY_FIXED_QUOTE, null)
                ?.toDoubleOrNull() ?: 100_000.0,
            riskPctPerTrade = prefs.getString(KEY_PAPER_AUTO_BUY_RISK_PCT, null)
                ?.toDoubleOrNull() ?: 1.0,
            minStage = runCatching {
                agu.analys.model.ScalpingStage.valueOf(
                    prefs.getString(KEY_PAPER_AUTO_BUY_MIN_STAGE, null)
                        ?: agu.analys.model.ScalpingStage.ENTRY.name
                )
            }.getOrDefault(agu.analys.model.ScalpingStage.ENTRY),
            // Batas posisi paper bersamaan (global). `0` = tanpa batas; nilai tak terbaca
            // jatuh ke default aman 3, bukan ke "tanpa batas".
            maxConcurrentPositions = prefs.getString(KEY_PAPER_AUTO_BUY_MAX_POSITIONS, null)
                ?.toIntOrNull() ?: 3,
            // Jeda entri-ulang auto-buy paper (menit). Nilai tak terbaca jatuh ke default 15.
            reentryCooldownMinutes = prefs.getString(KEY_PAPER_AUTO_REENTRY_COOLDOWN_MIN, null)
                ?.toIntOrNull() ?: 15
        )
        set(value) = prefs.edit()
            .putBoolean(KEY_PAPER_AUTO_BUY_ENABLED, value.autoBuyEnabled)
            .putBoolean(KEY_PAPER_AUTO_EXIT_ENABLED, value.autoExitEnabled)
            .putString(KEY_PAPER_AUTO_BUY_ALLOCATION, value.allocation.key)
            .putString(KEY_PAPER_AUTO_BUY_FIXED_QUOTE, value.fixedIdrAmount.toString())
            .putString(KEY_PAPER_AUTO_BUY_RISK_PCT, value.riskPctPerTrade.toString())
            .putString(KEY_PAPER_AUTO_BUY_MIN_STAGE, value.minStage.name)
            .putString(KEY_PAPER_AUTO_BUY_MAX_POSITIONS, value.maxConcurrentPositions.toString())
            .putString(KEY_PAPER_AUTO_REENTRY_COOLDOWN_MIN, value.reentryCooldownMinutes.toString())
            .apply()

    /**
     * Jurnal paper trade (P2) — JSON array berisi [agu.analys.engine.scalping.PaperTradeRecord].
     *
     * Disimpan di prefs terenkripsi supaya kartu statistik tetap akurat setelah app ditutup.
     */
    var paperTradeJournalJson: String
        get() = prefs.getString(KEY_PAPER_TRADE_JOURNAL, null) ?: "[]"
        set(value) = prefs.edit().putString(KEY_PAPER_TRADE_JOURNAL, value).apply()

    var updateRepo: String
        get() = DEFAULT_UPDATE_REPO
        set(_) { /* hardcoded to Agus27712/agentfeed */ }

    var updateGitHubToken: String
        get() = ""
        set(_) { /* hardcoded empty for public repo */ }

    var compactUi: Boolean
        get() = prefs.getBoolean(KEY_COMPACT_UI, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPACT_UI, value).apply()

    var isDarkTheme: Boolean
        get() = prefs.getBoolean("is_dark_theme", true)
        set(value) = prefs.edit().putBoolean("is_dark_theme", value).apply()

    var themeStyle: ThemeStyle
        get() = runCatching {
            ThemeStyle.valueOf(prefs.getString("theme_style_v2", if (isDarkTheme) ThemeStyle.DARK_NAVY.name else ThemeStyle.LIGHT_CLEAN.name).orEmpty())
        }.getOrDefault(if (isDarkTheme) ThemeStyle.DARK_NAVY else ThemeStyle.LIGHT_CLEAN)
        set(value) {
            prefs.edit().putString("theme_style_v2", value.name).apply()
            isDarkTheme = (value != ThemeStyle.LIGHT_CLEAN)
        }

    var accentColorPreset: AccentColorPreset
        get() = runCatching {
            AccentColorPreset.valueOf(prefs.getString("accent_preset_v2", AccentColorPreset.BLUE.name).orEmpty())
        }.getOrDefault(AccentColorPreset.BLUE)
        set(value) = prefs.edit().putString("accent_preset_v2", value.name).apply()

    var candleColorStyle: CandleColorStyle
        get() = runCatching {
            CandleColorStyle.valueOf(prefs.getString("candle_style_v2", CandleColorStyle.CLASSIC.name).orEmpty())
        }.getOrDefault(CandleColorStyle.CLASSIC)
        set(value) = prefs.edit().putString("candle_style_v2", value.name).apply()

    var animationSpeed: AnimationSpeed
        get() = runCatching {
            AnimationSpeed.valueOf(prefs.getString("animation_speed_v2", AnimationSpeed.SMOOTH.name).orEmpty())
        }.getOrDefault(AnimationSpeed.SMOOTH)
        set(value) = prefs.edit().putString("animation_speed_v2", value.name).apply()

    var priceAnimationMode: PriceAnimationMode
        get() = runCatching {
            PriceAnimationMode.valueOf(prefs.getString("price_anim_mode_v2", PriceAnimationMode.DIGIT_FLIP.name).orEmpty())
        }.getOrDefault(PriceAnimationMode.DIGIT_FLIP)
        set(value) = prefs.edit().putString("price_anim_mode_v2", value.name).apply()

    var isPriceTickPulseEnabled: Boolean
        get() = prefs.getBoolean("price_tick_pulse_enabled_v1", true)
        set(value) = prefs.edit().putBoolean("price_tick_pulse_enabled_v1", value).apply()

    var isSmoothChartEnabled: Boolean
        get() = prefs.getBoolean("smooth_chart_enabled_v1", true)
        set(value) = prefs.edit().putBoolean("smooth_chart_enabled_v1", value).apply()

    var isNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()

    var isNotifyCandidateBuyEnabled: Boolean
        get() = prefs.getBoolean("notify_candidate_buy_v1", true)
        set(value) = prefs.edit().putBoolean("notify_candidate_buy_v1", value).apply()

    var isNotifyPriceAlertsEnabled: Boolean
        get() = prefs.getBoolean("notify_price_alerts_v1", true)
        set(value) = prefs.edit().putBoolean("notify_price_alerts_v1", value).apply()

    var isNotifyTrailingStopEnabled: Boolean
        get() = prefs.getBoolean("notify_trailing_stop_v1", true)
        set(value) = prefs.edit().putBoolean("notify_trailing_stop_v1", value).apply()

    var isNotifyEmergencyExitEnabled: Boolean
        get() = prefs.getBoolean("notify_emergency_exit_v1", true)
        set(value) = prefs.edit().putBoolean("notify_emergency_exit_v1", value).apply()

    /**
     * Potret seluruh sakelar notifikasi untuk gerbang [allowsNotification].
     *
     * Dibaca sekali jadi supaya pemanggil tidak perlu tahu nama kelima key-nya, dan supaya
     * menambah kategori baru hanya menyentuh satu tempat.
     */
    val notificationSettings: NotificationSettings
        get() = NotificationSettings(
            masterEnabled = isNotificationsEnabled,
            candidateBuyEnabled = isNotifyCandidateBuyEnabled,
            priceAlertsEnabled = isNotifyPriceAlertsEnabled,
            trailingStopEnabled = isNotifyTrailingStopEnabled,
            emergencyExitEnabled = isNotifyEmergencyExitEnabled
        )

    var isRealSimSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_REAL_SIM_SYNC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_REAL_SIM_SYNC_ENABLED, value).apply()

    var isAgentOutputEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT_OUTPUT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_OUTPUT_ENABLED, value).apply()

    /**
     * Token Agent Bridge (2026-11-06). Endpoint tulis rute `api/v1/trading` menuntut
     * header `X-Agent-Token` dengan nilai ini; berkas token diinstal bersama ekstensi OMP
     * lewat [agu.analys.bridge.OmpBridgeInstaller].
     *
     * Sengaja **tidak** memakai EncryptedSharedPreferences: token ini memungkinkan order
     * pada perangkat sendiri (bukan kredensial bursa), dan stabilitas lintas instans/
     * lintas restart lebih penting daripada enkripsi berlapis — kegagalan AndroidKeyStore
     * (jalur pemulihan) menghapus seluruh isi prefs utama dan token lama "menghilang"
     * begitu saja. Nilainya 32 karakter hex dari SecureRandom 16 byte; bila kosong,
     * server tetap fail-closed (semua request tulis ditolak).
     */
    var agentBridgeToken: String
        get() {
            val existing = bridgePrefs.getString(KEY_AGENT_BRIDGE_TOKEN, null)
            if (!existing.isNullOrBlank()) return existing
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val token = bytes.joinToString("") { "%02x".format(it) }
            bridgePrefs.edit().putString(KEY_AGENT_BRIDGE_TOKEN, token).apply()
            return token
        }
        set(value) = bridgePrefs.edit().putString(KEY_AGENT_BRIDGE_TOKEN, value).apply()

    var priceFeedThrottleMs: Long
        get() = prefs.getLong(KEY_PRICE_FEED_THROTTLE_MS, PriceFeedThrottler.DEFAULT_THROTTLE_MS)
        set(value) = prefs.edit().putLong(KEY_PRICE_FEED_THROTTLE_MS, value.coerceAtLeast(0L)).apply()

    fun clearApiKeys() {
        prefs.edit()
            .remove(KEY_GEMINI)
            .remove(KEY_TOKOCRYPTO_API_KEY)
            .remove(KEY_TOKOCRYPTO_SECRET_KEY)
            .remove(KEY_UPDATE_GH_TOKEN)
            .apply()
    }

    private fun getInstallationSalt(): ByteArray {
        var saltStr = prefs.getString(KEY_INSTALLATION_SALT, null)
        if (saltStr.isNullOrBlank()) {
            val randomBytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(randomBytes)
            saltStr = android.util.Base64.encodeToString(randomBytes, android.util.Base64.NO_WRAP)

            val oldHash = prefs.getString(KEY_SECURITY_PIN_HASH, "")
            if (!oldHash.isNullOrBlank()) {
                prefs.edit()
                    .putBoolean(KEY_PIN_RESET_REQUIRED, true)
                    .remove(KEY_SECURITY_PIN_HASH)
                    .apply()
            }
            prefs.edit().putString(KEY_INSTALLATION_SALT, saltStr).apply()
        }
        return android.util.Base64.decode(saltStr, android.util.Base64.NO_WRAP)
    }

    private fun hashPin(pin: String): String {
        val salt = getInstallationSalt()
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun getDefaultWatchlistSymbol(source: MarketDataSource = marketDataSource): String = "BTCUSDT"

    private fun watchlistKey(source: MarketDataSource): String = KEY_WATCHLIST_TOKOCRYPTO

    /**
     * Memeriksa apakah [symbol] memang diperdagangkan di bursa [source] berdasarkan kuotasinya.
     * Mencegah simbol dengan kuotasi yang tidak dikenal masuk ke watchlist.
     */
    private fun symbolBelongsToExchange(symbol: String, source: MarketDataSource): Boolean {
        val quote = PriceFormatter.extractQuote(symbol)
        return when (source) {
            // Tokocrypto: USDT/USDC/BUSD (kuotasi kripto) + IDR/BIDR (kuotasi rupiah).
            MarketDataSource.TOKOCRYPTO -> quote == "USDT" || quote == "USDC" ||
                    quote == "BUSD" || quote == "IDR" || quote == "BIDR"
        }
    }

    fun getWatchlist(source: MarketDataSource = marketDataSource): Set<String> {
        val key = watchlistKey(source)
        val saved = prefs.getStringSet(key, null)
        if (saved != null && saved.isNotEmpty()) return saved.toSet()

        // Migrasi satu kali dari daftar lama tanpa partisi bursa (KEY_WATCHLIST_LEGACY).
        // Hanya simbol yang benar-benar milik bursa ini yang diadopsi, lalu disimpan ke
        // key per-bursa. Dengan begitu daftar lama tidak bocor ke bursa lain.
        val legacy = prefs.getStringSet(KEY_WATCHLIST_LEGACY, null)
        if (legacy != null && legacy.isNotEmpty()) {
            val migrated = legacy
                .map { it.uppercase().trim() }
                .filter { it.isNotBlank() && symbolBelongsToExchange(it, source) }
                .toSet()
            if (migrated.isNotEmpty()) {
                prefs.edit().putStringSet(key, migrated).apply()
                return migrated
            }
        }
        return setOf(getDefaultWatchlistSymbol(source))
    }

    fun setWatchlist(symbols: Collection<String>, source: MarketDataSource = marketDataSource) {
        val defaultSym = getDefaultWatchlistSymbol(source)
        val upperSet = symbols.map { it.uppercase().trim() }.filter { it.isNotBlank() }.toSet()
        val finalSet = if (upperSet.isEmpty()) setOf(defaultSym) else upperSet
        prefs.edit().putStringSet(watchlistKey(source), finalSet).apply()
    }

    fun toggleWatchlist(symbol: String, source: MarketDataSource = marketDataSource): Boolean {
        val set = getWatchlist(source).toMutableSet()
        val upper = symbol.uppercase()
        val added = if (set.remove(upper)) false else { set.add(upper); true }
        prefs.edit().putStringSet(watchlistKey(source), set).apply()
        return added
    }

    fun isInWatchlist(symbol: String, source: MarketDataSource = marketDataSource): Boolean =
        getWatchlist(source).contains(symbol.uppercase())

    fun getFavorites(source: MarketDataSource = marketDataSource): Set<String> {
        val key = KEY_FAVORITES_TOKOCRYPTO
        val saved = prefs.getStringSet(key, null)
        if (saved != null && saved.isNotEmpty()) return saved.toSet()
        return setOf(getDefaultWatchlistSymbol(source))
    }

    fun toggleFavorite(symbol: String, source: MarketDataSource = marketDataSource): Boolean {
        val set = getFavorites(source).toMutableSet()
        val upper = symbol.uppercase()
        val added = if (set.remove(upper)) false else { set.add(upper); true }
        val key = KEY_FAVORITES_TOKOCRYPTO
        prefs.edit().putStringSet(key, set).apply()
        return added
    }

    fun isFavorite(symbol: String, source: MarketDataSource = marketDataSource): Boolean =
        getFavorites(source).contains(symbol.uppercase())

    fun setFavorite(symbol: String, isFav: Boolean) {
        val set = getFavorites().toMutableSet()
        val upper = symbol.uppercase()
        if (isFav) set.add(upper) else set.remove(upper)
        prefs.edit().putStringSet(KEY_FAVORITES_TOKOCRYPTO, set).apply()
    }

    fun getCompletedLearningLessons(): Set<Int> = prefs.getStringSet(KEY_LEARNING_COMPLETED, emptySet())
        ?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()

    fun setLearningLessonCompleted(index: Int, completed: Boolean) {
        val set = getCompletedLearningLessons().toMutableSet()
        if (completed) set.add(index) else set.remove(index)
        prefs.edit().putStringSet(KEY_LEARNING_COMPLETED, set.map(Int::toString).toSet()).apply()
    }

    fun getSavedRealBalance(exchange: String = marketDataSource.name): Map<String, Double> {
        val exKey = "${exchange.lowercase()}_real_balance"
        val jsonStr = prefs.getString(exKey, null).orEmpty()
        if (jsonStr.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, Double>()
            json.keys().forEach { key ->
                val v = json.optDouble(key, 0.0)
                map[key.lowercase()] = v
                map[key.uppercase()] = v
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveRealBalance(balances: Map<String, Double>, exchange: String = marketDataSource.name) {
        try {
            val json = JSONObject()
            balances.forEach { (k, v) ->
                json.put(k.lowercase(), v)
            }
            val exKey = "${exchange.lowercase()}_real_balance"
            prefs.edit().putString(exKey, json.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getSavedRealAvgBuyPrices(exchange: String = marketDataSource.name): Map<String, Double> {
        val exKey = "${exchange.lowercase()}_real_avg_prices"
        val jsonStr = prefs.getString(exKey, null).orEmpty()
        if (jsonStr.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, Double>()
            json.keys().forEach { key ->
                map[key.uppercase()] = json.optDouble(key, 0.0)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveRealAvgBuyPrices(avgMap: Map<String, Double>, exchange: String = marketDataSource.name) {
        try {
            val json = JSONObject()
            avgMap.forEach { (k, v) ->
                json.put(k.uppercase(), v)
            }
            val exKey = "${exchange.lowercase()}_real_avg_prices"
            prefs.edit().putString(exKey, json.toString()).apply()
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile var isKeyStoreRecoveryOccurred: Boolean = false
        const val DEFAULT_UPDATE_REPO = "Agus27712/agentfeed"
        private const val PREFS_NAME = "krypto_analysis_prefs"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_MARKET_SOURCE = "market_data_source"
        private const val KEY_STRATEGY_MODE = "strategy_mode"
        private const val KEY_SCALPING_MODE = "scalping_mode"
        private const val KEY_SCALPING_SENSITIVITY = "scalping_sensitivity"
        private const val KEY_SCALPING_RUNTIME_CONFIG = "scalping_runtime_config_json_v1"
        private const val KEY_SCALPING_PRESET = "scalping_sensitivity_preset_v1"
        private const val KEY_INTRADAY_RUNTIME_CONFIG = "intraday_runtime_config_v1"
        private const val KEY_INTRADAY_PRESET = "intraday_sensitivity_preset_v1"
        private const val KEY_SWING_RUNTIME_CONFIG = "swing_runtime_config_v1"
        private const val KEY_SWING_PRESET = "swing_sensitivity_preset_v1"
        private const val KEY_PAPER_AUTO_BUY_ENABLED = "paper_auto_buy_enabled_v1"
        private const val KEY_PAPER_AUTO_EXIT_ENABLED = "paper_auto_exit_enabled_v1"
        private const val KEY_PAPER_AUTO_BUY_ALLOCATION = "paper_auto_buy_allocation_v1"
        private const val KEY_PAPER_AUTO_BUY_FIXED_QUOTE = "paper_auto_buy_fixed_quote_v1"
        private const val KEY_PAPER_AUTO_BUY_RISK_PCT = "paper_auto_buy_risk_pct_v1"
        private const val KEY_PAPER_AUTO_BUY_MIN_STAGE = "paper_auto_buy_min_stage_v1"
        private const val KEY_PAPER_AUTO_BUY_MAX_POSITIONS = "paper_auto_buy_max_positions_v1"
        private const val KEY_PAPER_AUTO_REENTRY_COOLDOWN_MIN = "paper_auto_reentry_cooldown_min_v1"
        private const val KEY_PAPER_TRADE_JOURNAL = "paper_trade_journal_v1"
        private const val KEY_WATCHLIST_LEGACY = "watchlist_symbols"
        private const val KEY_WATCHLIST_TOKOCRYPTO = "watchlist_tokocrypto"
        private const val KEY_FAVORITES_TOKOCRYPTO = "favorites_tokocrypto"
        private const val KEY_LEARNING_COMPLETED = "learning_completed_lessons"
        private const val KEY_BUY_MAKER = "fee_buy_maker"
        private const val KEY_BUY_TAKER = "fee_buy_taker"
        private const val KEY_SELL_MAKER = "fee_sell_maker"
        private const val KEY_SELL_TAKER = "fee_sell_taker"

        /** Penanda apakah pengguna pernah menimpa biaya; bila false → pakai default bursa. */
        private const val KEY_FEES_CUSTOM = "fee_is_custom"
        private const val KEY_UPDATE_REPO = "github_update_repo"
        private const val KEY_UPDATE_GH_TOKEN = "github_update_token"
        private const val KEY_COMPACT_UI = "compact_ui"
        private const val KEY_REAL_BUY_MODE = "real_buy_mode_enabled"
        private const val KEY_SECURITY_PIN_HASH = "security_pin_hash"
        private const val KEY_FAILED_PIN_ATTEMPTS = "failed_pin_attempts_count"
        private const val KEY_TOKOCRYPTO_API_KEY = "tokocrypto_encrypted_api_key"
        private const val KEY_TOKOCRYPTO_SECRET_KEY = "tokocrypto_encrypted_secret_key"
        private const val KEY_RECENT_HISTORY_BASES = "recent_history_bases_v1"
        private const val KEY_INSTALLATION_SALT = "sec_installation_salt_v2"
        private const val KEY_PIN_RESET_REQUIRED = "sec_pin_reset_required"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled_v1"
        private const val KEY_REAL_SIM_SYNC_ENABLED = "real_sim_sync_enabled_v1"
        private const val KEY_PRICE_FEED_THROTTLE_MS = "price_feed_throttle_ms_v1"
        private const val KEY_AGENT_OUTPUT_ENABLED = "agent_output_enabled"
        private const val KEY_AGENT_BRIDGE_TOKEN = "agent_bridge_token_v1"
        private const val PREFS_BRIDGE_NAME = "agent_bridge_prefs_v1"
    }
}
