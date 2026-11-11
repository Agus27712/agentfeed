package agu.analys.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Centralized Price & Asset Value Formatter for Tokocrypto and crypto assets.
 * 
 * Optimized for performance:
 * - Static cached DecimalFormatSymbols to prevent repeated locale lookup.
 * - ThreadLocal cached DecimalFormat instances to eliminate object allocation in hot paths.
 *
 * Complies with Tokocrypto spot API rules for:
 * - IDR quote pairs (Whole Rupiah for standard coins, decimal fractions for micro/meme coins)
 * - USDT / USD quote pairs (2 decimals for major pairs, high precision for micro pairs)
 * - Percentage formatting with dynamic precision and sign handling (+ / -)
 * - Order price & quantity formatting for API payload calculations without precision mismatch
 * - Volume formatting with localized unit scaling (T/Mil/jt/rb for IDR, B/M/K for USDT)
 */
object PriceFormatter {

    private val ID_LOCALE = Locale.forLanguageTag("id-ID")

    private val US_SYMBOLS = DecimalFormatSymbols(Locale.US)
    private val ID_SYMBOLS = DecimalFormatSymbols(ID_LOCALE).apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }

    private val usFormatters = ThreadLocal.withInitial { HashMap<String, DecimalFormat>() }
    private val idFormatters = ThreadLocal.withInitial { HashMap<String, DecimalFormat>() }

    private fun getUsFormat(pattern: String): DecimalFormat {
        val map = usFormatters.get() ?: HashMap<String, DecimalFormat>().also { usFormatters.set(it) }
        return map.getOrPut(pattern) {
            DecimalFormat(pattern, US_SYMBOLS)
        }
    }

    private fun getIdFormat(pattern: String): DecimalFormat {
        val map = idFormatters.get() ?: HashMap<String, DecimalFormat>().also { idFormatters.set(it) }
        return map.getOrPut(pattern) {
            DecimalFormat(pattern, ID_SYMBOLS)
        }
    }

    /**
     * Format harga dengan simbol mata uang dinamis (IDR / USDT / BIDR / USD)
     * Mengikuti aturan presisi pair bursa.
     */
    fun formatPrice(
        price: Double,
        showSymbol: Boolean = true,
        quoteAsset: String = "IDR",
        decimals: Int? = null
    ): String {
        val isUsdt = isUsdtQuote(quoteAsset)
        if (price.isNaN() || price.isInfinite() || price == 0.0) {
            return if (!showSymbol) "0" else if (isUsdt) "$0.00" else "Rp 0"
        }

        val isNegative = price < 0.0
        val absPrice = abs(price)

        val formatted = if (isUsdt) {
            val prefix = if (showSymbol) "$" else ""
            if (decimals != null) {
                val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
                prefix + getUsFormat(pattern).format(absPrice)
            } else {
                when {
                    absPrice < 0.00001 -> prefix + getUsFormat("0.########").format(absPrice)
                    absPrice < 0.001 -> prefix + getUsFormat("0.######").format(absPrice)
                    absPrice < 1.0 -> prefix + getUsFormat("0.####").format(absPrice)
                    absPrice < 10.0 -> prefix + getUsFormat("0.###").format(absPrice)
                    else -> prefix + getUsFormat("#,##0.00").format(absPrice)
                }
            }
        } else {
            val prefix = if (showSymbol) "Rp " else ""
            if (decimals != null) {
                val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
                prefix + getIdFormat(pattern).format(absPrice)
            } else {
                when {
                    absPrice < 0.00001 -> prefix + getIdFormat("0.########").format(absPrice)
                    absPrice < 0.01 -> prefix + getIdFormat("0.######").format(absPrice)
                    absPrice < 1.0 -> prefix + getIdFormat("0.####").format(absPrice)
                    absPrice < 100.0 && absPrice % 1.0 != 0.0 -> prefix + getIdFormat("#,##0.##").format(absPrice)
                    else -> {
                        val rounded = kotlin.math.round(absPrice).toLong()
                        prefix + getIdFormat("#,##0").format(rounded)
                    }
                }
            }
        }
        return if (isNegative) "-$formatted" else formatted
    }

    /** Alias — selalu full price dengan quoteAsset sesuai */
    fun formatPriceFull(price: Double, quoteAsset: String = "IDR"): String =
        formatPrice(price, showSymbol = true, quoteAsset = quoteAsset)

    /**
     * Format harga Rupiah **ekuivalen** untuk pair yang berkuotasi USDT (dua harga: `$` + `Rp`).
     *
     * Nilai Rupiah dihitung dari kurs USDT/IDR **real-time** yang diteruskan pemanggil
     * (dari [ExchangeRateManager]). Tidak ada konstanta hardcode di sini.
     *
     * @param usdtPrice harga dalam USDT (kuotasi `$`)
     * @param usdtIdrRate kurs USDT→IDR; `<= 0.0` berarti rate belum tersedia
     * @return teks `≈ Rp 1.234.567`, atau `null` bila harga/rate tidak valid.
     *         Pemanggil **wajib menyembunyikan** baris harga Rp saat hasilnya `null`
     *         daripada menampilkan angka hasil tebakan.
     */
    fun formatIdrEquivalent(
        usdtPrice: Double,
        usdtIdrRate: Double,
        withApproxPrefix: Boolean = true
    ): String? {
        if (usdtPrice.isNaN() || usdtPrice.isInfinite() || usdtPrice <= 0.0) return null
        if (usdtIdrRate.isNaN() || usdtIdrRate.isInfinite() || usdtIdrRate <= 0.0) return null
        val idrValue = usdtPrice * usdtIdrRate
        if (idrValue.isNaN() || idrValue.isInfinite()) return null
        val text = formatPrice(idrValue, showSymbol = true, quoteAsset = "IDR", decimals = 0)
        return if (withApproxPrefix) "≈ $text" else text
    }

    /**
     * Nominal minimum order **dalam mata uang kuotasi pair**.
     *
     * Exchange mewajibkan notional minimum ≈ Rp 10.000. Untuk pair berkuotasi USDT
     * nilainya diturunkan dari kurs live; bila kurs belum tersedia dikembalikan `0.0`
     * yang berarti "lewati validasi batas nominal" (bukan memakai kurs tebakan).
     */
    fun minOrderNominal(quoteAsset: String, usdtIdrRate: Double): Double {
        val minIdr = 10_000.0
        if (!isUsdtQuote(quoteAsset)) return minIdr
        if (usdtIdrRate.isNaN() || usdtIdrRate.isInfinite() || usdtIdrRate <= 0.0) return 0.0
        return minIdr / usdtIdrRate
    }

    /**
     * Konversi USDT → IDR berbasis rate yang diberikan (bukan hardcode).
     * Mengembalikan `null` bila rate belum tersedia supaya pemanggil bisa menahan tampilan.
     */
    fun usdtToIdrOrNull(usdtAmount: Double, usdtIdrRate: Double): Double? {
        if (usdtAmount.isNaN() || usdtAmount.isInfinite()) return null
        if (usdtIdrRate.isNaN() || usdtIdrRate.isInfinite() || usdtIdrRate <= 0.0) return null
        val v = usdtAmount * usdtIdrRate
        return if (v.isNaN() || v.isInfinite()) null else v
    }

    /** Format harga khusus USDT/USD dengan simbol dollar */
    fun formatUsdtPrice(amount: Double, showSymbol: Boolean = true): String =
        formatPrice(amount, showSymbol = showSymbol, quoteAsset = "USDT")

    /** Format uang serbaguna berdasarkan quoteAsset */
    fun formatMoney(value: Double, quoteAsset: String = "IDR", showSymbol: Boolean = true): String =
        formatPrice(value, showSymbol = showSymbol, quoteAsset = quoteAsset)

    /**
     * Format volume 24h dengan satuan singkatan sesuai mata uang kuotasi
     */
    fun formatVolume(volume: Double, quoteAsset: String = "IDR"): String {
        if (volume.isNaN() || volume.isInfinite() || volume == 0.0) {
            return if (isUsdtQuote(quoteAsset)) "$0" else "Rp 0"
        }
        val absVol = abs(volume)
        val isUsdt = isUsdtQuote(quoteAsset)
        if (isUsdt) {
            val fmt = getUsFormat("#.##")
            return when {
                absVol >= 1_000_000_000.0 -> "$" + fmt.format(volume / 1_000_000_000.0) + " B"
                absVol >= 1_000_000.0 -> "$" + fmt.format(volume / 1_000_000.0) + " M"
                absVol >= 1_000.0 -> "$" + fmt.format(volume / 1_000.0) + " K"
                else -> "$" + fmt.format(volume)
            }
        } else {
            val fmt = getIdFormat("#.##")
            return when {
                absVol >= 1_000_000_000_000.0 -> "Rp " + fmt.format(volume / 1_000_000_000_000.0) + " T"
                absVol >= 1_000_000_000.0 -> "Rp " + fmt.format(volume / 1_000_000_000.0) + " Mil"
                absVol >= 1_000_000.0 -> "Rp " + fmt.format(volume / 1_000_000.0) + " jt"
                absVol >= 1_000.0 -> "Rp " + fmt.format(volume / 1_000.0) + " rb"
                else -> formatPrice(volume, showSymbol = true, quoteAsset = quoteAsset)
            }
        }
    }

    /**
     * Format persentase standar dengan tanda +/- dan presisi desimal kustom
     * Cth: +2.45%, -0.80%, 0.00%
     */
    fun formatPercentage(
        change: Double,
        includePlusSign: Boolean = true,
        decimals: Int = 2
    ): String {
        if (change.isNaN() || change.isInfinite()) {
            val zeros = if (decimals <= 0) "0" else "0." + "0".repeat(decimals)
            return "$zeros%"
        }
        val pattern = if (decimals <= 0) "0" else "0." + "0".repeat(decimals)
        val formatted = getUsFormat(pattern).format(abs(change))
        return when {
            change > 0.0 -> if (includePlusSign) "+$formatted%" else "$formatted%"
            change < 0.0 -> "-$formatted%"
            else -> {
                val zeros = if (decimals <= 0) "0" else "0." + "0".repeat(decimals)
                "$zeros%"
            }
        }
    }

    /**
     * Format harga yang valid untuk payload order (tanpa exponential / ribuan separator).
     * Mencegah kegagalan eksekusi order riil akibat mismatch presisi.
     */
    fun formatOrderPrice(
        price: Double,
        quoteAsset: String = "IDR",
        priceDecimals: Int? = null
    ): String {
        if (price.isNaN() || price.isInfinite() || price <= 0.0) return "0"
        val dec = priceDecimals ?: determinePriceDecimals(price, quoteAsset)
        return BigDecimal.valueOf(price)
            .setScale(dec, RoundingMode.HALF_UP)
            .toPlainString()
    }

    /**
     * Format kuantitas yang valid untuk payload order.
     * Menggunakan pembulatan ke bawah (RoundingMode.DOWN) agar order tidak melebihi saldo akun.
     */
    fun formatOrderQuantity(
        quantity: Double,
        baseAsset: String = "",
        qtyDecimals: Int = 8
    ): String {
        if (quantity.isNaN() || quantity.isInfinite() || quantity <= 0.0) return "0"
        val dec = qtyDecimals.coerceIn(0, 8)
        return BigDecimal.valueOf(quantity)
            .setScale(dec, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
    }

    /**
     * Menentukan presisi desimal harga sesuai aturan pair & nilai harga.
     */
    fun determinePriceDecimals(price: Double, quoteAsset: String): Int {
        val isUsdt = isUsdtQuote(quoteAsset)
        val absPrice = abs(price)
        return if (isUsdt) {
            when {
                absPrice < 0.00001 -> 8
                absPrice < 0.001 -> 6
                absPrice < 1.0 -> 4
                absPrice < 10.0 -> 3
                else -> 2
            }
        } else {
            when {
                absPrice < 0.00001 -> 8
                absPrice < 0.01 -> 6
                absPrice < 1.0 -> 4
                absPrice < 100.0 && absPrice % 1.0 != 0.0 -> 2
                else -> 0
            }
        }
    }

    fun isUsdtQuote(quoteAsset: String): Boolean {
        return quoteAsset.equals("USDT", true) ||
                quoteAsset.equals("USD", true) ||
                quoteAsset.equals("BUSD", true) ||
                quoteAsset.equals("USDC", true)
    }

    fun isIdrQuote(quoteAsset: String): Boolean {
        return quoteAsset.equals("IDR", true) ||
                quoteAsset.equals("BIDR", true) ||
                quoteAsset.equals("IDRT", true)
    }

    /**
     * Ekstraksi quote asset dari symbol pasangan trading (misal BTCUSDT -> USDT, ETHIDR -> IDR)
     */
    fun extractQuote(symbol: String, defaultQuote: String = "IDR"): String {
        val clean = symbol.uppercase().replace("_", "").trim()
        return when {
            clean.endsWith("USDT") -> "USDT"
            clean.endsWith("USDC") -> "USDC"
            clean.endsWith("BUSD") -> "BUSD"
            clean.endsWith("BIDR") -> "BIDR"
            clean.endsWith("IDR") -> "IDR"
            clean.endsWith("IDRT") -> "IDRT"
            clean.endsWith("USD") -> "USDT"
            else -> defaultQuote
        }
    }

    fun formatRsi(rsi: Double): String {
        if (rsi.isNaN() || rsi.isInfinite()) return "50.0"
        return getUsFormat("0.0").format(rsi)
    }

    fun formatIndicatorVal(value: Double, decimals: Int = 2): String {
        if (value.isNaN() || value.isInfinite()) return "0.0"
        val pattern = buildString {
            append("0.")
            repeat(decimals) { append("0") }
        }
        return getUsFormat(pattern).format(value)
    }

    fun formatRawDecimal(value: Double): String {
        if (value.isNaN() || value.isInfinite() || value <= 0.0) return "0"
        return if (value >= 1.0) {
            if (value % 1.0 == 0.0) {
                value.toLong().toString()
            } else {
                String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
            }
        } else {
            String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
        }
    }

    fun formatQuantity(quantity: Double, maxDecimals: Int = 8): String {
        if (quantity.isNaN() || quantity.isInfinite() || quantity <= 0.0) return "0"
        return if (quantity >= 1000.0) {
            String.format(Locale.US, "%,.2f", quantity).replace(",", ".")
        } else if (quantity >= 1.0) {
            String.format(Locale.US, "%.4f", quantity).trimEnd('0').trimEnd('.')
        } else {
            val pattern = "0." + "#".repeat(maxDecimals.coerceIn(2, 8))
            getUsFormat(pattern).format(quantity)
        }
    }

    /** Format desimal koin kripto presisi tinggi (cth: 0,00002774 BTC) */
    fun formatCryptoExact(amount: Double, maxDecimals: Int = 8): String {
        if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) return "0"
        val pattern = "0." + "#".repeat(maxDecimals.coerceIn(2, 10))
        return getIdFormat(pattern).format(amount)
    }

    /** Format nominal IDR dengan dukungan pecahan desimal koin kecil (cth: 38.028 atau 0,00015 atau -40) */
    fun formatIdrNumber(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite() || amount == 0.0) return "0"
        val absVal = abs(amount)
        val formatted = when {
            absVal < 0.00001 -> getIdFormat("0.########").format(absVal)
            absVal < 0.01 -> getIdFormat("0.######").format(absVal)
            absVal < 1.0 -> getIdFormat("0.####").format(absVal)
            absVal < 100.0 && absVal % 1.0 != 0.0 -> getIdFormat("#,##0.##").format(absVal)
            else -> {
                val rounded = kotlin.math.round(absVal).toLong()
                getIdFormat("#,##0").format(rounded)
            }
        }
        return if (amount < 0) "- $formatted" else formatted
    }

    /** Parser serbaguna untuk input nominal IDR/koin dari pengguna (menangani titik/koma/spasi/teks) */
    fun parseCleanIdrDouble(input: String): Double {
        if (input.isBlank()) return 0.0
        val cleaned = input.trim()
            .replace("Rp", "", ignoreCase = true)
            .replace("IDR", "", ignoreCase = true)
            .replace("BTC", "", ignoreCase = true)
            .replace("USDT", "", ignoreCase = true)
            .replace("$", "")
            .trim()
        if (cleaned.isBlank()) return 0.0

        val hasComma = cleaned.contains(",")
        val hasDot = cleaned.contains(".")

        val sanitized = if (hasDot && hasComma) {
            val lastDot = cleaned.lastIndexOf('.')
            val lastComma = cleaned.lastIndexOf(',')
            if (lastDot > lastComma) {
                // Contoh: "1,367,959.50" -> koma ribuan, titik desimal
                cleaned.replace(",", "")
            } else {
                // Contoh: "1.367.959,50" -> titik ribuan, koma desimal
                cleaned.replace(".", "").replace(",", ".")
            }
        } else if (hasDot) {
            val dotCount = cleaned.count { it == '.' }
            if (dotCount > 1) {
                // Multiple dots (cth: "1.367.959.000" atau "2.500.000") -> pemisah ribuan
                cleaned.replace(".", "")
            } else {
                // Single dot: cth "41.00", "41.0", "41.25", "0.5", "0.00015", "1.000"
                val parts = cleaned.split('.')
                val beforeDot = parts.getOrNull(0).orEmpty().trim()
                val afterDot = parts.getOrNull(1).orEmpty().trim()
                if (beforeDot == "0" || afterDot.length != 3 || beforeDot.length > 3) {
                    cleaned
                } else {
                    // Integer ribuan seperti 1.000, 50.000
                    cleaned.replace(".", "")
                }
            }
        } else if (hasComma) {
            val commaCount = cleaned.count { it == ',' }
            if (commaCount > 1) {
                cleaned.replace(",", "")
            } else {
                // Single comma: cth "41,50" atau "0,00015" -> koma adalah desimal
                cleaned.replace(",", ".")
            }
        } else {
            cleaned
        }

        return sanitized.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: 0.0
    }

    /** Parser serbaguna untuk input nilai numerik */
    fun parseCleanDouble(input: String): Double = parseCleanIdrDouble(input)

    /** Helper umum untuk format angka desimal ringkas di evaluator & sinyal */
    fun fmt(v: Double, decimals: Int = 2): String =
        String.format(Locale.US, "%.${decimals}f", v)

    /** Helper umum untuk format angka harga ringkas di evaluator & sinyal */
    fun fmtPrice(v: Double): String =
        when {
            v >= 1000.0 -> String.format(Locale.US, "%,.0f", v)
            v >= 1.0 -> String.format(Locale.US, "%.2f", v)
            v >= 0.01 -> String.format(Locale.US, "%.4f", v)
            v > 0.0 -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
            else -> "0"
        }

    /** Helper umum untuk format angka harga bulat integer di evaluator & sinyal */
    fun fmtPriceInt(v: Double): String =
        String.format(Locale.US, "%,.0f", v)

    fun formatCoinQuantity(quantity: BigDecimal, baseAsset: String, decimals: Int = 8): String {
        if (quantity.compareTo(BigDecimal.ZERO) == 0) return "0 $baseAsset"
        val formatted = quantity.setScale(decimals, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
        return "$formatted $baseAsset"
    }

    fun formatCoinQuantity(quantity: Double, baseAsset: String, decimals: Int = 8): String {
        return formatCoinQuantity(BigDecimal.valueOf(quantity), baseAsset, decimals)
    }
}