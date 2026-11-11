package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import agu.analys.database.AppDatabase
import agu.analys.database.TradeHistoryRecorder
import agu.analys.database.TradeHistoryRecordEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import agu.analys.util.AppPreferences

class PortfolioViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val positionStore = SpotPositionStore(application)
    private val tradeHistoryRecorder = TradeHistoryRecorder(
        dao = AppDatabase.getInstance().tradeHistoryRecordDao(),
        scope = viewModelScope
    )

    val currentExchange: String get() = prefs.marketDataSource.name

    private val _spotPosition = MutableStateFlow(SpotPosition())
    val spotPosition: StateFlow<SpotPosition> = _spotPosition.asStateFlow()

    private val _positionVersion = MutableStateFlow(0L)
    val positionVersion: StateFlow<Long> = _positionVersion.asStateFlow()

    val tradeHistoryRecords: StateFlow<List<TradeHistoryRecordEntity>> = tradeHistoryRecorder.allRecordsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadSpotPositions()
    }

    fun loadSpotPositions(symbol: String = "BTCIDR", isReal: Boolean = false) {
        viewModelScope.launch {
            _spotPosition.value = positionStore.get(symbol, isReal, currentExchange)
            _positionVersion.value = System.currentTimeMillis()
        }
    }

    fun seedSampleTradeJourneys(force: Boolean = true) {
        viewModelScope.launch { tradeHistoryRecorder.seedSampleTradeJourneys(force) }
    }

    fun deleteTradeHistoryRecord(id: Long) {
        viewModelScope.launch { tradeHistoryRecorder.deleteRecord(id) }
    }

    fun clearAllTradeHistoryRecords() {
        viewModelScope.launch { tradeHistoryRecorder.clearAllRecords() }
    }
}
