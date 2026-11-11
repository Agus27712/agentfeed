package agu.analys.ui.components.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import agu.analys.util.WatchlistEntryGate

@Composable
fun WatchlistManagerSettings(
    currentWatchlist: Set<String>,
    dashboardTicks: Map<String, MarketTick>,
    marketDataSource: MarketDataSource = MarketDataSource.TOKOCRYPTO,
    onAddPair: (String) -> Unit,
    onRemovePair: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val isToko = marketDataSource == MarketDataSource.TOKOCRYPTO
    var selectedQuote by remember(marketDataSource) {
        mutableStateOf(if (isToko) "USDT" else "IDR")
    }

    // K13: kehadiran simbol diperiksa ke repository bursa aktif (fail-closed).
    fun isKnownSymbol(symbol: String): Boolean = when (marketDataSource) {
        MarketDataSource.TOKOCRYPTO -> TokocryptoSymbolRepository.hasSymbol(symbol)
    }

    // Simbol lengkap sesuai kuotasi terpilih (input boleh hanya kode dasar, mis. "SOL").
    val typedSymbol = remember(searchQuery, selectedQuote) {
        val clean = searchQuery.trim().uppercase()
            .replace("/", "").replace("_", "").replace(" ", "")
        if (clean.isBlank()) "" else when {
            clean.endsWith("USDT") || clean.endsWith("IDR") || clean.endsWith("BIDR") ||
                clean.endsWith("USDC") || clean.endsWith("BUSD") -> clean
            else -> "$clean$selectedQuote"
        }
    }
    // Putusan gate untuk isi kolom: dipakai tombol Tambah DAN pesan di bawah kolom, supaya
    // tombol yang mati selalu punya alasan yang terlihat.
    val addVerdict = remember(typedSymbol, marketDataSource) {
        val sanitized = agu.analys.model.PairRules
            .sanitizeSymbolForExchange(typedSymbol, marketDataSource.name)
        WatchlistEntryGate.checkAdd(
            rawSymbol = sanitized ?: typedSymbol,
            source = marketDataSource
        ) { sym -> sanitized != null && isKnownSymbol(sym) }
    }

    val popularBases = listOf(
        "BTC", "ETH", "SOL", "DOGE", "XRP", "PEPE",
        "SUI", "ADA", "BNB", "SHIB", "NEAR", "AVAX",
        "TKO", "RENDER", "FET", "TRX", "LINK", "FLOKI", "BONK"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. WATCHLIST SUMMARY & PRESETS
        SectionHeader("PAKET PRESET WATCHLIST CEPAT (${marketDataSource.label.uppercase()})")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Koin Dipantau:",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                    Box(
                        modifier = Modifier
                            .background(TvBlue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${currentWatchlist.size} Pair Aktif",
                            color = TvBlue,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "Pilih paket preset instan (${if (isToko) "Pair USDT" else "Pair IDR"}):",
                    color = TvTextSecondary,
                    fontSize = 10.5.sp
                )
                Spacer(Modifier.height(8.dp))

                val presets = listOf(
                    Triple("top10", "👑 Top 10 Kripto", Color(0xFFFFB300)),
                    Triple("scalp", "⚡ Scalping Gems", TvGreen),
                    Triple("ai", "🤖 AI & Web3", Color(0xFFAB47BC)),
                    Triple("layer1", "🧱 Layer-1 Top", TvBlue),
                    Triple("default", "🔄 Reset Default (${if (isToko) "BTC/USDT" else "BTC/IDR"})", TvTextSecondary)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { (presetKey, label, color) ->
                        OutlinedButton(
                            onClick = {
                                onApplyPreset(presetKey)
                                Toast.makeText(context, "Preset $label diterapkan!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
                            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 2. TAMBAH PAIR MANUAL / CARI KOIN
        SectionHeader("TAMBAH PAIR MANUAL (${marketDataSource.label.uppercase()})")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Selector Kuotasi Aktif (USDT / IDR)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Pilih Kuotasi Pasar:", color = TvTextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("USDT", "IDR").forEach { q ->
                            val isSelected = selectedQuote == q
                            val qColor = if (q == "USDT") TvCyan else TvBlue
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) qColor.copy(alpha = 0.25f) else TvSurface)
                                    .border(1.dp, if (isSelected) qColor else TvBorder, RoundedCornerShape(6.dp))
                                    .clickable { selectedQuote = q }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = q,
                                    color = if (isSelected) qColor else TvTextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it.uppercase() },
                        placeholder = { Text("Ketik simbol (contoh: SOL, TKO, BTC)", fontSize = 11.sp, color = TvTextSecondary) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, null, tint = TvCyan, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, null, tint = TvTextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvCyan,
                            unfocusedBorderColor = TvBorder,
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            cursorColor = TvCyan
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    )

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = {
                            // Tidak ada fallback "simpan dulu": verdict tolak berarti tidak
                            // masuk watchlist, dengan alasan yang jelas (lihat teks di bawah kolom).
                            if (addVerdict.accepted) {
                                onAddPair(addVerdict.normalized)
                                searchQuery = ""
                                Toast.makeText(context, "${addVerdict.normalized} ditambahkan ke Watchlist", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    addVerdict.message ?: "Simbol tidak dapat ditambahkan.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        enabled = searchQuery.isNotBlank() && addVerdict.accepted,
                        colors = ButtonDefaults.buttonColors(containerColor = TvCyan),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = Color.Black)
                        Spacer(Modifier.width(4.dp))
                        Text("Tambah", color = Color.Black, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (searchQuery.isNotBlank() && !addVerdict.accepted) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = addVerdict.message ?: "",
                        color = TvRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text("Koin Populer Siap Tambah (${selectedQuote}):", color = TvTextSecondary, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))

                // Popular suggestions chips with selectedQuote
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    popularBases.forEach { base ->
                        val sym = "$base$selectedQuote"
                        val chipVerdict = WatchlistEntryGate.checkAdd(sym, marketDataSource) { s ->
                            isKnownSymbol(s)
                        }
                        val isTracked = currentWatchlist.any { it.equals(sym, ignoreCase = true) }
                        val chipAvailable = chipVerdict.accepted
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isTracked) TvGreen.copy(alpha = 0.15f)
                                    else if (!chipAvailable) TvSurface.copy(alpha = 0.4f)
                                    else TvSurface
                                )
                                .border(
                                    1.dp,
                                    if (isTracked) TvGreen.copy(alpha = 0.5f) else if (!chipAvailable) TvBorder.copy(alpha = 0.4f) else TvBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    if (chipAvailable) {
                                        if (isTracked) {
                                            onRemovePair(sym)
                                            Toast.makeText(context, "$sym dihapus dari Watchlist", Toast.LENGTH_SHORT).show()
                                        } else {
                                            onAddPair(chipVerdict.normalized)
                                            Toast.makeText(context, "$sym ditambahkan ke Watchlist", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        // K13: chip yang tidak ada di bursa aktif tetap tampil
                                        // (agar tidak "hilang" misterius) tapi tidak bisa ditambah.
                                        Toast.makeText(
                                            context,
                                            chipVerdict.message ?: "Simbol tidak dapat ditambahkan.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$base/$selectedQuote",
                                    color = if (isTracked) TvGreen else if (!chipAvailable) TvTextSecondary.copy(alpha = 0.5f) else TvTextPrimary,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (isTracked) Icons.Default.Check else Icons.Default.Add,
                                    contentDescription = null,
                                    tint = when {
                                        isTracked -> TvGreen
                                        !chipAvailable -> TvTextSecondary.copy(alpha = 0.5f)
                                        else -> TvTextSecondary
                                    },
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 3. DAFTAR KOIN WATCHLIST SAAT INI
        SectionHeader("DAFTAR PAIR WATCHLIST AKTIF (${currentWatchlist.size})")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (currentWatchlist.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Watchlist kosong. Tambahkan pair di atas.", color = TvTextSecondary, fontSize = 11.5.sp)
                    }
                } else {
                    currentWatchlist.toList().forEach { symbol ->
                        val pair = TradingPair.fromCustomSymbol(symbol, defaultQuote = marketDataSource.defaultQuoteAsset)
                        val tick = dashboardTicks[symbol] ?: dashboardTicks[pair.symbol] ?: dashboardTicks[symbol.lowercase()] ?: dashboardTicks[pair.baseAsset]
                        val priceStr = tick?.let { PriceFormatter.formatPrice(it.price, quoteAsset = pair.quoteAsset) } ?: "-"
                        val change24h = tick?.change24h ?: 0.0
                        val isUp = change24h >= 0
                        val isUsdt = PriceFormatter.isUsdtQuote(pair.quoteAsset)
                        val quoteBadgeColor = if (isUsdt) TvCyan else if (pair.quoteAsset.equals("BIDR", true)) TvAmber else TvBlue

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TvSurface)
                                .border(1.dp, TvBorder.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(quoteBadgeColor.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = pair.baseAsset.take(3),
                                        color = quoteBadgeColor,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${pair.baseAsset} / ${pair.quoteAsset}",
                                            color = TvTextPrimary,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(quoteBadgeColor.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(pair.quoteAsset, color = quoteBadgeColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(
                                        text = priceStr,
                                        color = if (isUsdt) TvGreen else TvTextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (tick != null) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                (if (isUp) TvGreen else TvRed).copy(alpha = 0.15f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = PriceFormatter.formatPercentage(change24h, includePlusSign = true),
                                            color = if (isUp) TvGreen else TvRed,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }

                                IconButton(
                                    onClick = {
                                        onRemovePair(symbol)
                                        Toast.makeText(context, "$symbol dihapus", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Hapus",
                                        tint = TvRed.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
