package agu.analys.trading

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Uji persistensi progres FSM paper di [SpotPositionStore] (P4.4).
 *
 * Yang dijaga di sini bukan isi JSON — skema JSON dimiliki `engine.scalping.PaperExitFsmState` dan
 * diuji di `PaperExitFsmStateTest`. Yang diuji adalah **siklus hidup key**-nya:
 *
 * 1. store menyimpan dan mengembalikan string apa adanya (tidak mem-parsing, tidak memvalidasi);
 * 2. key benar-benar **dibersihkan** saat posisi dijual — kalau tidak, progres milik posisi lama
 *    bisa terbaca oleh posisi berikutnya yang memakai simbol sama.
 */
@RunWith(RobolectricTestRunner::class)
class SpotPositionStorePaperFsmTest {

    private val ex = "TOKOCRYPTO"
    private val json = "{\"v\":1,\"entry\":100.0,\"sl\":94.0,\"active_sl\":101.1,\"peak\":103.0}"

    private companion object {
        /** Harus sama dengan `SpotPositionStore.PREFS_NAME`. */
        const val PREFS_NAME = "analysis_ui_spot_positions"
    }

    private fun newStore(): SpotPositionStore =
        SpotPositionStore(RuntimeEnvironment.getApplication())

    /**
     * Prefs **yang benar-benar dipakai** store.
     *
     * Store mencoba `EncryptedSharedPreferences` lebih dulu dan baru jatuh ke prefs biasa bila gagal
     * (lihat `SpotPositionStore` konstruktor). Di Robolectric jalur terenkripsi berhasil, jadi uji
     * yang perlu menulis key mentah harus memakai prefs yang sama — bukan prefs biasa.
     *
     * @param probeKey key yang ditulis store untuk posisi probe, dipakai untuk memastikan prefs
     *   yang dikembalikan memang yang dilihat store.
     */
    private fun storePrefs(probeKey: String): SharedPreferences? {
        val ctx = RuntimeEnvironment.getApplication()
        val plain = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (plain.contains(probeKey)) return plain
        return runCatching {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "${PREFS_NAME}_encrypted",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()?.takeIf { it.contains(probeKey) }
    }

    /**
     * Blok legacy di `markSold` menargetkan key tanpa prefix bursa (`${norm}_state`), yang hanya bisa
     * ditulis lewat prefs mentah. Bila prefs store tidak bisa dijangkau, uji dilewati alih-alih
     * memberi hasil menyesatkan.
     */
    private fun prefsUntukLegacy(store: SpotPositionStore, norm: String): SharedPreferences? {
        store.markBought(symbol = norm, entryPrice = 1.0, invested = 1.0, quantity = 1.0, exchange = ex)
        // `buildKey` menormalkan simbol ke huruf besar, jadi key nyatanya juga huruf besar.
        return storePrefs("${ex.lowercase()}_sim_${norm}_state")
    }

    @Test
    fun `set lalu get mengembalikan JSON yang sama`() {
        val store = newStore()
        store.setPaperFsmState(symbol = "BTCUSDT", exchange = ex, stateJson = json)
        assertEquals(json, store.getPaperFsmState("BTCUSDT", exchange = ex))
    }

    @Test
    fun `get mengembalikan null saat belum pernah disimpan`() {
        val store = newStore()
        assertNull(store.getPaperFsmState("BELUMADAUSDT", exchange = ex))
    }

    @Test
    fun `menulis ulang menimpa nilai lama`() {
        val store = newStore()
        store.setPaperFsmState(symbol = "ETHUSDT", exchange = ex, stateJson = json)
        val baru = "{\"v\":1,\"entry\":200.0,\"sl\":188.0,\"active_sl\":188.0,\"peak\":200.0}"
        store.setPaperFsmState(symbol = "ETHUSDT", exchange = ex, stateJson = baru)
        assertEquals(baru, store.getPaperFsmState("ETHUSDT", exchange = ex))
    }

    @Test
    fun `clearPaperFsmState menghapus nilai`() {
        val store = newStore()
        store.setPaperFsmState(symbol = "SOLUSDT", exchange = ex, stateJson = json)
        store.clearPaperFsmState(symbol = "SOLUSDT", exchange = ex)
        assertNull(store.getPaperFsmState("SOLUSDT", exchange = ex))
    }

    @Test
    fun `markSold membersihkan progres FSM`() {
        val store = newStore()
        store.markBought(symbol = "APTUSDT", entryPrice = 10.0, invested = 10.0, quantity = 1.0, exchange = ex)
        store.setPaperFsmState(symbol = "APTUSDT", exchange = ex, stateJson = json)
        assertEquals(json, store.getPaperFsmState("APTUSDT", exchange = ex))

        store.markSold("APTUSDT", exchange = ex)

        assertNull("progres harus ikut terhapus saat posisi dijual", store.getPaperFsmState("APTUSDT", exchange = ex))
    }

    @Test
    fun `markSold membersihkan progres FSM gaya legacy`() {
        val store = newStore()
        val norm = "ADAUSDT"
        val prefs = prefsUntukLegacy(store, norm)
        assumeNotNull("prefs store tidak bisa dijangkau di lingkungan ini", prefs)
        val p = prefs!!

        // Bentuk key lama (tanpa prefix bursa) — hanya bisa ditulis lewat prefs mentah.
        p.edit()
            .putString("${norm}_state", SpotPositionState.HOLDING.name)
            .putBoolean("${norm}_is_real", false)
            .putString("${norm}_paper_fsm", json)
            .apply()

        store.markSold(norm, exchange = ex)

        assertNull("key gaya legacy harus ikut dibersihkan", p.getString("${norm}_paper_fsm", null))
    }

    @Test
    fun `isi rusak dikembalikan apa adanya tanpa diparsing`() {
        val store = newStore()
        val rusak = "ini-bukan-json"
        store.setPaperFsmState(symbol = "SUIUSDT", exchange = ex, stateJson = rusak)
        assertEquals(
            "store tidak boleh mem-parsing; penolakan JSON adalah tugas engine",
            rusak,
            store.getPaperFsmState("SUIUSDT", exchange = ex)
        )
    }
}
