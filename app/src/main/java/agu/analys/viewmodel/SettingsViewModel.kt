package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataSource
import agu.analys.config.TradingFeeConfig
import agu.analys.ui.animation.PriceAnimationMode
import agu.analys.ui.theme.AccentColorPreset
import agu.analys.ui.theme.AnimationSpeed
import agu.analys.ui.theme.CandleColorStyle
import agu.analys.ui.theme.ThemeStyle
import agu.analys.util.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private val _isDarkTheme = MutableStateFlow(prefs.isDarkTheme)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _themeStyle = MutableStateFlow(prefs.themeStyle)
    val themeStyle: StateFlow<ThemeStyle> = _themeStyle.asStateFlow()

    private val _accentColorPreset = MutableStateFlow(prefs.accentColorPreset)
    val accentColorPreset: StateFlow<AccentColorPreset> = _accentColorPreset.asStateFlow()

    private val _candleColorStyle = MutableStateFlow(prefs.candleColorStyle)
    val candleColorStyle: StateFlow<CandleColorStyle> = _candleColorStyle.asStateFlow()

    private val _animationSpeed = MutableStateFlow(prefs.animationSpeed)
    val animationSpeed: StateFlow<AnimationSpeed> = _animationSpeed.asStateFlow()

    private val _priceAnimationMode = MutableStateFlow(prefs.priceAnimationMode)
    val priceAnimationMode: StateFlow<PriceAnimationMode> = _priceAnimationMode.asStateFlow()

    private val _isPriceTickPulseEnabled = MutableStateFlow(prefs.isPriceTickPulseEnabled)
    val isPriceTickPulseEnabled: StateFlow<Boolean> = _isPriceTickPulseEnabled.asStateFlow()

    private val _isSmoothChartEnabled = MutableStateFlow(prefs.isSmoothChartEnabled)
    val isSmoothChartEnabled: StateFlow<Boolean> = _isSmoothChartEnabled.asStateFlow()

    private val _isNotificationsEnabled = MutableStateFlow(prefs.isNotificationsEnabled)
    val isNotificationsEnabled: StateFlow<Boolean> = _isNotificationsEnabled.asStateFlow()

    private val _isNotifyCandidateBuyEnabled = MutableStateFlow(prefs.isNotifyCandidateBuyEnabled)
    val isNotifyCandidateBuyEnabled: StateFlow<Boolean> = _isNotifyCandidateBuyEnabled.asStateFlow()

    private val _isNotifyPriceAlertsEnabled = MutableStateFlow(prefs.isNotifyPriceAlertsEnabled)
    val isNotifyPriceAlertsEnabled: StateFlow<Boolean> = _isNotifyPriceAlertsEnabled.asStateFlow()

    private val _isNotifyTrailingStopEnabled = MutableStateFlow(prefs.isNotifyTrailingStopEnabled)
    val isNotifyTrailingStopEnabled: StateFlow<Boolean> = _isNotifyTrailingStopEnabled.asStateFlow()

    private val _isNotifyEmergencyExitEnabled = MutableStateFlow(prefs.isNotifyEmergencyExitEnabled)
    val isNotifyEmergencyExitEnabled: StateFlow<Boolean> = _isNotifyEmergencyExitEnabled.asStateFlow()

    private val _isRealSimSyncEnabled = MutableStateFlow(prefs.isRealSimSyncEnabled)
    val isRealSimSyncEnabled: StateFlow<Boolean> = _isRealSimSyncEnabled.asStateFlow()

    private val _aiProvider = MutableStateFlow(prefs.aiProvider)
    val aiProvider: StateFlow<AiProvider> = _aiProvider.asStateFlow()

    private val _marketDataSource = MutableStateFlow(prefs.marketDataSource)
    val marketDataSource: StateFlow<MarketDataSource> = _marketDataSource.asStateFlow()

    private val _geminiApiKey = MutableStateFlow(prefs.geminiApiKey)
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _tradingFees = MutableStateFlow(prefs.tradingFees)
    val tradingFees: StateFlow<TradingFeeConfig> = _tradingFees.asStateFlow()

    fun setDarkTheme(enabled: Boolean) {
        _isDarkTheme.value = enabled
        prefs.isDarkTheme = enabled
    }

    fun setThemeStyle(style: ThemeStyle) {
        _themeStyle.value = style
        prefs.themeStyle = style
    }

    fun setAccentColorPreset(preset: AccentColorPreset) {
        _accentColorPreset.value = preset
        prefs.accentColorPreset = preset
    }

    fun setCandleColorStyle(style: CandleColorStyle) {
        _candleColorStyle.value = style
        prefs.candleColorStyle = style
    }

    fun setAnimationSpeed(speed: AnimationSpeed) {
        _animationSpeed.value = speed
        prefs.animationSpeed = speed
    }

    fun setPriceAnimationMode(mode: PriceAnimationMode) {
        _priceAnimationMode.value = mode
        prefs.priceAnimationMode = mode
    }

    fun setPriceTickPulseEnabled(enabled: Boolean) {
        _isPriceTickPulseEnabled.value = enabled
        prefs.isPriceTickPulseEnabled = enabled
    }

    fun setSmoothChartEnabled(enabled: Boolean) {
        _isSmoothChartEnabled.value = enabled
        prefs.isSmoothChartEnabled = enabled
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _isNotificationsEnabled.value = enabled
        prefs.isNotificationsEnabled = enabled
    }

    fun setNotifyCandidateBuyEnabled(enabled: Boolean) {
        _isNotifyCandidateBuyEnabled.value = enabled
        prefs.isNotifyCandidateBuyEnabled = enabled
    }

    fun setNotifyPriceAlertsEnabled(enabled: Boolean) {
        _isNotifyPriceAlertsEnabled.value = enabled
        prefs.isNotifyPriceAlertsEnabled = enabled
    }

    fun setNotifyTrailingStopEnabled(enabled: Boolean) {
        _isNotifyTrailingStopEnabled.value = enabled
        prefs.isNotifyTrailingStopEnabled = enabled
    }

    fun setNotifyEmergencyExitEnabled(enabled: Boolean) {
        _isNotifyEmergencyExitEnabled.value = enabled
        prefs.isNotifyEmergencyExitEnabled = enabled
    }

    fun setRealSimSyncEnabled(enabled: Boolean) {
        _isRealSimSyncEnabled.value = enabled
        prefs.isRealSimSyncEnabled = enabled
    }

    fun setAiProvider(provider: AiProvider) {
        _aiProvider.value = provider
        prefs.aiProvider = provider
    }

    fun setMarketDataSource(source: MarketDataSource) {
        val previous = _marketDataSource.value
        _marketDataSource.value = source
        prefs.marketDataSource = source
        // Bila pengguna belum menimpa biaya sendiri, lepas nilai lama agar preset
        // default yang berlaku langsung dipakai dan perhitungan Net R:R tetap benar.
        if (previous != source && !prefs.hasCustomTradingFees) {
            prefs.resetTradingFeesToDefault()
        }
        _tradingFees.value = prefs.tradingFees
    }

    fun setGeminiApiKey(key: String) {
        _geminiApiKey.value = key
        prefs.geminiApiKey = key
    }

    fun setTradingFees(fees: TradingFeeConfig) {
        _tradingFees.value = fees
        prefs.tradingFees = fees
    }
}
