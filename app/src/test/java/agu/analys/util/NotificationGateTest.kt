package agu.analys.util

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import agu.analys.config.StrategyMode
import agu.analys.model.AISignalState
import agu.analys.model.MarketKey
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * **N4 + sakelar kategori mati** — gerbang notifikasi.
 *
 * Sebelum perbaikan 2026-10-16:
 *  - `TradingViewModelAlerts` tidak pernah membaca `isNotificationsEnabled` (0 kemunculan di
 *    berkas itu), sehingga user yang mematikan notifikasi di Pengaturan masih menerima bel;
 *  - **keempat** sakelar kategori (`isNotifyPriceAlertsEnabled`, `isNotifyCandidateBuyEnabled`,
 *    `isNotifyTrailingStopEnabled`, `isNotifyEmergencyExitEnabled`) hanya dibaca/ditulis di
 *    `SettingsViewModel` dan dirender di UI — **tidak ada satu pun jalur pengiriman yang
 *    membacanya**, jadi Pengaturan menawarkan empat sakelar yang tidak melakukan apa pun.
 *
 * Kontrol negatif: kembalikan `canNotify(context, ...)` menjadi `hasNotificationPermission(context)`
 * di `AlertNotificationHelper` → ketiga uji perilaku di bawah GAGAL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationGateTest {

    private lateinit var app: Application
    private lateinit var ctx: Context
    private lateinit var appPrefs: AppPreferences

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ctx = app
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AlertNotificationHelper.createNotificationChannels(ctx)
        AlertNotificationHelper.resetNotificationSettingsCache()
        AlertNotificationHelper.resetCandidateCooldownForTest()
        // Instance yang BENAR-BENAR dibaca gerbang; menulis lewat objek lain tidak terlihat
        // di bawah Robolectric (lihat catatan `notificationPrefsForTest`).
        appPrefs = AlertNotificationHelper.notificationPrefsForTest(ctx)
    }

    /** Jumlah notifikasi yang benar-benar terpasang di NotificationManager. */
    private fun posted(): Int =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications.size

    private fun sendPriceAlert(id: Int) =
        AlertNotificationHelper.sendUserPriceAlertNotification(ctx, "Judul", "Pesan", notificationId = id)

    private fun sendExecutionResult(id: Int) =
        AlertNotificationHelper.sendExecutionResultNotification(ctx, "Judul", "Pesan", notificationId = id)

    // ── Fungsi murni ────────────────────────────────────────────────────────

    @Test
    fun `default semua kategori diizinkan`() {
        val s = NotificationSettings()
        NotificationCategory.entries.forEach {
            assertTrue("default harus mengizinkan $it", allowsNotification(s, it))
        }
    }

    @Test
    fun `sakelar master mati mematikan seluruh kategori`() {
        val s = NotificationSettings(masterEnabled = false)
        NotificationCategory.entries.forEach {
            assertFalse("master mati harus menahan $it", allowsNotification(s, it))
        }
    }

    @Test
    fun `sakelar kategori hanya mematikan kategorinya sendiri`() {
        val s = NotificationSettings(trailingStopEnabled = false)

        assertFalse(allowsNotification(s, NotificationCategory.TRAILING_STOP))
        assertTrue(allowsNotification(s, NotificationCategory.PRICE_ALERT))
        assertTrue(allowsNotification(s, NotificationCategory.CANDIDATE_BUY))
        assertTrue(allowsNotification(s, NotificationCategory.EMERGENCY_EXIT))
    }

    // ── Perilaku (gerbang benar-benar dipasang di jalur kirim) ──────────────

    @Test
    fun `sakelar master mati menahan notifikasi harga`() {
        appPrefs.isNotificationsEnabled = true
        sendPriceAlert(4001)
        assertEquals("prasyarat: harness uji harus bisa memasang notifikasi", 1, posted())

        appPrefs.isNotificationsEnabled = false
        sendPriceAlert(4002)

        assertEquals("master mati: notifikasi berikutnya tidak boleh terkirim", 1, posted())
    }

    @Test
    fun `sakelar kategori price alert menahan kategori itu saja`() {
        appPrefs.isNotificationsEnabled = true
        appPrefs.isNotifyPriceAlertsEnabled = false
        appPrefs.isNotifyTrailingStopEnabled = true

        sendPriceAlert(4101)
        assertEquals("price alert dimatikan -> tidak boleh terkirim", 0, posted())

        AlertNotificationHelper.sendTrailingHitNotification(
            context = ctx,
            symbol = "BTCIDR",
            entryPrice = 100.0,
            peakPrice = 110.0,
            currentPrice = 105.0,
            limitSellPrice = 104.0,
            quantity = 1.0,
            isReal = false
        )
        assertEquals("trailing stop masih menyala -> harus terkirim", 1, posted())
    }

    @Test
    fun `sakelar candidate buy menahan notifikasi kandidat`() {
        appPrefs.isNotificationsEnabled = true
        appPrefs.isNotifyCandidateBuyEnabled = false

        AlertNotificationHelper.sendCandidateFoundNotification(
            context = ctx,
            marketKey = MarketKey.resolve("BTCIDR", "TOKOCRYPTO", "IDR"),
            strategyMode = StrategyMode.SCALPING,
            signal = AISignalState(
                action = SignalAction.BUY,
                confidence = 70,
                entryPrice = 1_000.0,
                targetPrice1 = 1_050.0,
                stopLoss = 970.0
            )
        )

        assertEquals("sakelar candidate buy dimatikan -> tidak boleh terkirim", 0, posted())
    }

    // ── N13: kategori laporan eksekusi ──────────────────────────────────────

    /**
     * Inti N13: laporan "order jual berhasil/gagal" **bukan** price alert.
     *
     * Kalau pengguna mematikan "Trading & Price Alerts" (karena tidak mau diganggu pengingat
     * harga), konfirmasi bahwa order uang riilnya gagal harus **tetap** sampai. Sebelum
     * perbaikan, seluruh laporan eksekusi memakai kategori `PRICE_ALERT`, jadi ikut mati.
     */
    @Test
    fun `sakelar price alert mati tidak menahan laporan eksekusi`() {
        appPrefs.isNotificationsEnabled = true
        appPrefs.isNotifyPriceAlertsEnabled = false
        appPrefs.isNotifyTrailingStopEnabled = true

        sendExecutionResult(4201)

        assertEquals(
            "N13: laporan eksekusi memakai kategori TRAILING_STOP, bukan PRICE_ALERT",
            1,
            posted()
        )
    }

    @Test
    fun `sakelar trailing stop mati menahan laporan eksekusi`() {
        appPrefs.isNotificationsEnabled = true
        appPrefs.isNotifyTrailingStopEnabled = false
        appPrefs.isNotifyPriceAlertsEnabled = true

        sendExecutionResult(4301)

        assertEquals("sakelar eksekusi dimatikan -> laporan tidak boleh terkirim", 0, posted())
    }

    @Test
    fun `kategori routing tidak tertukar`() {
        assertEquals(NotificationCategory.TRAILING_STOP, NotificationRouting.EXECUTION)
        assertEquals(NotificationCategory.PRICE_ALERT, NotificationRouting.USER_PRICE_ALERT)
    }
}
