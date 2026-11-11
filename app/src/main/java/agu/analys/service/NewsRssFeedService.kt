package agu.analys.service

import agu.analys.model.NewsArticle
import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber

/**
 * Service agregasi RSS feed berita crypto global & Indonesia
 * Mengumpulkan headline katalis (upgrade, volume spike, partnership, breakout).
 */
object NewsRssFeedService {
    private val client get() = NetworkClientProvider.rssClient

    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 menit
    private var cachedArticles: List<NewsArticle>? = null
    private var cacheTimestampMs: Long = 0L

    private val RSS_SOURCES = listOf(
        RssSource(
            name = "Decrypt",
            url = "https://decrypt.co/feed"
        ),
        RssSource(
            name = "BeInCrypto",
            url = "https://beincrypto.com/feed/"
        ),
        RssSource(
            name = "The Daily Hodl",
            url = "https://dailyhodl.com/feed/"
        ),
        RssSource(
            name = "Cointelegraph",
            url = "https://cointelegraph.com/rss/tag/market-analysis"
        ),
        RssSource(
            name = "U.Today",
            url = "https://u.today/rss"
        ),
        RssSource(
            name = "CoinDesk",
            url = "https://www.coindesk.com/arc/outboundfeeds/rss/"
        )
    )

    private data class RssSource(val name: String, val url: String)

    suspend fun fetchAggregatedNews(forceRefresh: Boolean = false): List<NewsArticle> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedArticles != null && (now - cacheTimestampMs) < CACHE_TTL_MS) {
            return@withContext cachedArticles.orEmpty()
        }

        val articlesBySource = coroutineScope {
            RSS_SOURCES.map { source ->
                async {
                    runCatching { fetchSingleRss(source) }.getOrElse {
                        Timber.w(it, "Gagal fetch RSS dari ${source.name}")
                        emptyList()
                    }
                }
            }.map { it.await() }
        }

        // Ambil secara berimbang dari setiap sumber (Round-Robin) agar tidak hanya 1 media saja
        val balancedList = mutableListOf<NewsArticle>()
        val maxPerSource = 5
        for (i in 0 until maxPerSource) {
            for (sourceList in articlesBySource) {
                if (i < sourceList.size) {
                    balancedList.add(sourceList[i])
                }
            }
        }

        val cleaned = deduplicateArticles(balancedList)
        if (cleaned.isNotEmpty()) {
            cachedArticles = cleaned
            cacheTimestampMs = now
        }
        cleaned.ifEmpty { cachedArticles.orEmpty() }
    }

    private fun fetchSingleRss(source: RssSource): List<NewsArticle> {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val xml = resp.body?.string().orEmpty()
            return parseRssXml(xml, source.name)
        }
    }

    private const val MAX_NEWS_AGE_MS = 24 * 60 * 60 * 1000L // 24 jam (1 hari) filter fresh

    private val dateFormats = listOf(
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US),
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US),
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        },
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US),
        java.text.SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
    )

    private fun parseRssDateToMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim()
        for (fmt in dateFormats) {
            try {
                val parsed = fmt.parse(clean)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseRssXml(xml: String, sourceName: String): List<NewsArticle> {
        val results = mutableListOf<NewsArticle>()
        val itemRegex = Regex("<item[\\s>]([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("<title><!\\[CDATA\\[(.*?)\\]\\]></title>|<title>(.*?)</title>", RegexOption.IGNORE_CASE)
        val linkRegex = Regex("<link><!\\[CDATA\\[(.*?)\\]\\]></link>|<link>(.*?)</link>", RegexOption.IGNORE_CASE)
        val pubDateRegex = Regex("<pubDate><!\\[CDATA\\[(.*?)\\]\\]></pubDate>|<pubDate>(.*?)</pubDate>", RegexOption.IGNORE_CASE)
        val dcDateRegex = Regex("<dc:date><!\\[CDATA\\[(.*?)\\]\\]></dc:date>|<dc:date>(.*?)</dc:date>", RegexOption.IGNORE_CASE)
        val publishedRegex = Regex("<published><!\\[CDATA\\[(.*?)\\]\\]></published>|<published>(.*?)</published>", RegexOption.IGNORE_CASE)
        val updatedRegex = Regex("<updated><!\\[CDATA\\[(.*?)\\]\\]></updated>|<updated>(.*?)</updated>", RegexOption.IGNORE_CASE)

        val now = System.currentTimeMillis()

        itemRegex.findAll(xml).take(12).forEach { match ->
            val block = match.groupValues.getOrNull(1).orEmpty()
            val tMatch = titleRegex.find(block)
            val lMatch = linkRegex.find(block)

            val rawTitle = tMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: tMatch?.groupValues?.getOrNull(2).orEmpty()
            val rawLink = lMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: lMatch?.groupValues?.getOrNull(2).orEmpty()

            // Ekstrak tanggal terbit artikel
            val rawDate = pubDateRegex.find(block)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: pubDateRegex.find(block)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?: dcDateRegex.find(block)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: dcDateRegex.find(block)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?: publishedRegex.find(block)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: publishedRegex.find(block)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?: updatedRegex.find(block)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: updatedRegex.find(block)?.groupValues?.getOrNull(2).orEmpty()

            val parsedTime = parseRssDateToMs(rawDate)
            
            // FILTER KETAT: Jika berita sudah berusia > 24 jam (1 hari), JANGAN dimasukkan ke screener
            if (parsedTime != null) {
                val ageMs = now - parsedTime
                if (ageMs > MAX_NEWS_AGE_MS) {
                    // Berita basi / lampau (> 1 hari), abaikan demi menjaga kesegaran data screener
                    return@forEach
                }
            }

            val articleTimestamp = when {
                parsedTime == null -> now // fallback jika feed tidak memuat tanggal
                parsedTime > now + 300_000L -> now // toleransi skew jam server
                else -> parsedTime
            }

            val title = cleanTitle(rawTitle)
            if (title.length in 25..220 && !isSpammy(title)) {
                results.add(
                    NewsArticle(
                        title = title,
                        source = sourceName,
                        link = rawLink.trim(),
                        publishedAtMs = articleTimestamp
                    )
                )
            }
        }
        return results
    }

    private fun deduplicateArticles(articles: List<NewsArticle>): List<NewsArticle> {
        val seen = mutableSetOf<String>()
        val output = mutableListOf<NewsArticle>()
        val now = System.currentTimeMillis()

        // Urutkan artikel terbitan paling segar di urutan pertama
        val sortedByFreshness = articles.sortedByDescending { it.publishedAtMs }

        for (art in sortedByFreshness) {
            // Filter tambahan: pastikan berita fresh (< 24 jam)
            if (now - art.publishedAtMs > MAX_NEWS_AGE_MS) {
                continue
            }

            val key = art.title.lowercase().filter { it.isLetterOrDigit() }.take(40)
            if (key.length >= 15 && seen.add(key)) {
                output.add(art)
            }
            if (output.size >= 25) break
        }
        return output
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isSpammy(title: String): Boolean {
        val t = title.lowercase()
        val spamWords = listOf("casino", "slot", "togel", "judi", "porn", "giveaway", "airdrop claim", "free money")
        return spamWords.any { t.contains(it) }
    }
}
