package agu.analys.util

import agu.analys.config.MarketDataSource

/**
 * Gate penambahan simbol ke watchlist (temuan **K13**).
 *
 * Cacat lama: kolom "ketik simbol" di setelan watchlist menerima teks bebas. Hasilnya langsung
 * disimpan, dan `TradingPair.fromCustomSymbol` mengarang metadata untuk simbol yang tidak dikenal.
 * Pair hantam seperti `UVXYBUSDT` karena itu bisa masuk watchlist, tampil sebagai kartu berharga
 * `-`, dan ikut dievaluasi engine — pintu masuk kedua bagi pair hantu, terlepas dari perbaikan
 * discovery (K1).
 *
 * Gate ini murni (tanpa Android `Context`) supaya bisa diuji, dan **fail-closed**: simbol yang
 * tidak bisa dibuktikan ada di bursa aktif ditolak, bukan diterima dengan catatan "nanti dicek".
 *
 * Tiga syarat untuk simbol baru:
 *  1. **format** — hanya huruf dan angka, panjang wajar;
 *  2. **kuotasi** — berakhiran kuotasi yang sah untuk bursa aktif;
 *  3. **keberadaan** — `isKnownSymbol` (repository bursa) mengakui simbol itu.
 *
 * Menghapus simbol dari watchlist **tidak** digate di sini: membersihkan daftar harus selalu bisa.
 */
object WatchlistEntryGate {

    /** Alasan penolakan; `null` pada [Verdict] berarti diterima. */
    enum class Rejection { BLANK, MALFORMED, WRONG_QUOTE, NOT_LISTED }

    data class Verdict(
        val normalized: String,
        val accepted: Boolean,
        val rejection: Rejection? = null,
        val message: String? = null
    )

    private val SYMBOL_PATTERN = Regex("^[A-Z0-9]{4,24}$")

    /** Bersihkan input mentah menjadi bentuk simbol: huruf besar, tanpa pemisah. */
    fun normalize(raw: String): String =
        raw.trim().uppercase()
            .replace("/", "")
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")

    /** Kuotasi yang sah untuk masing-masing bursa, yang terpanjang diperiksa lebih dulu. */
    fun knownQuotes(source: MarketDataSource): List<String> = when (source) {
        MarketDataSource.TOKOCRYPTO -> listOf("USDT", "USDC", "BUSD", "IDR", "BIDR")
    }

    /**
     * Nilai penambahan `rawSymbol` ke watchlist bursa [source].
     *
     * @param isKnownSymbol fungsi keberadaan dari repository bursa (lihat
     *   `TokocryptoSymbolRepository.hasSymbol` / `TokocryptoSymbolRepository.hasSymbol`).
     */
    fun checkAdd(
        rawSymbol: String,
        source: MarketDataSource,
        isKnownSymbol: (String) -> Boolean
    ): Verdict {
        val normalized = normalize(rawSymbol)
        if (normalized.isEmpty()) {
            return Verdict(normalized, false, Rejection.BLANK, "Simbol kosong.")
        }
        if (!SYMBOL_PATTERN.matches(normalized)) {
            return Verdict(
                normalized, false, Rejection.MALFORMED,
                "Format simbol tidak valid — hanya huruf dan angka."
            )
        }
        val quote = knownQuotes(source).firstOrNull { normalized.endsWith(it) }
            ?: return Verdict(
                normalized, false, Rejection.WRONG_QUOTE,
                "Kuotasi harus ${knownQuotes(source).joinToString(" atau ")} untuk ${source.label}."
            )
        if (normalized.length <= quote.length) {
            return Verdict(
                normalized, false, Rejection.MALFORMED,
                "Format simbol tidak valid — hanya huruf dan angka."
            )
        }
        if (!isKnownSymbol(normalized)) {
            return Verdict(
                normalized, false, Rejection.NOT_LISTED,
                "Simbol tidak ditemukan di ${source.label}. Periksa ejaan simbol atau tunggu " +
                    "daftar simbol ${source.label} termuat."
            )
        }
        return Verdict(normalized, true, null, null)
    }
}
