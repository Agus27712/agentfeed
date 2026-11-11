package agu.analys.engine.scalping

import agu.analys.config.FeeSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ekspor JSON dari app harus memakai angka [FeeSchedule], bukan literal kedua.
 *
 * Cacat yang dijaga: `toJson()` pernah menulis 0.4044 sendiri dan tidak mengekspor
 * `idr_reference`, sehingga config hasil ekspor tidak bisa dipakai replay untuk pair IDR.
 */
class ScalpingRuntimeConfigFeeExportTest {

    @Test
    fun `fee ekspor sama dengan FeeSchedule dan punya referensi IDR`() {
        val json = ScalpingRuntimeConfig().toJson()
        val fee = json.getJSONObject("fee")

        assertEquals(FeeSchedule.TOKOCRYPTO_USDT_BUY_TAKER * 100.0, fee.getDouble("buy_taker_pct"), 1e-9)
        assertEquals(FeeSchedule.TOKOCRYPTO_USDT_SELL_TAKER * 100.0, fee.getDouble("sell_taker_pct"), 1e-9)
        assertEquals(FeeSchedule.TOKOCRYPTO_USDT_BUY_TAKER * 100.0, fee.getDouble("buy_maker_pct"), 1e-9)
        assertEquals(FeeSchedule.TOKOCRYPTO_USDT_SELL_TAKER * 100.0, fee.getDouble("sell_maker_pct"), 1e-9)

        val idr = fee.getJSONObject("idr_reference")
        assertEquals(FeeSchedule.TOKOCRYPTO_IDR_BUY_TAKER * 100.0, idr.getDouble("buy_taker_pct"), 1e-9)
        assertEquals(FeeSchedule.TOKOCRYPTO_IDR_SELL_TAKER * 100.0, idr.getDouble("sell_taker_pct"), 1e-9)
        assertEquals(FeeSchedule.TOKOCRYPTO_IDR_BUY_MAKER * 100.0, idr.getDouble("buy_maker_pct"), 1e-9)
        assertEquals(FeeSchedule.TOKOCRYPTO_IDR_SELL_MAKER * 100.0, idr.getDouble("sell_maker_pct"), 1e-9)
    }

    @Test
    fun `ekspor memuat parameter parity replay dan tanpa knob order type`() {
        val json = ScalpingRuntimeConfig().toJson()

        assertEquals(ScalpingConfig.MAX_RISK_ATR, json.getDouble("max_risk_atr"), 1e-9)
        assertEquals(ScalpingConfig.MAX_CHANGE_24H_PCT, json.getDouble("max_change_24h_pct"), 1e-9)
        assertTrue(json.has("exit"))

        val exit = json.getJSONObject("exit")
        assertEquals(ScalpExitParams.DEFAULT.mode.key, exit.getString("mode"))
        assertEquals(ScalpExitParams.DEFAULT.maxHoldBarsM1, exit.getInt("max_hold_bars_m1"))
        assertEquals(ScalpExitParams.DEFAULT.minProfitFrictionMultiple, exit.getDouble("min_profit_friction_multiple"), 1e-9)

        assertFalse("replay/app hanya punya jalur taker", json.has("order_type"))
        assertFalse("knob maker tidak boleh diekspor", exit.has("order_type"))
    }
}
