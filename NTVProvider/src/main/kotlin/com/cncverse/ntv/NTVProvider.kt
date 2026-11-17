package com.cncverse.ntv

import android.util.Base64
import android.webkit.WebSettings
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class NTVProvider : MainAPI() {
    override var mainUrl = "https://ntv.st"
    override var name = "NTV"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf("" to "NTV")

    private val servers = linkedMapOf(
        "raptor" to "Raptor",
        "falcon" to "Falcon",
        "phoenix" to "Phoenix",
        "titan" to "Titan",
    )
    private val domains = listOf("https://ntv.st", "https://ntv.cx")
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    data class Source(val server: String, val label: String, val url: String)

    data class Event(
        val title: String,
        val sport: String,
        val tournament: String,
        val date: Long,
        val ends: Long,
        val liveFlag: Boolean,
        val sources: List<Source>,
    ) {
        private fun lengthMs() = if (sport == "Football") 9_000_000L else 14_400_000L

        private fun endAt(): Long {
            val cap = date + lengthMs()
            return if (ends > 0) min(ends, cap) else cap
        }

        fun ended(now: Long) = date > 0 && now >= endAt()

        fun live(now: Long) = !ended(now) && (liveFlag || date in 1..now)
    }

    private class Raw(
        val server: String,
        val title: String,
        val category: String,
        val tournament: String,
        val date: Long,
        val ends: Long,
        val live: Boolean,
        val sources: List<Source>,
    )

    private val cache = mutableMapOf<String, Pair<Long, List<Raw>>>()
    private val locks = servers.keys.associateWith { Mutex() }

    private suspend fun fetch(server: String): List<Raw> = locks.getValue(server).withLock {
        cache[server]?.let { (at, list) ->
            if (System.currentTimeMillis() - at < 60_000) return list
        }
        val json = domains.firstNotNullOfOrNull { base ->
            runCatching {
                JSONObject(
                    app.get(
                        "$base/api/get-matches",
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to "$base/matches/$server",
                            "Accept" to "application/json",
                        ),
                        params = mapOf("server" to server, "type" to "both"),
                        timeout = 12L,
                    ).text
                )
            }.getOrNull()?.takeIf { it.optBoolean("success") }
        } ?: return cache[server]?.second ?: emptyList()

        json.optJSONArray("all").objects().mapNotNull { m ->
            val sources = m.optJSONArray("sources").objects().mapIndexedNotNull { i, s ->
                val url = s.optString("url").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                val label = s.optString("channelName")
                    .ifEmpty { s.optString("source") }
                    .ifEmpty { "Stream ${i + 1}" }
                Source(server, label, url)
            }
            if (sources.isEmpty()) return@mapNotNull null
            Raw(
                server = server,
                title = m.optString("title").trim(),
                category = m.optString("category").trim(),
                tournament = m.optString("tournament").trim(),
                date = m.optLong("date"),
                ends = m.optLong("ends"),
                live = m.optBoolean("live") || m.optString("status") == "live",
                sources = sources,
            )
        }.also { cache[server] = System.currentTimeMillis() to it }
    }

    private val mergeLock = Mutex()
    private var merged: Pair<Long, List<Event>>? = null

    // The same match is listed by several servers; merge them into one event with every source
    private suspend fun events(): List<Event> = mergeLock.withLock {
        merged?.let { (at, list) ->
            if (System.currentTimeMillis() - at < 60_000) return list
        }
        val raws = servers.keys.toList().amap { runCatching { fetch(it) }.getOrElse { emptyList() } }.flatten()

        val buckets = HashMap<String, MutableList<MutableList<Raw>>>()
        val groups = mutableListOf<Pair<String, MutableList<Raw>>>()
        for (r in raws) {
            val sport = sportOf(r)
            val groupsForKey = buckets.getOrPut(matchKey(r.title) + "|" + sport) { mutableListOf() }
            val group = groupsForKey.firstOrNull {
                val f = it.first()
                f.date == 0L || r.date == 0L || abs(f.date - r.date) < 10_800_000
            }
            if (group != null) {
                group.add(r)
            } else {
                val g = mutableListOf(r)
                groupsForKey.add(g)
                groups.add(sport to g)
            }
        }

        groups.map { (sport, g) ->
            val main = g.firstOrNull { it.title.lowercase().contains(" vs ") } ?: g.first()
            Event(
                title = displayTitle(main.title),
                sport = sport,
                tournament = g.map { displayTitle(it.tournament) }.firstOrNull {
                    it.isNotEmpty() && !it.equals("24/7 channel", true) && it != "Upcoming Events"
                } ?: "",
                date = g.map { it.date }.filter { it > 0 }.minOrNull() ?: 0L,
                ends = g.map { it.ends }.maxOrNull() ?: 0L,
                liveFlag = g.any { it.live },
                sources = g.flatMap { it.sources }.distinctBy { it.url },
            )
        }.also { merged = System.currentTimeMillis() to it }
    }

    private fun displayTitle(t: String): String =
        t.substringBefore(" | ")
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it in "-.,:&'()+/@" }
            .split(' ', '\t', '\n')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { t }

    private val versusWords = setOf("vs", "vs.", "v", "v.", "versus", "@")

    private fun matchKey(t: String): String =
        displayTitle(t).lowercase()
            .split(' ')
            .filter { it !in versusWords }
            .joinToString("")
            .filter { it.isLetterOrDigit() }

    private val sportByTag = mapOf(
        "Football" to "Football",
        "Soccer" to "Football",
        "American Football" to "American Football",
        "NFL" to "American Football",
        "NCAA" to "College Sports",
        "Australian Football" to "Australian Football",
        "Basketball" to "Basketball",
        "Cricket" to "Cricket",
        "Tennis" to "Tennis",
        "Golf" to "Golf",
        "Ice Hockey" to "Ice Hockey",
        "Hockey" to "Ice Hockey",
        "Combat Sports" to "Combat Sports",
        "Boxing" to "Combat Sports",
        "UFC" to "Combat Sports",
        "MMA" to "Combat Sports",
        "Wrestling" to "Wrestling",
        "WWE" to "Wrestling",
        "Motorsports" to "Motorsport",
        "Motorsport" to "Motorsport",
        "Moto Sport" to "Motorsport",
        "Rugby" to "Rugby",
        "Darts" to "Darts",
        "Badminton" to "Badminton",
        "24/7 Streams" to "24/7 Channels",
        "Others" to "Other",
    )

    private val sportByEmoji = listOf(
        "⚽" to "Football",
        "🏈" to "American Football",
        "🏉" to "Rugby",
        "🏏" to "Cricket",
        "🏀" to "Basketball",
        "🏒" to "Ice Hockey",
        "🎾" to "Tennis",
        "🥊" to "Combat Sports",
        "🥋" to "Combat Sports",
        "🤼" to "Wrestling",
        "🏎" to "Motorsport",
        "🏍" to "Motorsport",
        "⛳" to "Golf",
        "🏐" to "Volleyball",
        "🎯" to "Darts",
    )

    private val sportOrder = listOf(
        "Cricket", "Football", "Basketball", "American Football", "Tennis", "Ice Hockey",
        "Combat Sports", "Motorsport", "Rugby", "Wrestling", "Golf", "24/7 Channels",
    )

    private fun sportOf(r: Raw): String {
        sportByTag[r.category]?.let { return it }
        if (r.category.contains("(AFL)")) return "Australian Football"
        val tagged = if (r.category == "Upcoming Events") r.title else r.category
        sportByEmoji.firstOrNull { tagged.contains(it.first) }?.let { return it.second }
        return displayTitle(r.category).takeIf { it.isNotEmpty() && it != "Upcoming Events" } ?: "Other"
    }

    private fun sportIcon(sport: String) = when (sport) {
        "Cricket" -> "🏏"
        "Football" -> "⚽"
        "Basketball" -> "🏀"
        "American Football" -> "🏈"
        "Australian Football" -> "🏉"
        "Tennis" -> "🎾"
        "Ice Hockey" -> "🏒"
        "Combat Sports" -> "🥊"
        "Motorsport" -> "🏎️"
        "Rugby" -> "🏉"
        "Wrestling" -> "🤼"
        "Golf" -> "⛳"
        "Volleyball" -> "🏐"
        "Darts" -> "🎯"
        else -> "📺"
    }

    private fun status(e: Event, now: Long) = when {
        e.date == 0L -> ""
        e.ended(now) -> "✅"
        e.live(now) -> "🔴"
        else -> "🔜"
    }

    private fun cardUrl(e: Event, now: Long): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        val words = e.title.split(' ')
        val vs = words.indexOfFirst { it.lowercase() in versusWords }
        val teams = (if (vs > 0) {
            listOf(words.take(vs).joinToString(" "), words.drop(vs + 1).joinToString(" "))
        } else {
            listOf(e.title)
        }).map { it.trim() }.toMutableList()
        var tournament = e.tournament.ifEmpty { e.sport }
        if (teams.size == 2 && teams[0].contains(":")) {
            val event = teams[0].substringBeforeLast(":").trim()
            teams[0] = teams[0].substringAfterLast(":").trim()
            if (e.tournament.isEmpty() || e.tournament.length > 40) tournament = event
        }
        val time = if (e.date > 0) {
            SimpleDateFormat("MMM dd, yyyy hh:mm a z", Locale.US).format(Date(e.date))
        } else {
            ""
        }
        return buildString {
            append("https://card.cncverse.dpdns.org/?")
            append("title=" + enc(tournament))
            append("&teamA=" + enc(teams[0]))
            if (teams.size == 2 && teams[1].isNotEmpty()) append("&teamB=" + enc(teams[1]))
            if (time.isNotEmpty()) append("&time=" + enc(time))
            append("&isLive=" + e.live(now))
            append("&isEnded=" + e.ended(now))
        }
    }

    private fun card(e: Event, now: Long): SearchResponse {
        val s = status(e, now)
        val title = if (s.isEmpty()) e.title else "$s ${e.title}"
        return newLiveSearchResponse(title, e.toJson(), TvType.Live) {
            posterUrl = cardUrl(e, now)
        }
    }

    // Live first, then upcoming, then ended; each by start time
    private fun sortKey(now: Long): Comparator<Event> = compareBy(
        { if (it.live(now)) 0 else if (it.ended(now)) 2 else 1 },
        { it.date },
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val now = System.currentTimeMillis()
        val shown = events().filter {
            !it.ended(now) && (it.date == 0L || it.live(now) || it.date <= now + 86_400_000)
        }
        val rows = shown.groupBy { it.sport }.toList()
            .sortedWith(compareBy({ sportOrder.indexOf(it.first).takeIf { i -> i >= 0 } ?: 99 }, { it.first }))
            .map { (sport, list) ->
                HomePageList("${sportIcon(sport)} $sport", list.sortedWith(sortKey(now)).map { card(it, now) }, true)
            }
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val now = System.currentTimeMillis()
        return events()
            .filter { e ->
                !e.ended(now) && listOf(e.title, e.sport, e.tournament).any { it.contains(query, true) }
            }
            .sortedWith(sortKey(now))
            .map { card(it, now) }
    }

    override suspend fun load(url: String): LoadResponse {
        val e = parseJson<Event>(url)
        val now = System.currentTimeMillis()
        val plot = buildString {
            append("📌 ${e.sport}\n")
            if (e.tournament.isNotEmpty()) append("🏆 ${e.tournament}\n")
            if (e.date > 0) {
                append("🕐 " + SimpleDateFormat("MMM dd, yyyy HH:mm z", Locale.US).format(Date(e.date)) + "\n")
            }
            when {
                e.date == 0L -> Unit
                e.live(now) -> append("🔴 Live now\n")
                e.ended(now) -> append("✅ Ended\n")
                else -> append("🔜 Upcoming\n")
            }
            append("\n📡 Available Servers: ${e.sources.size}")
            val perServer = e.sources.groupingBy { it.server }.eachCount()
            append(" (" + perServer.entries.joinToString(", ") { (s, n) -> "${servers[s] ?: s} $n" } + ")")
        }
        return newLiveStreamLoadResponse(e.title, url, url) {
            this.posterUrl = cardUrl(e, now)
            this.plot = plot
            this.tags = listOf(e.sport)
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return SegmentUnwrap.interceptor
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val e = parseJson<Event>(data)
        var found = false
        e.sources.amap { src ->
            val label = "${servers[src.server] ?: src.server} · ${src.label}"
            val stream = when {
                src.url.contains("cdnlivetv.") -> cdnLiveTv(src.url) ?: sniffStream(src.url, "$mainUrl/")
                src.url.contains("livelive24.") -> falcon(src.url)
                else -> sniffStream(src.url, "$mainUrl/")
            } ?: return@amap
            found = true
            callback(
                newExtractorLink(name, label, stream.url, ExtractorLinkType.M3U8) {
                    this.referer = stream.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: ""
                    this.headers = stream.headers
                    this.quality = if (src.url.contains("540p")) Qualities.P480.value else Qualities.Unknown.value
                }
            )
        }
        return found
    }

    // livelive24 wraps an embedsports.me player that only starts inside a WebView with its service worker
    private suspend fun falcon(pageUrl: String): Sniffed? {
        val slug = pageUrl.substringAfter("url=", "").substringBefore('&')
        if (slug.isEmpty()) return null
        val embed = "https://embedsports.me/a/$slug"
        val webUa = runCatching { WebSettings.getDefaultUserAgent(snifferContext?.get()) }.getOrNull() ?: ua
        var html: String? = null
        for (attempt in 0 until 6) {
            html = runCatching {
                app.get(
                    embed,
                    headers = mapOf("User-Agent" to webUa, "Referer" to "https://livelive24.st/"),
                    cacheTime = 0,
                    timeout = 8L,
                ).text
            }.getOrNull()
            if (html != null) break
            delay((attempt + 1) * 400L)
        }
        if (html != null && !html.contains("embed.min.js") && !html.contains("data-play=")) return null
        return if (html != null) {
            val served = html.replace("sandDetect();", "")
            sniffStream(embed, pageUrl, 60_000L, pageUrl, served, ::falconAutostart, true)
        } else {
            sniffStream(embed, pageUrl, 60_000L, pageUrl, rewrite = ::falconAutostart, serviceWorker = true)
        }
    }

    private fun falconAutostart(url: String): ((String) -> String)? {
        if (url.contains("fallafar.") && url.contains("/sd0embed/")) {
            return { page -> page.replace("autostart:!1", "autostart:!0").replace("mute:!1", "mute:!0") }
        }
        return null
    }

    // cdnlivetv pages build the playlist URL from base64 fragments joined by a decoder function
    private suspend fun cdnLiveTv(pageUrl: String): Sniffed? = runCatching {
        val html = app.get(pageUrl, headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")).text
        val fn = Regex("""function (\w+)\(s\)\{s=s\.replace\(/-/g""").find(html)?.groupValues?.get(1)
            ?: return@runCatching null
        val parts = Regex("""var (\w+)='([^']*)';""").findAll(html)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val expr = Regex("""var \w+=((?:$fn\(\w+\)\+?)+);""").find(html)?.groupValues?.get(1)
            ?: return@runCatching null
        val url = Regex("""$fn\((\w+)\)""").findAll(expr).joinToString("") { m ->
            val b = (parts[m.groupValues[1]] ?: "").replace('-', '+').replace('_', '/')
            String(Base64.decode(b + "=".repeat((4 - b.length % 4) % 4), Base64.DEFAULT), Charsets.UTF_8)
        }
        if (!url.startsWith("http") || !url.contains(".m3u8")) return@runCatching null
        val origin = Regex("https?://[^/]+").find(pageUrl)!!.value
        Sniffed(url, mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin))
    }.getOrNull()
}

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
