package agu.analys.trading

/**
 * Satu posisi paper yang exit-nya dipegang engine paper, lengkap dengan bursanya.
 *
 * P1 — [SpotPositionStore.getPaperEngineSymbols] mensyaratkan bursa, sehingga pemanggil yang
 * ingin menemukan **semua** posisi paper (mis. rehidrasi setelah restart) tidak punya cara
 * melakukannya. Padahal kunci posisi sudah menyimpan bursanya.
 *
 * @param exchange nama bursa dalam huruf besar (mis. `TOKOCRYPTO`)
 * @param symbol simbol kanonik tanpa pemisah (mis. `BTCIDR`)
 */
data class PaperEnginePosition(val exchange: String, val symbol: String)
