package agu.analys.service

import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Headline gratis, tanpa API key.
 * Prioritas: Google News locale Indonesia → fallback EN → freenewsapi.
 * AI wajib menerjemahkan ke Bahasa Indonesia di output.
 */
object CryptoHeadlineService {
    private val client get() = NetworkClientProvider.rssClient

    private const val CACHE_TTL_MS = 12 * 60 * 1000L
    private const val MAX_HEADLINES = 3

    data class Snapshot(
        val headlines: List<String>,
        val fearGreedLabel: String?,
        val fetchedAtMs: Long = System.currentTimeMillis()
    ) {
        fun promptBlock(): String {
            val lines = buildList {
                if (headlines.isNotEmpty()) {
                    add("Headline publik terbaru (sajikan intisari terjemahan dalam Bahasa Indonesia di jawaban):")
                    headlines.forEachIndexed { i, h -> add("${i + 1}. $h") }
                } else {
                    add("Headline: tidak tersedia saat ini.")
                }
                fearGreedLabel?.let { add("Indeks Fear & Greed market: $it") }
            }
            return lines.joinToString("\n")
        }
    }

    private data class CacheEntry(val snapshot: Snapshot, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    @Volatile private var fearGreedCache: Pair<String, Long>? = null

    suspend fun snapshotForBase(base: String): Snapshot = withContext(Dispatchers.IO) {
        val key = base.lowercase().trim().ifBlank { "btc" }
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return@withContext it.snapshot }

        coroutineScope {
            val headlinesDeferred = async { fetchHeadlines(key) }
            val fgDeferred = async { fetchFearGreed() }
            val headlines = headlinesDeferred.await()
            val fg = fgDeferred.await()
            val snap = Snapshot(headlines = headlines, fearGreedLabel = fg)
            cache[key] = CacheEntry(snap, now + CACHE_TTL_MS)
            snap
        }
    }

    private fun fetchHeadlines(base: String): List<String> {
        val queryName = displayName(base)
        val symbol = base.uppercase()

        // 1) Google News Indonesia dulu
        val qId = URLEncoder.encode("$queryName OR $symbol kripto OR crypto", "UTF-8")
        val idNews = fetchGoogleNewsRss(qId, localeId = true)
        if (idNews.isNotEmpty()) return idNews.take(MAX_HEADLINES)

        // 2) Google News EN (AI yang translate)
        val qEn = URLEncoder.encode("$queryName OR $symbol crypto", "UTF-8")
        val enNews = fetchGoogleNewsRss(qEn, localeId = false)
        if (enNews.isNotEmpty()) return enNews.take(MAX_HEADLINES)

        // 3) Fallback freenewsapi
        return fetchFreeNewsApi("$queryName crypto OR $symbol").take(MAX_HEADLINES)
    }

    private fun fetchGoogleNewsRss(encodedQuery: String, localeId: Boolean): List<String> {
        val locale = if (localeId) {
            "hl=id&gl=ID&ceid=ID:id"
        } else {
            "hl=en-US&gl=US&ceid=US:en"
        }
        val url = "https://news.google.com/rss/search?q=$encodedQuery&$locale"
        return runCatching {
            val body = httpGet(url) ?: return emptyList()
            parseRssTitles(body)
                .map { cleanTitle(it) }
                .filter { it.length in 20..180 }
                .distinct()
                .take(MAX_HEADLINES)
        }.getOrElse { emptyList() }
    }

    private fun fetchFreeNewsApi(query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://freenewsapi.ai/v1/search?q=$q&size=8"
        return runCatching {
            val body = httpGet(url) ?: return emptyList()
            val root = JSONObject(body)
            val arr = root.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title").orEmpty().trim()
                val host = obj.optString("host").orEmpty()
                if (title.length < 25) continue
                if (looksSpammy(title, host)) continue
                out.add(if (host.isNotBlank()) "$title — $host" else title)
                if (out.size >= MAX_HEADLINES) break
            }
            out
        }.getOrElse { emptyList() }
    }

    private fun fetchFearGreed(): String? {
        val now = System.currentTimeMillis()
        fearGreedCache?.let { (label, exp) -> if (exp > now) return label }

        return runCatching {
            val body = httpGet("https://api.alternative.me/fng/?limit=1") ?: return null
            val data = JSONObject(body).optJSONArray("data")?.optJSONObject(0) ?: return null
            val value = data.optString("value", "?")
            val clsEn = data.optString("value_classification", "?")
            val clsId = when (clsEn.lowercase()) {
                "extreme fear" -> "Ketakutan ekstrem"
                "fear" -> "Takut"
                "neutral" -> "Netral"
                "greed" -> "Serakah"
                "extreme greed" -> "Keserakahan ekstrem"
                else -> clsEn
            }
            val label = "$value ($clsId)"
            fearGreedCache = label to (now + CACHE_TTL_MS)
            label
        }.getOrNull()
    }

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AnalysPasar/2.2.1 (Android; headline-assist)")
            .header("Accept", "application/json, application/rss+xml, text/xml, */*")
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.5")
            .get()
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }

    private fun parseRssTitles(xml: String): List<String> {
        val titles = mutableListOf<String>()
        val itemRegex = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("<title><!\\[CDATA\\[(.*?)\\]\\]></title>|<title>(.*?)</title>", RegexOption.IGNORE_CASE)
        itemRegex.findAll(xml).forEach { itemMatch ->
            val block = itemMatch.groupValues.getOrNull(1).orEmpty()
            val t = titleRegex.find(block) ?: return@forEach
            val raw = t.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: t.groupValues.getOrNull(2).orEmpty()
            if (raw.isNotBlank()) titles.add(raw)
        }
        return titles
    }

    private fun cleanTitle(raw: String): String =
        raw
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

    private fun looksSpammy(title: String, host: String): Boolean {
        val t = title.lowercase()
        val h = host.lowercase()
        if (h.contains("casino") || h.contains("betting") || h.contains("porn")) return true
        if (t.contains("sic bo") || t.contains("casino") || t.contains("slot")) return true
        return false
    }

    private fun displayName(base: String): String = when (base.lowercase()) {
        "btc" -> "Bitcoin"
        "eth" -> "Ethereum"
        "sol" -> "Solana"
        "xrp" -> "XRP"
        "bnb" -> "BNB"
        "ada" -> "Cardano"
        "doge" -> "Dogecoin"
        "shib" -> "Shiba"
        "dot" -> "Polkadot"
        "avax" -> "Avalanche"
        "matic", "pol" -> "Polygon"
        "link" -> "Chainlink"
        "near" -> "NEAR"
        "sui" -> "Sui"
        "apt" -> "Aptos"
        "pepe" -> "PEPE"
        "trx" -> "TRON"
        else -> base.uppercase()
    }
}
