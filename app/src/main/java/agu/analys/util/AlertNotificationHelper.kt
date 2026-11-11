package agu.analys.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import agu.analys.config.FeeSchedule
import agu.analys.MainActivity
import agu.analys.config.StrategyMode
import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.MarketKey
import agu.analys.model.SellSignalState
import agu.analys.model.TradingPair

object AlertNotificationHelper {
    // Channel 1: Candidate / Pair Ready to Buy
    const val CHANNEL_CANDIDATE_ID = "channel_candidate_buy_alerts"
    const val CHANNEL_CANDIDATE_NAME = "Notifikasi Pair Ready & Sinyal Buy"
    const val CHANNEL_CANDIDATE_DESC = "Sinyal koin kandidat strategi yang siap entry / buy"

    // Channel 2: Trailing Stop & Execution
    const val CHANNEL_TRAILING_ID = "channel_trailing_stop_alerts"
    const val CHANNEL_TRAILING_NAME = "Notifikasi Trailing Stop & Eksekusi"
    const val CHANNEL_TRAILING_DESC = "Sinyal kenaikan trailing profit dan eksekusi take profit otomatis"

    // Channel 3: Emergency Exit & Stop Loss (Rapid Drop / Flash Dump)
    const val CHANNEL_EMERGENCY_EXIT_ID = "channel_emergency_exit_alerts"
    const val CHANNEL_EMERGENCY_EXIT_NAME = "Notifikasi Exit Darurat & Stop Loss"
    const val CHANNEL_EMERGENCY_EXIT_DESC = "Peringatan darurat saat Stop Loss tersentuh atau terjadi Flash Dump / Rapid Drop"

    // Channel 4: General Price Alerts
    const val CHANNEL_PRICE_ALERT_ID = "channel_price_alerts"
    const val CHANNEL_PRICE_ALERT_NAME = "Notifikasi Target Harga & Market"
    const val CHANNEL_PRICE_ALERT_DESC = "Notifikasi perubahan harga target dan indikator teknikal"

    // Extra Constants for Intent routing
    const val EXTRA_SYMBOL = "EXTRA_SYMBOL"
    const val EXTRA_EXCHANGE = "EXTRA_EXCHANGE"
    const val EXTRA_QUOTE = "EXTRA_QUOTE"
    const val EXTRA_IS_REAL = "EXTRA_IS_REAL"
    const val EXTRA_LIMIT_PRICE = "EXTRA_LIMIT_PRICE"
    const val EXTRA_QUANTITY = "EXTRA_QUANTITY"
    const val ACTION_EXECUTE_TRAILING_SELL = "agu.analys.ACTION_EXECUTE_TRAILING_SELL"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val audioAttributesNotification = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build()

            val audioAttributesAlarm = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            // Candidate Buy Channel
            val candidateSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val candidateChannel = NotificationChannel(
                CHANNEL_CANDIDATE_ID,
                CHANNEL_CANDIDATE_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_CANDIDATE_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                setSound(candidateSoundUri, audioAttributesNotification)
            }

            // Trailing Stop Channel
            val trailingSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val trailingChannel = NotificationChannel(
                CHANNEL_TRAILING_ID,
                CHANNEL_TRAILING_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_TRAILING_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setSound(trailingSoundUri, audioAttributesNotification)
            }

            // Emergency Exit Channel (High priority / Alarm feel)
            val emergencySoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val emergencyChannel = NotificationChannel(
                CHANNEL_EMERGENCY_EXIT_ID,
                CHANNEL_EMERGENCY_EXIT_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_EMERGENCY_EXIT_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setSound(emergencySoundUri, audioAttributesAlarm)
            }

            // General Price Alert Channel
            val priceAlertSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val priceAlertChannel = NotificationChannel(
                CHANNEL_PRICE_ALERT_ID,
                CHANNEL_PRICE_ALERT_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_PRICE_ALERT_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150)
                setSound(priceAlertSoundUri, audioAttributesNotification)
            }

            notificationManager.createNotificationChannels(
                listOf(candidateChannel, trailingChannel, emergencyChannel, priceAlertChannel)
            )
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Cache `AppPreferences` untuk membaca sakelar user.
     *
     * Konstruktor `AppPreferences` membangun `EncryptedSharedPreferences` — terlalu mahal bila
     * dipanggil tiap notifikasi. Satu instance cukup karena aplikasi hanya punya satu set prefs.
     */
    @Volatile
    private var cachedPrefs: AppPreferences? = null

    /**
     * Hanya untuk uji: buang cache `AppPreferences` supaya sakelar dibaca ulang dari nol.
     *
     * Tanpa ini, uji kedua dalam satu kelas akan memakai instance prefs milik `Application`
     * Robolectric dari uji sebelumnya.
     */
    @androidx.annotation.VisibleForTesting
    internal fun resetNotificationSettingsCache() {
        cachedPrefs = null
    }

    private fun resolvePrefs(context: Context): AppPreferences =
        cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: AppPreferences(context.applicationContext).also { cachedPrefs = it }
        }

    /**
     * Hanya untuk uji: instance prefs yang **benar-benar** dibaca [canNotify].
     *
     * Uji perlu menulis sakelar lewat objek ini, bukan lewat `AppPreferences(context)` baru:
     * di bawah Robolectric membuat `AppPreferences` kedua tidak melihat tulisan instance pertama
     * (jalur pemulihan keystore menimpa prefs), sehingga uji akan lolos/gagal karena artefak
     * harness, bukan karena gerbangnya.
     */
    @androidx.annotation.VisibleForTesting
    internal fun notificationPrefsForTest(context: Context): AppPreferences = resolvePrefs(context)

    private fun notificationSettings(context: Context): NotificationSettings =
        runCatching { resolvePrefs(context).notificationSettings }
            .getOrDefault(NotificationSettings())

    /**
     * **Gerbang tunggal** sebelum push dikirim: izin OS **dan** sakelar user
     * (lihat [allowsNotification]).
     *
     * Ditaruh di sini, bukan di tiap pemanggil, justru karena itulah akar N4: ada belasan call
     * site dan tidak satu pun memeriksa sakelar, sehingga sakelar master maupun keempat sakelar
     * kategori tidak berpengaruh. Dengan gerbang di chokepoint ini, call site baru otomatis ikut
     * teratur.
     *
     * Bila `AppPreferences` gagal dibaca (keystore rusak), gerbang **terbuka** — sama dengan
     * perilaku sebelum perbaikan, supaya kerusakan prefs tidak diam-diam mematikan peringatan
     * darurat.
     */
    /** N12 — cooldown kandidat per pair, hanya di memori proses. */
    private val candidateCooldown = CandidateNotifyCooldown()

    private fun candidateCooldownKey(marketKey: MarketKey): String =
        "${marketKey.exchange.uppercase()}_${marketKey.symbol.uppercase()}"

    /** Hanya untuk uji: cooldown bersifat proses, jadi harus bisa dikosongkan antar kasus. */
    internal fun resetCandidateCooldownForTest() {
        candidateCooldown.clear()
    }

    private fun canNotify(context: Context, category: NotificationCategory): Boolean =
        hasNotificationPermission(context) &&
            allowsNotification(notificationSettings(context), category)

    // 1. Candidate Buy Notification
    fun sendCandidateFoundNotification(
        context: Context,
        symbol: String,
        strategyMode: StrategyMode,
        signal: AISignalState,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendCandidateFoundNotification(context, marketKey, strategyMode, signal)
    }

    fun sendCandidateFoundNotification(
        context: Context,
        marketKey: MarketKey,
        strategyMode: StrategyMode,
        signal: AISignalState
    ) {
        if (!canNotify(context, NotificationCategory.CANDIDATE_BUY)) return
        // N12 — cooldown per pair. State yang flapping READY → INVALIDATED → READY akan lolos
        // edge-trigger berulang kali; jendela ini yang menahan bunyi beruntun untuk setup yang
        // praktis sama. Ditaruh di sini supaya jalur foreground (VM) dan latar (worker) sama-sama
        // terkena — keduanya lewat helper ini.
        val cooldownKey = candidateCooldownKey(marketKey)
        val nowMs = System.currentTimeMillis()
        if (!candidateCooldown.allow(cooldownKey, nowMs)) return

        val strategyLabel = when (strategyMode) {
            StrategyMode.SCALPING -> "SCALPING M15/M5"
            StrategyMode.OFFICE_DAILY -> "INTRADAY H1"
            StrategyMode.SWING -> "SWING H4/D1"
        }

        val pairFormatted = marketKey.formattedPair()

        val title = "🚀 Sinyal Entry Siap • $pairFormatted ($strategyLabel)"
        val message = buildCandidateSignalMessage(marketKey, signal)
        val entryFormatted = PriceFormatter.formatPrice(
            signal.entryPrice, showSymbol = true, quoteAsset = marketKey.quote
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(false, 100),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CANDIDATE_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Kandidat BUY terdeteksi di $entryFormatted")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Lihat Chart", pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(false, 100), notification)
    }

    // 2. Emergency Exit Notification
    fun sendEmergencyExitNotification(
        context: Context,
        symbol: String,
        state: SellSignalState,
        currentPrice: Double,
        entryPrice: Double,
        quantity: Double,
        isReal: Boolean,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendEmergencyExitNotification(context, marketKey, state, currentPrice, entryPrice, quantity, isReal)
    }

    fun sendEmergencyExitNotification(
        context: Context,
        marketKey: MarketKey,
        state: SellSignalState,
        currentPrice: Double,
        entryPrice: Double,
        quantity: Double,
        isReal: Boolean
    ) {
        if (!canNotify(context, NotificationCategory.EMERGENCY_EXIT)) return

        val pairFormatted = marketKey.formattedPair()
        val quoteAsset = marketKey.quote
        val modeTag = if (isReal) "REAL" else "SIMULASI"

        val currFormatted = PriceFormatter.formatPrice(currentPrice, showSymbol = true, quoteAsset = quoteAsset)
        val entryFormatted = PriceFormatter.formatPrice(entryPrice, showSymbol = true, quoteAsset = quoteAsset)

        val title = "🚨 EXIT DARURAT [$modeTag] • $pairFormatted"
        val message = "PERINGATAN: ${state.reason}\n" +
                "• Harga Terkini: $currFormatted\n" +
                "• Harga Beli: $entryFormatted\n" +
                "• Estimasi PnL: ${String.format(java.util.Locale.US, "%.2f", state.netProfitPct)}%\n" +
                "Segera periksa posisi Anda di [${marketKey.displayExchange}]."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 200),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sellIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_EXECUTE_TRAILING_SELL
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
            putExtra(EXTRA_LIMIT_PRICE, currentPrice)
            putExtra(EXTRA_QUANTITY, quantity)
            putExtra(EXTRA_IS_REAL, isReal)
        }
        val sellPendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 201),
            sellIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionLabel = if (isReal) "JUAL REAL SEKARANG" else "JUAL SEKARANG"

        val notification = NotificationCompat.Builder(context, CHANNEL_EMERGENCY_EXIT_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Bahaya: ${state.reason}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, actionLabel, sellPendingIntent)
            .addAction(0, "Buka Chart", pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(isReal, 200), notification)
    }

    // 3. Trailing Hit Notification
    fun sendTrailingHitNotification(
        context: Context,
        symbol: String,
        entryPrice: Double,
        peakPrice: Double,
        currentPrice: Double,
        limitSellPrice: Double,
        quantity: Double,
        isReal: Boolean,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendTrailingHitNotification(context, marketKey, entryPrice, peakPrice, currentPrice, limitSellPrice, quantity, isReal)
    }

    fun sendTrailingHitNotification(
        context: Context,
        marketKey: MarketKey,
        entryPrice: Double,
        peakPrice: Double,
        currentPrice: Double,
        limitSellPrice: Double,
        quantity: Double,
        isReal: Boolean
    ) {
        if (!canNotify(context, NotificationCategory.TRAILING_STOP)) return

        val pairFormatted = marketKey.formattedPair()
        val quoteAsset = marketKey.quote
        val modeTag = if (isReal) "REAL" else "SIMULASI"
        val netProfitPct = trailingHitNetProfitPct(marketKey, entryPrice, limitSellPrice)

        val title = "⚡ Trailing Stop Terpicu [$modeTag] • $pairFormatted"
        val limitFormatted = PriceFormatter.formatPrice(limitSellPrice, showSymbol = true, quoteAsset = quoteAsset)

        val message = buildTrailingHitMessage(marketKey, entryPrice, peakPrice, currentPrice, limitSellPrice)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 300),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TRAILING_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Trailing stop tersentuh di $limitFormatted (${PriceFormatter.formatPercentage(netProfitPct)} net)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Cek Portofolio", pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(isReal, 300), notification)
    }

    // 4. Trailing Peak Update Notification
    fun sendTrailingPeakUpdateNotification(
        context: Context,
        symbol: String,
        newPeak: Double,
        stopLimitPrice: Double,
        entryPrice: Double,
        isReal: Boolean,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendTrailingPeakUpdateNotification(context, marketKey, newPeak, stopLimitPrice, entryPrice, isReal)
    }

    fun sendTrailingPeakUpdateNotification(
        context: Context,
        marketKey: MarketKey,
        newPeak: Double,
        stopLimitPrice: Double,
        entryPrice: Double,
        isReal: Boolean
    ) {
        if (!canNotify(context, NotificationCategory.TRAILING_STOP)) return

        val pairFormatted = marketKey.formattedPair()
        val quoteAsset = marketKey.quote
        val modeTag = if (isReal) "REAL" else "SIMULASI"

        // H1/H2 — persen profit dihitung di sini dari basis net, bukan diterima dari pemanggil.
        // Parameternya dulu `profitPct` berisi selisih harga bruto, sehingga notifikasi menulis
        // angka yang lebih besar daripada halaman detail untuk posisi yang sama.
        val netProfitPct = FeeSchedule.netProfitPctFor(
            entry = entryPrice,
            price = newPeak,
            exchange = marketKey.exchange,
            quoteAsset = quoteAsset
        )
        val stopFormatted = PriceFormatter.formatPrice(stopLimitPrice, showSymbol = true, quoteAsset = quoteAsset)

        val title = "📈 Trailing Peak Naik [$modeTag] • $pairFormatted"
        val message = buildTrailingPeakUpdateMessage(marketKey, newPeak, stopLimitPrice, entryPrice)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 400),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TRAILING_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Stop limit dinaikkan ke $stopFormatted (${PriceFormatter.formatPercentage(netProfitPct)} net)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(isReal, 400), notification)
    }

    // 5. Take Profit Reached Notification
    fun sendTakeProfitNotification(
        context: Context,
        symbol: String,
        targetLabel: String,
        entryPrice: Double,
        currentPrice: Double,
        netProfitPct: Double,
        quantity: Double,
        isReal: Boolean,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendTakeProfitNotification(context, marketKey, targetLabel, entryPrice, currentPrice, netProfitPct, quantity, isReal)
    }

    fun sendTakeProfitNotification(
        context: Context,
        marketKey: MarketKey,
        targetLabel: String,
        entryPrice: Double,
        currentPrice: Double,
        netProfitPct: Double,
        quantity: Double,
        isReal: Boolean
    ) {
        if (!canNotify(context, NotificationCategory.TRAILING_STOP)) return

        val pairFormatted = marketKey.formattedPair()
        val quoteAsset = marketKey.quote
        val modeTag = if (isReal) "REAL" else "SIMULASI"

        val profitStr = String.format(java.util.Locale.US, "%.2f", netProfitPct)
        val currFormatted = PriceFormatter.formatPrice(currentPrice, showSymbol = true, quoteAsset = quoteAsset)
        val entryFormatted = PriceFormatter.formatPrice(entryPrice, showSymbol = true, quoteAsset = quoteAsset)

        val title = "🎯 Target Tercapai ($targetLabel) [$modeTag] • $pairFormatted"
        val message = "Posisi $pairFormatted di [${marketKey.displayExchange}] mencapai target:\n" +
                "• Harga Jual: $currFormatted\n" +
                "• Harga Beli: $entryFormatted\n" +
                "• Net Profit: +$profitStr%\n" +
                "Koin siap untuk direalisasikan."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 500),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sellIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_EXECUTE_TRAILING_SELL
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
            putExtra(EXTRA_LIMIT_PRICE, currentPrice)
            putExtra(EXTRA_QUANTITY, quantity)
            putExtra(EXTRA_IS_REAL, isReal)
        }
        val sellPendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 501),
            sellIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionLabel = if (isReal) "JUAL REAL SEKARANG" else "JUAL SEKARANG"

        val notification = NotificationCompat.Builder(context, CHANNEL_TRAILING_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Target tercapai di $currFormatted (+$profitStr%)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, actionLabel, sellPendingIntent)
            .addAction(0, "Cek Portofolio", pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(isReal, 500), notification)
    }

    // 6. User Price Alert Notification
    /**
     * Pengingat harga yang **dibuat pengguna sendiri** (mis. "beri tahu saat BTC menembus 1 M").
     *
     * N13 — jangan pakai ini untuk laporan eksekusi jual/trailing. Kategori `PRICE_ALERT` berarti
     * mematikan sakelar "Trading & Price Alerts" ikut mematikan konfirmasi order berhasil/gagal.
     * Untuk hasil eksekusi pakai [sendExecutionResultNotification].
     *
     * N8 — parameter `onlyWhenBackground` dihapus: nilainya tidak pernah dibaca di dalam fungsi
     * (param mati), dan satu-satunya pemanggil yang mengisinya sudah tidak ada.
     */
    fun sendUserPriceAlertNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = 9999,
        symbol: String = "",
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = if (symbol.isNotBlank()) MarketKey.resolve(symbol, exchange, quote) else null
        sendUserPriceAlertNotification(context, title, message, notificationId, marketKey)
    }

    fun sendUserPriceAlertNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = 9999,
        marketKey: MarketKey? = null
    ) {
        if (!canNotify(context, NotificationRouting.USER_PRICE_ALERT)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (marketKey != null) {
                putExtra(EXTRA_SYMBOL, marketKey.symbol)
                putExtra(EXTRA_EXCHANGE, marketKey.exchange)
                putExtra(EXTRA_QUOTE, marketKey.quote)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PRICE_ALERT_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }

    // 7. Execution Result Notification (jual, trailing, batch sell)
    /**
     * N13 — laporan **hasil eksekusi**: order terkirim, order gagal, trailing ternaikkan, jual
     * dibatalkan, batch sell selesai.
     *
     * Kategori `TRAILING_STOP` ("Notifikasi Trailing Stop & Eksekusi"), **bukan** `PRICE_ALERT`.
     * Konsekuensinya disengaja: pengguna yang mematikan "Trading & Price Alerts" tetap menerima
     * kabar bahwa order jual uang riilnya gagal — informasi yang tidak boleh hilang.
     *
     * Kanalnya juga `CHANNEL_TRAILING_ID` supaya satu peristiwa tidak muncul di dua kanal berbeda.
     */
    fun sendExecutionResultNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = 9998,
        symbol: String = "",
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = if (symbol.isNotBlank()) MarketKey.resolve(symbol, exchange, quote) else null
        sendExecutionResultNotification(context, title, message, notificationId, marketKey)
    }

    fun sendExecutionResultNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = 9998,
        marketKey: MarketKey? = null
    ) {
        if (!canNotify(context, NotificationRouting.EXECUTION)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (marketKey != null) {
                putExtra(EXTRA_SYMBOL, marketKey.symbol)
                putExtra(EXTRA_EXCHANGE, marketKey.exchange)
                putExtra(EXTRA_QUOTE, marketKey.quote)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_TRAILING_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }
}

/**
 * Kalimat konteks wajib untuk setiap permukaan yang menampilkan status siap (AGENTS.md:
 * "Status SIAP bukan ajakan beli"). Push notification kandidat membawa angka Entry/Target/Stop
 * Loss, jadi ia paling berisiko dibaca sebagai ajakan beli nyata.
 */
internal const val CANDIDATE_DISCLAIMER =
    "→ Eksekusi uang riil masih DIBLOKIR. Sinyal ini decision support, bukan ajakan beli."

/**
 * Badan pesan notifikasi kandidat BUY.
 *
 * Dipisah dari [AlertNotificationHelper.sendCandidateFoundNotification] supaya bisa diuji tanpa
 * Android. **N1:** `AISignalState.confidence` sudah `Int` skala **0–100**
 * (`model/TradingViewModels.kt`), jadi nilainya dipakai apa adanya — jangan dikali 100.
 * Sebelum perbaikan 2026-10-16 baris ini menulis `(signal.confidence * 100)`, sehingga skor
 * `68` tampil sebagai `6800%` di push notification.
 *
 * **N5:** baris [CANDIDATE_DISCLAIMER] wajib ikut — lihat alasannya di konstanta itu.
 */
internal fun buildCandidateSignalMessage(marketKey: MarketKey, signal: AISignalState): String {
    val quoteAsset = marketKey.quote
    val entryFormatted = PriceFormatter.formatPrice(signal.entryPrice, showSymbol = true, quoteAsset = quoteAsset)
    val tp1Formatted = PriceFormatter.formatPrice(signal.targetPrice1, showSymbol = true, quoteAsset = quoteAsset)
    val slFormatted = PriceFormatter.formatPrice(signal.stopLoss, showSymbol = true, quoteAsset = quoteAsset)

    return "Kandidat BUY terkonfirmasi [${marketKey.displayExchange}]:\n" +
        "• Entry: $entryFormatted\n" +
        "• Target 1: $tp1Formatted\n" +
        "• Stop Loss: $slFormatted\n" +
        "• Skor Keyakinan: ${signal.confidence}%\n" +
        CANDIDATE_DISCLAIMER
}
/**
 * Persen profit **net** untuk notifikasi trailing, memakai bursa + kuotasi dari [marketKey].
 *
 * H1/H2 — notifikasi dulu menghitung `(limitSellPrice − entryPrice) / entryPrice`, yaitu selisih
 * harga **bruto**. Halaman detail memakai `FeeSchedule.netProfitPctFor` (`SellEstimate.netProfitPct`,
 * `ActiveHoldingSection`), jadi untuk posisi yang sama notifikasi menampilkan angka lebih besar
 * (entry 1.000.000 → 1.050.000: bruto +5,00%, net +4,15%). Semua persen profit di permukaan
 * notifikasi wajib lewat sini atau `FeeSchedule` langsung, jangan dihitung ulang di call site.
 */
internal fun trailingHitNetProfitPct(marketKey: MarketKey, entryPrice: Double, limitSellPrice: Double): Double =
    FeeSchedule.netProfitPctFor(
        entry = entryPrice,
        price = limitSellPrice,
        exchange = marketKey.exchange,
        quoteAsset = marketKey.quote
    )

/**
 * Badan pesan "Trailing Stop Terpicu". Angka profit di sini **net** (lihat
 * [trailingHitNetProfitPct]); labelnya menyebut "net" supaya tidak dibandingkan dengan persen
 * pergerakan harga mentah.
 */
internal fun buildTrailingHitMessage(
    marketKey: MarketKey,
    entryPrice: Double,
    peakPrice: Double,
    currentPrice: Double,
    limitSellPrice: Double
): String {
    val quoteAsset = marketKey.quote
    val limitFormatted = PriceFormatter.formatPrice(limitSellPrice, showSymbol = true, quoteAsset = quoteAsset)
    val peakFormatted = PriceFormatter.formatPrice(peakPrice, showSymbol = true, quoteAsset = quoteAsset)
    val currFormatted = PriceFormatter.formatPrice(currentPrice, showSymbol = true, quoteAsset = quoteAsset)
    val netStr = PriceFormatter.formatPercentage(trailingHitNetProfitPct(marketKey, entryPrice, limitSellPrice))

    return "Batas pengaman trailing tercapai [${marketKey.displayExchange}]:\n" +
        "• Harga Eksekusi: $limitFormatted\n" +
        "• Harga Puncak: $peakFormatted\n" +
        "• Harga Terkini: $currFormatted\n" +
        "• Estimasi Profit Net: $netStr\n" +
        "Auto-sell otomatis diluncurkan untuk mengamankan profit."
}

/**
 * Badan pesan "Trailing Peak Naik". Persen yang ditampilkan adalah profit **net** di harga puncak
 * baru, bukan pergerakan harga mentah.
 */
internal fun buildTrailingPeakUpdateMessage(
    marketKey: MarketKey,
    newPeak: Double,
    stopLimitPrice: Double,
    entryPrice: Double
): String {
    val quoteAsset = marketKey.quote
    val peakFormatted = PriceFormatter.formatPrice(newPeak, showSymbol = true, quoteAsset = quoteAsset)
    val stopFormatted = PriceFormatter.formatPrice(stopLimitPrice, showSymbol = true, quoteAsset = quoteAsset)
    val netStr = PriceFormatter.formatPercentage(
        FeeSchedule.netProfitPctFor(
            entry = entryPrice,
            price = newPeak,
            exchange = marketKey.exchange,
            quoteAsset = quoteAsset
        )
    )

    return "Puncak harga naik ke $peakFormatted [${marketKey.displayExchange}] (Profit Puncak Net: $netStr).\n" +
        "Batas stop limit otomatis dinaikkan ke $stopFormatted untuk mengunci profit."
}
