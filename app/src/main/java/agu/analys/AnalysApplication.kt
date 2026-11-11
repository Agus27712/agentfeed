package agu.analys

import android.app.Activity
import android.app.Application
import android.os.Bundle
import agu.analys.util.AppPreferences
import agu.analys.util.ExchangeRateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

class AnalysApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var activeActivityCount = 0
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppContextProvider.init(this)
        registerActivityLifecycleCallbacks(this)
        Timber.plant(agu.analys.util.AppLogManager.timberTree)
        if (BuildConfig.DEBUG) {
            Timber.plant(agu.analys.util.StructuredDebugTree())
        }
        // Kurs USDT/IDR: sumber tunggal orderbook Tokocrypto (USDT hanya diperdagangkan di Tokocrypto).
        ExchangeRateManager.init(this)
        ExchangeRateManager.startAutoRefresh(appScope)
        try {
            agu.analys.service.CandidateScanWorker.schedule(this)
        } catch (e: Exception) {
            Timber.w(e, "CandidateScanWorker schedule deferred or not initialized in test environment")
        }
    }

    override fun onTerminate() {
        ExchangeRateManager.stopAutoRefresh()
        super.onTerminate()
    }

    override fun onActivityStarted(activity: Activity) {
        activeActivityCount++
        AppContextProvider.isAppInForeground = activeActivityCount > 0
    }

    override fun onActivityStopped(activity: Activity) {
        activeActivityCount = maxOf(0, activeActivityCount - 1)
        AppContextProvider.isAppInForeground = activeActivityCount > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {
        AppContextProvider.isAppInForeground = true
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}