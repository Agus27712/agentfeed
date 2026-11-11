package agu.analys

import android.content.Context

/** Process-wide application context for services that must outlive a composable/ViewModel. */
object AppContextProvider {
    lateinit var context: Context
        private set

    @Volatile
    var isAppInForeground: Boolean = false

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}
