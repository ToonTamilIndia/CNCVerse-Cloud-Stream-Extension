package com.cncverse.daddylive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DaddyLiveProvider : MainAPI() {
    override var mainUrl = "https://thedaddy.li"
    override var name = "DaddyLive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "schedule" to "Schedule",
        "channels" to "📺 24/7 Channels",
    )

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val players = listOf("stream", "cast", "watch", "plus", "casting", "player", "hub")
    private val headers get() = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")

    data class Channel(val id: String, val name: String)

    data class Event(
        val title: String,
        val sport: String,
        val tournament: String,
        val date: Long,
        val channels: List<Channel>,
    ) {
        fun live(now: Long) = date in 1..now && now < date + 10_800_000L
        fun ended(now: Long) = date > 0 && now >= date + 14_400_000L
    }

    private data class Embed(val player: Int, val page: String, val src: String)

    private val lock = Mutex()
    private var scheduleCache: Pair<Long, List<Event>>? = null
    private var channelCache: Pair<Long, List<Event>>? = null

    private suspend fun page(path: String): Document {
        var last: Exception? = null
        repeat(3) {
            try {
                return app.get(mainUrl + path, headers = headers, timeout = 20L).document
            } catch (e: Exception) {
                last = e
            }
        }
        throw ErrorLoadingException("DaddyLive didn't respond, refresh to try again (${last?.message})")
    }

    private suspend fun schedule(): List<Event> = lock.withLock {
        scheduleCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 120_000) return@withLock list }
        val doc = runCatching { page("/") }.getOrElse { e -> return@withLock scheduleCache?.second ?: throw e }
        val london = TimeZone.getTimeZone("Europe/London")

        doc.select("#schedule .schedule__day").flatMap { day ->
            val dayStart = dayStart(day.selectFirst(".schedule__dayTitle")?.text() ?: "", london)
                ?: return@flatMap emptyList()
            day.select(".schedule__category").flatMap { cat ->
                val category = cat.selectFirst(".schedule__catHeader .card__meta")?.text()?.trim() ?: ""
                // Times are UK clock times listed in order; a big jump backwards means the next day.
                var dayOffset = 0L
                var lastMinute = -1
                cat.select(".schedule__event").mapNotNull { ev ->
                    val title = ev.selectFirst(".schedule__eventTitle")?.text()?.trim() ?: ""
                    val hm = (ev.selectFirst(".schedule__time")?.attr("data-time") ?: "").split(":")
                    val hour = hm.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val minute = hour * 60 + (hm.getOrNull(1)?.toIntOrNull() ?: 0)
                    if (lastMinute >= 0 && minute < lastMinute - 360) dayOffset += 86_400_000L
                    lastMinute = minute

                    val channels = ev.select(".schedule__channels a[href*=watch.php?id=]").mapNotNull { a ->
                        val id = a.attr("href").substringAfter("id=")
                            .takeIf { it.isNotEmpty() && it.trim('0').isNotEmpty() } ?: return@mapNotNull null
                        Channel(id, a.attr("title").ifEmpty { a.text() }.trim())
                    }.distinctBy { it.id }
                    if (channels.isEmpty() || title.isEmpty()) return@mapNotNull null

                    Event(
                        displayTitle(title),
                        sportOf(category, title),
                        tournamentOf(category, title),
                        dayStart + dayOffset + minute * 60_000L,
                        channels,
                    )
                }
            }
        }.also { scheduleCache = System.currentTimeMillis() to it }
    }

    private suspend fun channels(): List<Event> = lock.withLock {
        channelCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 21_600_000) return@withLock list }
        val doc = runCatching { page("/24-7-channels.php") }
            .getOrElse { e -> return@withLock channelCache?.second ?: throw e }

        doc.select("a.card[href*=watch.php?id=]").mapNotNull { a ->
            val id = a.attr("href").substringAfter("id=").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = a.selectFirst(".card__title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Event(name, "24/7 Channels", "", 0L, listOf(Channel(id, name)))
        }.distinctBy { it.channels.first().id }
            .also { channelCache = System.currentTimeMillis() to it }
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // "Thursday 13th Nov 2025 - Schedule Time UK GMT" -> midnight of that day in London
    private fun dayStart(title: String, zone: TimeZone): Long? {
        val words = title.substringBefore(" - ").split(' ').filter { it.isNotEmpty() }
        if (words.size < 4) return null
        val day = words[1].filter { it.isDigit() }.toIntOrNull() ?: return null
        val month = months.indexOf(words[2].take(3).lowercase()).takeIf { it >= 0 } ?: return null
        val year = words[3].toIntOrNull() ?: return null
        return Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, 0, 0, 0)
        }.timeInMillis
    }

    private fun displayTitle(t: String): String =
        t.substringBefore(" | ")
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it in "-.,:&'()+/@" }
            .split(' ', '\t', '\n')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { t }

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
        "🎱" to "Snooker",
        "🏸" to "Badminton",
        "🏇" to "Horse Racing",
        "🚴" to "Cycling",
        "🏃" to "Athletics",
        "🤾" to "Handball",
        "📺" to "TV Shows",
    )

    private val sportOrder = listOf(
        "Cricket", "Football", "Basketball", "American Football", "Tennis", "Ice Hockey",
        "Combat Sports", "Motorsport", "Rugby", "Australian Football", "Wrestling", "Golf",
    )

    private fun sportOf(category: String, title: String): String {
        if (category.contains("(AFL)")) return "Australian Football"
        val tagged = if (category == "Upcoming Events") title else category
        sportByEmoji.firstOrNull { tagged.contains(it.first) }?.let { return it.second }
        return displayTitle(category).takeIf { it.isNotEmpty() && it != "Upcoming Events" } ?: "Other"
    }

    private fun tournamentOf(category: String, title: String): String {
        if (category == "Upcoming Events") return ""
        val rest = displayTitle(category).removePrefix(sportOf(category, title)).trim()
        return if (rest.startsWith("All ") && rest.endsWith(" Events")) "" else rest
    }

    private fun sportIcon(sport: String) = when (sport) {
        "Cricket" -> "🏏"
        "Football" -> "⚽"
        "Basketball" -> "🏀"
        "American Football" -> "🏈"
        "Tennis" -> "🎾"
        "Ice Hockey" -> "🏒"
        "Combat Sports" -> "🥊"
        "Motorsport" -> "🏎️"
        "Rugby", "Australian Football" -> "🏉"
        "Wrestling" -> "🤼"
        "Golf" -> "⛳"
        "Volleyball" -> "🏐"
        "Darts" -> "🎯"
        "Snooker" -> "🎱"
        "Badminton" -> "🏸"
        "Horse Racing" -> "🏇"
        "Cycling" -> "🚴"
        "Athletics" -> "🏃"
        "Handball" -> "🤾"
        else -> "📺"
    }

    private fun status(e: Event, now: Long) = when {
        e.date == 0L -> ""
        e.ended(now) -> "✅"
        e.live(now) -> "🔴"
        else -> "🔜"
    }

    private val versusWords = setOf("vs", "vs.", "v", "v.", "versus", "@")

    private fun cardUrl(e: Event, now: Long): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        val words = e.title.split(' ')
        val vs = words.indexOfFirst { it.lowercase() in versusWords }
        val teams = (if (vs > 0) {
            listOf(words.take(vs).joinToString(" "), words.drop(vs + 1).joinToString(" "))
        } else {
            listOf(e.title)
        }).map { it.trim() }.toMutableList()

        var title = if (e.date == 0L) "24/7 Channel" else e.tournament.ifEmpty { e.sport }
        if (teams.size == 2 && teams[0].contains(":")) {
            val event = teams[0].substringBeforeLast(":").trim()
            teams[0] = teams[0].substringAfterLast(":").trim()
            if (e.tournament.isEmpty() || e.tournament.length > 40) title = event
        }
        val time = if (e.date > 0) SimpleDateFormat("MMM dd, yyyy hh:mm a z", Locale.US).format(Date(e.date)) else ""

        return buildString {
            append("https://card.cncverse.dpdns.org/?")
            append("title=${enc(title)}")
            append("&teamA=${enc(teams[0])}")
            if (teams.size == 2 && teams[1].isNotEmpty()) append("&teamB=${enc(teams[1])}")
            if (time.isNotEmpty()) append("&time=${enc(time)}")
            append("&isLive=${e.date == 0L || e.live(now)}")
            append("&isEnded=${e.ended(now)}")
        }
    }

    private fun card(e: Event, now: Long): SearchResponse {
        val s = status(e, now)
        return newLiveSearchResponse(if (s.isEmpty()) e.title else "$s ${e.title}", e.toJson(), TvType.Live) {
            posterUrl = cardUrl(e, now)
        }
    }

    private fun sortKey(now: Long): Comparator<Event> = compareBy(
        { if (it.live(now)) 0 else if (it.ended(now)) 2 else 1 },
        { it.date },
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val now = System.currentTimeMillis()
        if (request.data == "channels") {
            val all = channels()
            val slice = all.drop((page - 1) * 60).take(60)
            return newHomePageResponse(HomePageList(request.name, slice.map { card(it, now) }, true), page * 60 < all.size)
        }
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)

        val rows = schedule()
            .filter { !it.ended(now) }
            .groupBy { it.sport }
            .toList()
            .sortedWith(compareBy({ sportOrder.indexOf(it.first).takeIf { i -> i >= 0 } ?: 99 }, { it.first }))
            .map { (sport, events) ->
                HomePageList("${sportIcon(sport)} $sport", events.sortedWith(sortKey(now)).map { card(it, now) }, true)
            }
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val now = System.currentTimeMillis()
        val events = runCatching { schedule() }.getOrDefault(emptyList())
            .filter { e ->
                !e.ended(now) && (listOf(e.title, e.sport, e.tournament).any { it.contains(query, true) } ||
                    e.channels.any { it.name.contains(query, true) })
            }
            .sortedWith(sortKey(now))
        val chans = runCatching { channels() }.getOrDefault(emptyList())
            .filter { it.title.contains(query, true) }
        return (events + chans).map { card(it, now) }
    }

    override suspend fun load(url: String): LoadResponse {
        val e = parseJson<Event>(url)
        val now = System.currentTimeMillis()
        val plot = buildString {
            append("📌 ${e.sport}\n")
            if (e.tournament.isNotEmpty()) append("🏆 ${e.tournament}\n")
            if (e.date > 0) {
                append("🕐 ${SimpleDateFormat("MMM dd, yyyy HH:mm z", Locale.US).format(Date(e.date))}\n")
            }
            when {
                e.date == 0L -> append("📺 24/7 channel\n")
                e.live(now) -> append("🔴 Live now\n")
                e.ended(now) -> append("✅ Ended\n")
                else -> append("🔜 Upcoming\n")
            }
            append("\n📡 Channels: ${e.channels.size} (${players.size} players each)\n")
            e.channels.forEach { append("• ${it.name}\n") }
        }
        return newLiveStreamLoadResponse(e.title, url, url) {
            posterUrl = cardUrl(e, now)
            this.plot = plot.trimEnd()
            tags = listOf(e.sport)
        }
    }

    private suspend fun embeds(channelId: String): List<Embed> =
        players.withIndex().toList().amap { (i, folder) ->
            runCatching {
                val page = "$mainUrl/$folder/stream-$channelId.php"
                val doc = app.get(page, headers = headers, timeout = 15L).document
                val src = (doc.selectFirst("iframe#thatframe") ?: doc.selectFirst("iframe[src^=http]"))
                    ?.attr("src") ?: ""
                if (src.startsWith("http")) Embed(i + 1, page, src) else null
            }.getOrNull()
        }.filterNotNull().sortedBy { it.player }.distinctBy { it.src }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = SegmentUnwrap.interceptor

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val e = parseJson<Event>(data)
        var found = false
        e.channels.take(4).amap { ch ->
            embeds(ch.id).amap { (player, page, embed) ->
                val stream = sniffStream(embed, "$mainUrl/", hostPage = page) ?: return@amap
                found = true
                val label = if (e.channels.size > 1) "${ch.name} · Player $player" else "Player $player"
                callback(
                    newExtractorLink(name, label, stream.url, ExtractorLinkType.M3U8) {
                        this.referer = stream.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: ""
                        this.headers = stream.headers
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return found
    }
}
