package com.cncverse

import android.content.Context
import android.provider.Settings
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom

class EasyPlayProvider : MainAPI() {
    companion object {
        var context: Context? = null

        private val API_HOSTS = listOf("https://easyapi.sh", "https://popcornflix.app")
        private const val ACQUIRE_PATH = "/live-tv/api/v1/acquire"
        private const val APP_UA = "okhttp/5.3.0"
        private const val PLAYER_UA = "VLC/3.0.20 LibVLC/3.0.20"
        private const val PREFS = "EasyPlay"
        private const val KEY_TOKEN = "live_tv_token"
        private const val KEY_DEVICE = "live_tv_device"
        private const val LEASE_TTL_MS = 240_000L
        private const val CATALOG_TTL_MS = 600_000L
        private const val ROW_PAGE_SIZE = 60

        private const val ROW_ARABIC = "Arabic Networks"
        private const val ROW_247 = "24/7 Channels"
        private const val ROW_VIP = "VIP & Events"
        private const val ROW_OTHER = "Other"

        // Longest prefix first so "EXYU CROATIA" wins over "EXYU".
        private val COUNTRY_PREFIXES: List<Pair<String, String>> = listOf(
            "INDIA" to "India",
            "PAKISTAN" to "Pakistan",
            "BANGLADESH" to "Bangladesh",
            "SRI LANKA" to "Sri Lanka",
            "USA" to "United States",
            "UK" to "United Kingdom",
            "CANADA" to "Canada",
            "IRELAND" to "Ireland",
            "AUSTRALIA" to "Australia",
            "NEW ZEALAND" to "New Zealand",
            "FRANCE" to "France",
            "GERMANY" to "Germany",
            "SPAIN" to "Spain",
            "ITALY" to "Italy",
            "PORTUGAL" to "Portugal",
            "NETHERLANDS" to "Netherlands",
            "BELGIUM" to "Belgium",
            "SWITZERLAND" to "Switzerland",
            "AUSTRIA" to "Austria",
            "MALTA" to "Malta",
            "SWEDEN" to "Sweden",
            "DENMARK" to "Denmark",
            "NORWAY" to "Norway",
            "FINLAND" to "Finland",
            "ICELAND" to "Iceland",
            "SCANDINAVE" to "Scandinavia",
            "POLAND" to "Poland",
            "ROMANIA" to "Romania",
            "HUNGARY" to "Hungary",
            "BULGARIA" to "Bulgaria",
            "GREECE" to "Greece",
            "CYPRUS" to "Cyprus",
            "CZECH" to "Czechia",
            "SLOVAKIA" to "Slovakia",
            "RUSSIA" to "Russia",
            "UKRAINE" to "Ukraine",
            "LITHUANIA" to "Lithuania",
            "LATVIA" to "Latvia",
            "ESTONIA" to "Estonia",
            "GEORGIA" to "Georgia",
            "ALBANIA" to "Albania",
            "EXYU" to "Ex-Yugoslavia",
            "EXYU BOSNIA" to "Bosnia & Herzegovina",
            "EXYU CROATIA" to "Croatia",
            "EXYU MACEDONIA" to "North Macedonia",
            "EXYU SLOVENIA" to "Slovenia",
            "EXYU SRBIJA" to "Serbia",
            "EXYU PINK" to "Serbia",
            "TURK" to "Turkey",
            "KURDISH" to "Kurdish",
            "EZIDXAN" to "Kurdish",
            "AZERBAIJAN" to "Azerbaijan",
            "ARMENIA" to "Armenia",
            "IRAN" to "Iran",
            "ISRAËL" to "Israel",
            "ISRAEL" to "Israel",
            "AFGHANISTAN" to "Afghanistan",
            "KAZAKHSTAN" to "Kazakhstan",
            "JAPAN" to "Japan",
            "KOREA" to "Korea",
            "CHINA" to "China",
            "TAIWAN" to "Taiwan",
            "INDONESIA" to "Indonesia",
            "MALAYSIA" to "Malaysia",
            "PHILIPPINE" to "Philippines",
            "VIETNAM" to "Vietnam",
            "SINGAPORE" to "Singapore",
            "THAILLAND" to "Thailand",
            "THAILAND" to "Thailand",
            "CAMBODIA" to "Cambodia",
            "MEXICO" to "Mexico",
            "LATINO" to "Latino",
            "CARIBBEAN" to "Caribbean",
            "PANAMA" to "Panama",
            "ARGENTINA" to "Argentina",
            "BOLIVIA" to "Bolivia",
            "BRAZIL" to "Brazil",
            "CHILE" to "Chile",
            "COLOMBIA" to "Colombia",
            "ECUADOR" to "Ecuador",
            "EL SALVADOR" to "El Salvador",
            "COSTA RICA" to "Costa Rica",
            "HONDURAS" to "Honduras",
            "PARAGUAY" to "Paraguay",
            "PERU" to "Peru",
            "URUGUAY" to "Uruguay",
            "VENEZUELA" to "Venezuela",
            "SURINAME" to "Suriname",
            "HAITI" to "Haiti",
            "DOMINICAN" to "Dominican Republic",
            "SAUDI ARABIA" to "Saudi Arabia",
            "EMIRATES" to "UAE",
            "QATAR" to "Qatar",
            "KUWAIT" to "Kuwait",
            "BAHRAIN" to "Bahrain",
            "OMAN" to "Oman",
            "YEMEN" to "Yemen",
            "IRAQ" to "Iraq",
            "JORDAN" to "Jordan",
            "LEBANON" to "Lebanon",
            "SYRIA" to "Syria",
            "PALESTINE" to "Palestine",
            "EGYPT" to "Egypt",
            "LIBYA" to "Libya",
            "TUNISIA" to "Tunisia",
            "ALGERIA" to "Algeria",
            "MOROCCO" to "Morocco",
            "MAURITANIA" to "Mauritania",
            "SUDAN" to "Sudan",
            "NIGERIA" to "Nigeria",
            "GHANA" to "Ghana",
            "KENYA" to "Kenya",
            "UGANDA" to "Uganda",
            "ETHIOPIA" to "Ethiopia",
            "SOMALIA" to "Somalia",
            "SENEGAL" to "Senegal",
            "CAMEROON" to "Cameroon",
            "CONGO" to "Congo",
            "AFRICA" to "Africa",
            "DSTV" to "Africa",
            "ARAB" to ROW_ARABIC,
            "ALWAN" to ROW_ARABIC,
            "BEIN" to ROW_ARABIC,
            "STARZPLAY" to ROW_ARABIC,
            "OSN" to ROW_ARABIC,
            "ART" to ROW_ARABIC,
            "MBC" to ROW_ARABIC,
            "ROTANA" to ROW_ARABIC,
            "ISLAMIC" to ROW_ARABIC,
            "LOCAL" to ROW_ARABIC,
            "24/7" to ROW_247,
            "VIP" to ROW_VIP,
            "BAB" to ROW_VIP,
        ).sortedByDescending { it.first.length }

        private val ROW_ORDER: List<String> = run {
            val pinned = listOf("India", "United States", "United Kingdom", "Canada")
            val tail = listOf(ROW_ARABIC, ROW_247, ROW_VIP, ROW_OTHER)
            val rest = COUNTRY_PREFIXES.map { it.second }.distinct()
                .filter { it !in pinned && it !in tail }
                .sorted()
            pinned + rest + tail
        }

        // Channel-name prefixes ("IN| Star Plus") that override the category row.
        private val CHANNEL_PREFIX_ROWS = mapOf(
            "IN" to "India",
            "ASIA-IN" to "India",
            "UK-IN" to "India",
            "24/7-IN" to "India",
            "BAB" to "India",
        )

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun rowOf(channel: EasyChannel): String {
            val name = channel.name
            if ('|' in name) {
                CHANNEL_PREFIX_ROWS[name.substringBefore('|').trim().uppercase()]?.let { return it }
            }
            return countryOf(channel.category)
        }

        fun isSeparator(name: String): Boolean = name.contains("✦●✦") || name.contains("◆◆◆")

        fun countryOf(category: String): String {
            val n = category.uppercase().trim()
            for ((prefix, row) in COUNTRY_PREFIXES) {
                if (n == prefix || (n.startsWith(prefix) && !n[prefix.length].isLetter())) return row
            }
            return ROW_OTHER
        }
    }

    override var mainUrl = "https://easyapi.sh"
    override var name = "EasyPlay Live TV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(*ROW_ORDER.map { it to it }.toTypedArray())

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun identity(): Pair<String, String> {
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existingToken = prefs?.getString(KEY_TOKEN, null)
        val existingDevice = prefs?.getString(KEY_DEVICE, null)
        if (!existingToken.isNullOrBlank() && !existingDevice.isNullOrBlank()) {
            return existingToken to existingDevice
        }

        val token = randomHex(32)
        val androidId = try {
            context?.contentResolver?.let { Settings.Secure.getString(it, Settings.Secure.ANDROID_ID) }
        } catch (e: Exception) {
            null
        }
        val hex = androidId?.lowercase()?.filter { it in "0123456789abcdef" }
        val device = when {
            hex.isNullOrBlank() -> token.take(16)
            hex.length < 16 -> hex.padStart(16, '0')
            else -> hex.take(16)
        }
        prefs?.edit()?.putString(KEY_TOKEN, token)?.putString(KEY_DEVICE, device)?.apply()
        return token to device
    }

    private fun identityHeaders(): Map<String, String> {
        val (token, device) = identity()
        return mapOf(
            "X-Installation-Token" to token,
            "X-Device-Id" to device,
            "Content-Type" to "application/json",
            "User-Agent" to APP_UA,
        )
    }

    data class Lease(
        val base: String,
        val username: String,
        val password: String,
        val output: String,
        val expiresAtMs: Long,
    )

    private data class AcquireResponse(
        val playlist_url: String? = null,
        val expires_in: Double? = null,
        val state: String? = null,
    )

    @Volatile
    private var cachedLease: Lease? = null

    private fun parseLease(playlistUrl: String): Lease? {
        val rawBase = playlistUrl.substringBefore("get.php").takeIf { it.isNotBlank() } ?: return null
        if (!rawBase.startsWith("http")) return null
        val base = Regex("^https://", RegexOption.IGNORE_CASE).replaceFirst(rawBase, "http://")
        val query = playlistUrl.substringAfter("?", "")
        if (query.isBlank()) return null
        val params = query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val username = params["username"] ?: return null
        val password = params["password"] ?: return null
        val output = params["output"]?.takeIf { it.isNotBlank() } ?: "m3u8"
        return Lease(base, username, password, output, System.currentTimeMillis() + LEASE_TTL_MS)
    }

    private suspend fun acquire(forceFresh: Boolean = false): Lease? {
        if (!forceFresh) {
            cachedLease?.let { if (it.expiresAtMs > System.currentTimeMillis()) return it }
        }
        val headers = identityHeaders()
        val body = "{\"takeover\":true}".toRequestBody(JSON_MEDIA)
        for (host in API_HOSTS) {
            try {
                val resp = app.post("$host$ACQUIRE_PATH", headers = headers, requestBody = body, timeout = 20L)
                // 409 means another session holds the slot on this host; try the next one.
                if (resp.code == 409 || !resp.isSuccessful) continue
                val playlistUrl = parseJson<AcquireResponse>(resp.text).playlist_url
                    ?.takeIf { it.isNotBlank() } ?: continue
                val lease = parseLease(playlistUrl) ?: continue
                cachedLease = lease
                return lease
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    // Takes over a lease for the stitcher's own identity and returns that lease's panel .ts URL.
    private fun panelTsAs(sid: String, identity: Pair<String, String>): String? = runBlocking {
        val headers = mapOf(
            "X-Installation-Token" to identity.first,
            "X-Device-Id" to identity.second,
            "Content-Type" to "application/json",
            "User-Agent" to APP_UA,
        )
        for (host in API_HOSTS) {
            try {
                val body = "{\"takeover\":true}".toRequestBody(JSON_MEDIA)
                val resp = app.post("$host$ACQUIRE_PATH", headers = headers, requestBody = body, timeout = 15L)
                if (!resp.isSuccessful) continue
                val playlistUrl = parseJson<AcquireResponse>(resp.text).playlist_url
                    ?.takeIf { it.isNotBlank() } ?: continue
                val lease = parseLease(playlistUrl) ?: continue
                return@runBlocking "${lease.base}live/${lease.username}/${lease.password}/$sid.ts"
            } catch (e: Exception) {
                continue
            }
        }
        null
    }

    data class EasyChannel(
        val streamId: String,
        val name: String,
        val logo: String,
        val category: String,
    )

    private data class CategoryGroup(
        val name: String,
        val channels: List<EasyChannel>,
    )

    private data class XtreamCategory(
        val category_id: String? = null,
        val category_name: String? = null,
    )

    private data class XtreamStream(
        val stream_id: Long? = null,
        val name: String? = null,
        val category_id: String? = null,
        val stream_icon: String? = null,
    )

    @Volatile
    private var cachedCatalog: List<CategoryGroup>? = null

    @Volatile
    private var catalogAtMs = 0L

    @Volatile
    private var cachedByCountry: Map<String, List<EasyChannel>> = emptyMap()

    private val catalogLock = Mutex()

    private fun cleanCategory(raw: String): String {
        var s = raw
        for (ch in listOf("✦", "●", "◆", "★", "◉", "•")) s = s.replace(ch, " ")
        return Regex("\\s{2,}").replace(s, " ").trim().trim('|').trim()
            .ifBlank { raw.trim().ifBlank { "Live" } }
    }

    private suspend fun getCatalog(forceFresh: Boolean = false): List<CategoryGroup> = catalogLock.withLock {
        if (!forceFresh) {
            val cached = cachedCatalog
            if (cached != null && System.currentTimeMillis() - catalogAtMs < CATALOG_TTL_MS) {
                return@withLock cached
            }
        }
        val lease = acquire(forceFresh) ?: return@withLock cachedCatalog ?: emptyList()
        val groups = buildFromPlayerApi(lease) ?: buildFromM3U(lease) ?: emptyList()
        if (groups.isNotEmpty()) {
            cachedCatalog = groups
            cachedByCountry = groups.flatMap { it.channels }
                .groupBy { rowOf(it) }
                .mapValues { (_, chs) ->
                    chs.sortedBy { if (it.name.trimStart().startsWith("24/7")) 1 else 0 }
                }
            catalogAtMs = System.currentTimeMillis()
        }
        groups.ifEmpty { cachedCatalog ?: emptyList() }
    }

    private fun apiHeaders(): Map<String, String> = mapOf("User-Agent" to PLAYER_UA)

    private suspend fun buildFromPlayerApi(lease: Lease): List<CategoryGroup>? {
        return try {
            val u = lease.username
            val p = lease.password
            val catUrl = "${lease.base}player_api.php?username=$u&password=$p&action=get_live_categories"
            val strUrl = "${lease.base}player_api.php?username=$u&password=$p&action=get_live_streams"

            val catResp = app.get(catUrl, headers = apiHeaders(), timeout = 25L)
            if (!catResp.isSuccessful) return null
            val categories = catResp.body.byteStream().use { mapper.readValue<List<XtreamCategory>>(it) }

            val strResp = app.get(strUrl, headers = apiHeaders(), timeout = 30L)
            if (!strResp.isSuccessful) return null
            val streams = strResp.body.byteStream().use { mapper.readValue<List<XtreamStream>>(it) }
            if (streams.isEmpty()) return null

            val catNames = categories.associate { (it.category_id ?: "") to (it.category_name ?: "") }
            val byCat = LinkedHashMap<String, MutableList<EasyChannel>>()
            categories.forEach { c -> c.category_id?.let { byCat.getOrPut(it) { mutableListOf() } } }

            val idRe = Regex("\\d{1,30}")
            for (s in streams) {
                val catId = s.category_id ?: continue
                val sid = s.stream_id?.toString() ?: continue
                if (!idRe.matches(sid)) continue
                val title = s.name?.trim()?.takeIf { it.isNotBlank() } ?: "Channel $sid"
                if (isSeparator(title)) continue
                val catName = cleanCategory(catNames[catId] ?: "Live")
                byCat.getOrPut(catId) { mutableListOf() }
                    .add(EasyChannel(sid, title, s.stream_icon?.trim() ?: "", catName))
            }

            byCat.entries
                .filter { it.value.isNotEmpty() }
                .map { CategoryGroup(cleanCategory(catNames[it.key] ?: "Live"), it.value) }
                .ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun buildFromM3U(lease: Lease): List<CategoryGroup>? {
        return try {
            val u = lease.username
            val p = lease.password
            val m3uUrl = "${lease.base}get.php?username=$u&password=$p&type=m3u_plus&output=${lease.output}"
            val resp = app.get(m3uUrl, headers = apiHeaders(), timeout = 40L)
            if (!resp.isSuccessful) return null

            val groups = LinkedHashMap<String, MutableList<EasyChannel>>()
            val groupRe = Regex("group-title=\"([^\"]*)\"")
            val logoRe = Regex("tvg-logo=\"([^\"]*)\"")
            val sidRe = Regex("/(\\d{1,30})\\.(?:m3u8|ts)(?:\\?|$)")
            var autoId = 0
            var pendingInf: String? = null

            resp.body.byteStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("#EXTINF")) {
                        pendingInf = line
                        continue
                    }
                    if (line.startsWith("#")) continue
                    val inf = pendingInf ?: continue
                    pendingInf = null

                    val group = groupRe.find(inf)?.groupValues?.get(1)?.trim() ?: ""
                    val logo = logoRe.find(inf)?.groupValues?.get(1)?.trim() ?: ""
                    val title = inf.substringAfterLast(",").trim()
                    if (isSeparator(title)) continue

                    val sid = sidRe.find(line)?.groupValues?.get(1) ?: (autoId++).toString()
                    val catName = cleanCategory(group.ifBlank { "Live" })
                    groups.getOrPut(catName) { mutableListOf() }
                        .add(EasyChannel(sid, title.ifBlank { "Channel $sid" }, logo, catName))
                }
            }

            groups.entries
                .filter { it.value.isNotEmpty() }
                .map { CategoryGroup(it.key, it.value) }
                .ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun EasyChannel.toSearchResponse(): SearchResponse {
        val ch = this
        return newLiveSearchResponse(ch.name, ch.toJson(), TvType.Live) {
            this.posterUrl = ch.logo.ifBlank { null }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        getCatalog()
        val channels = cachedByCountry[request.data] ?: emptyList()
        val start = (page - 1) * ROW_PAGE_SIZE
        val slice = channels.drop(start).take(ROW_PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(request.name, slice.map { it.toSearchResponse() }, isHorizontalImages = true),
            hasNext = start + ROW_PAGE_SIZE < channels.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getCatalog().asSequence()
            .flatMap { it.channels.asSequence() }
            .filter { it.name.contains(query, ignoreCase = true) }
            .distinctBy { it.streamId }
            .take(100)
            .map { it.toSearchResponse() }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val ch = parseJson<EasyChannel>(url)
        return newLiveStreamLoadResponse(ch.name, url, url) {
            this.posterUrl = ch.logo.ifBlank { null }
            this.plot = ch.category
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = TsStitcher.interceptor

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ch = parseJson<EasyChannel>(data)
        TsStitcher.userAgent = PLAYER_UA
        TsStitcher.panelUrl = { sid, identity -> panelTsAs(sid, identity) }
        // Every play takes over a fresh lease; fall back to the cached one if that fails.
        val lease = acquire(forceFresh = true) ?: acquire() ?: return true
        val base = "${lease.base}live/${lease.username}/${lease.password}/${ch.streamId}"
        callback(
            newExtractorLink(this.name, "${ch.name} (Live)", "$base.ts", ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to PLAYER_UA, TsStitcher.STITCH_HEADER to "1")
            }
        )
        callback(
            newExtractorLink(this.name, "${ch.name} (HLS)", "$base.m3u8", ExtractorLinkType.M3U8) {
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to PLAYER_UA)
            }
        )
        return true
    }
}
