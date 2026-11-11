package agu.analys.viewmodel

import agu.analys.model.AppScreen
import agu.analys.model.TradingPair

fun TradingViewModel.navigateTo(screen: AppScreen) {
    if (_currentScreen.value != screen) {
        if (screen == AppScreen.DASHBOARD) {
            navigationStack.clear()
            _currentScreen.value = AppScreen.DASHBOARD
            return
        }
        if (navigationStack.isEmpty() || navigationStack.last() != _currentScreen.value) {
            navigationStack.add(_currentScreen.value)
        }
        _currentScreen.value = screen
    }
}

/** Buka chart layar penuh untuk pair yang dipilih. Halaman detail koin sudah dihapus. */
fun TradingViewModel.openChart(pair: TradingPair) {
    selectPair(pair)
    navigateTo(AppScreen.LANDSCAPE_CHART)
}

fun TradingViewModel.openSimulation(pair: TradingPair? = null) {
    if (pair != null) selectPair(pair)
    navigateTo(AppScreen.SIMULATION_TRADE)
}

fun TradingViewModel.openPortfolio() { navigateTo(AppScreen.PORTFOLIO) }
fun TradingViewModel.openLandscapeChart() { navigateTo(AppScreen.LANDSCAPE_CHART) }
fun TradingViewModel.closeLandscapeChart() { goBack() }
fun TradingViewModel.openSettings() { navigateTo(AppScreen.SETTINGS) }
fun TradingViewModel.openLearning() { navigateTo(AppScreen.LEARNING) }
fun TradingViewModel.openSignalLogs() { navigateTo(AppScreen.SIGNAL_LOGS) }

fun TradingViewModel.goBack(): Boolean {
    while (navigationStack.isNotEmpty() && navigationStack.last() == _currentScreen.value) {
        navigationStack.removeAt(navigationStack.size - 1)
    }

    return if (navigationStack.isNotEmpty()) {
        _currentScreen.value = navigationStack.removeAt(navigationStack.size - 1)
        true
    } else if (_currentScreen.value != AppScreen.DASHBOARD) {
        _currentScreen.value = AppScreen.DASHBOARD
        true
    } else {
        false
    }
}

