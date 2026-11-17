package com.cncverse.rtally

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class Shikshakdaak : HubCloud() {
    override var mainUrl: String = "https://shikshakdaak.com"
}

class Filesdl : ExtractorApi() {
    override val name            = "Filesdl"
    override val mainUrl         = "https://new6.filesdl.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit) {
        val res = app.get(url).document
        val titleText = res.select("div.title").text()
        val qualityRegex = Regex("(\\d{3,4}p)", RegexOption.IGNORE_CASE)
        val quality = qualityRegex.find(titleText)?.value ?: "Unknown"
        res.select("div.container a").map {
            val source = it.text()
            val href = it.attr("href")
            when {

                source.contains("Hubcloud", ignoreCase = true) -> HubCloud().getUrl(href,"Filmyfiy",subtitleCallback,callback)

                source.contains("GDFLIX", ignoreCase = true) -> GDFlix().getUrl(href,"Filmyfiy",subtitleCallback,callback)

                source.contains("Gofile", ignoreCase = true) -> Gofile().getUrl(href,"Filmyfiy",subtitleCallback,callback)

                source.contains("Direct Download", ignoreCase = true) || source.contains("Ultra FastDL", ignoreCase = true) || source.contains("Fast Cloud-02", ignoreCase = true) -> {
                    val response = app.get(href, allowRedirects = false)
                    val redirectUrl = response.headers["location"] ?: href
                    callback(
                        newExtractorLink(
                            "Direct Download",
                            "Filmyfiy [Direct Download]",
                            redirectUrl,
                            INFER_TYPE
                        ) {
                            this.quality = getQualityFromName(quality)
                        }
                    )
                }

                source.contains("Fast Cloud", ignoreCase = true) -> {
                    callback(
                        newExtractorLink(
                            "Fast Cloud",
                            "Filmyfiy [Fast Cloud]",
                            href,
                            INFER_TYPE
                        ) {
                            this.quality = getQualityFromName(quality)
                        }
                    )
                }
            }
        }
    }
}

class M4ulinks : ExtractorApi() {
    override val name            = "M4ulinks"
    override val mainUrl         = "https://m4ulinks.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit)
    {
        val res = app.get(url).document
        res.select("div.downloads-btns-div a").forEach {
            val href = it.attr("href")
            loadExtractor(href,"",subtitleCallback,callback)
        }
    }
}

open class HubCloud : ExtractorApi() {

    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        val ref = "HubCloud"

        val uri = runCatching { URI(url) }.getOrElse {
            Log.e(tag, "Invalid URL: ${it.message}")
            return
        }

        val realUrl = uri.toString()
        val baseUrl = "${uri.scheme}://${uri.host}"

        val href = runCatching {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val resp = app.get(realUrl)
                val doc = resp.document
                val raw = doc.selectFirst("#download")?.attr("href")
                    ?: doc.select("a[href]").firstOrNull { el ->
                        val h = el.attr("href")
                        h.contains("hubcloud.php") || h.contains("gamerxyt.com")
                    }?.attr("href")
                    // Newer pages hide the link behind a double base64 `atob` in an inline script
                    ?: Regex("var url = atob\\(atob\\('([A-Za-z0-9+/=]+)'\\)\\)").find(resp.text)
                        ?.groupValues?.get(1)
                        ?.let { String(Base64.decode(String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8), Base64.DEFAULT), Charsets.UTF_8) }
                    ?: ""

                when {
                    raw.startsWith("http", true) -> raw
                    raw.isNotEmpty() -> baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
                    else -> ""
                }
            }
        }.getOrElse {
            Log.e(tag, "Failed to extract href: ${it.message}")
            ""
        }

        if (href.isBlank()) return

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }

        document.select("a.btn[href]").forEach { element ->

            val link = element.attr("href")
            val text = element.ownText()
            val label = text.lowercase()
            Log.d("Phisher", label)
            when {
                "fsl server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [FSL Server]",
                            "$ref [FSL Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "download file" in label -> {
                    callback(
                        newExtractorLink(
                            ref,
                            "$ref $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "buzzserver" in label -> {
                    val resp = app.get("$link/download", referer = link, allowRedirects = false)
                    val dlink = resp.headers["hx-redirect"]
                        ?: resp.headers["HX-Redirect"].orEmpty()

                    if (dlink.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                "$ref [BuzzServer]",
                                "$ref [BuzzServer] $labelExtras",
                                dlink
                            ) { this.quality = quality }
                        )
                    } else {
                        Log.w(tag, "BuzzServer: No redirect")
                    }
                }

                "pixeldra" in label || "pixelserver" in label || "pixel server" in label || "pixeldrain" in label -> {
                    val base = getBaseUrl(link)
                    val finalUrl =
                        if ("download" in link) link
                        else "$base/api/file/${link.substringAfterLast("/")}?download"

                    callback(
                        newExtractorLink(
                            "$ref Pixeldrain",
                            "$ref Pixeldrain $labelExtras",
                            finalUrl
                        ) { this.quality = quality }
                    )
                }

                "s3 server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [S3 Server]",
                            "$ref [S3 Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "fslv2" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [FSLv2]",
                            "$ref [FSLv2] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "mega server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [Mega Server]",
                            "$ref [Mega Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "gofile" in label -> {
                    try {
                        Gofile().getUrl(link, ref, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e(tag, "Gofile error: ${e.message}")
                    }
                }

                "10gbps" in label || "gpdl" in link -> {
                    callback(
                        newExtractorLink(
                            "$ref [10Gbps]",
                            "$ref [10Gbps] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "telegram" in label || "t.me" in link || "tg/go" in link -> {
                    // Skip telegram buttons
                }

                else -> {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault("")
    }

    private fun cleanTitle(title: String): String {

        val name = title.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")

        val normalized = name
            .replace(Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE), "WEB-DL")
            .replace(Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE), "WEBRIP")
            .replace(Regex("H[ .]?265", RegexOption.IGNORE_CASE), "H265")
            .replace(Regex("H[ .]?264", RegexOption.IGNORE_CASE), "H264")
            .replace(Regex("DDP[ .]?([0-9]\\.[0-9])", RegexOption.IGNORE_CASE), "DDP$1")

        val parts = normalized.split(" ", "_", ".")

        val sourceTags = setOf(
            "WEB-DL", "WEBRIP", "BLURAY", "HDRIP",
            "DVDRIP", "HDTV", "CAM", "TS", "BRRIP", "BDRIP"
        )

        val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")

        val audioTags = setOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD", "DDP", "EAC3")

        val audioExtras = setOf("ATMOS")

        val hdrTags = setOf("SDR","HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION")

        val filtered = parts.mapNotNull { part ->
            val p = part.uppercase()

            when {
                sourceTags.contains(p) -> p
                codecTags.contains(p) -> p
                audioTags.any { p.startsWith(it) } -> p
                audioExtras.contains(p) -> p
                hdrTags.contains(p) -> {
                    when (p) {
                        "DV", "DOLBYVISION" -> "DOLBYVISION"
                        else -> p
                    }
                }
                p == "NF" || p == "CR" -> p
                else -> null
            }
        }

        return filtered.distinct().joinToString(" ")
    }
}

class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    private suspend fun getLatestUrl(): String {
        return try {
            val url = JSONObject(
                app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text
            ).optString("gdflix")
            if (url.isNullOrEmpty()) mainUrl else url
        } catch (e: Exception) {
            mainUrl
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        suspend fun fetch(u: String) = app.get(u).let {
            if (it.code == 403 || it.text.contains("<title>Just a moment")) {
                app.get(u, interceptor = CloudflareKiller())
            } else {
                it
            }
        }.document

        try {
            val uri = runCatching { URI(url) }.getOrNull()
            val host = uri?.host ?: ""
            val latestUrl = getLatestUrl()
            // Rewrite whatever mirror the post links to onto the current GDFlix domain
            val newUrl = if (host.isNotEmpty() && !url.startsWith(latestUrl)) {
                url.replace("${uri?.scheme}://$host", latestUrl)
            } else {
                url
            }
            val document = try {
                fetch(newUrl)
            } catch (e: Exception) {
                try {
                    fetch(url)
                } catch (e2: Exception) {
                    Log.e("GDFlix", "Failed to load GDFlix URL: ${e2.message}")
                    return
                }
            }
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ")
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ")
            val quality = getIndexQuality(fileName)

            document.select("div.text-center a").amap { anchor ->
                val text = anchor.select("a").text()
                val link = anchor.attr("href")

                when {
                    text.contains("Instant DL") -> {
                        val instant = runCatching { app.get(link).url }.getOrNull()?.let {
                            Regex("[?&]url=([^&]+)").find(it)?.groupValues?.get(1)
                                ?.let { encoded -> URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString()) }
                        }
                        if (instant != null) {
                            callback.invoke(
                                newExtractorLink("GDFlix[Instant]", "GDFlix[Instant] $fileName[$fileSize]", instant) {
                                    this.quality = quality
                                }
                            )
                        }
                    }

                    text.contains("DIRECT DL") -> {
                        callback.invoke(
                            newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                                this.quality = quality
                            }
                        )
                    }

                    text.contains("CLOUD DOWNLOAD [R2]") -> {
                        val decodedLink = URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                        callback.invoke(
                            newExtractorLink("GDFlix[Cloud]", "GDFlix[Cloud] $fileName[$fileSize]", decodedLink) {
                                this.quality = quality
                            }
                        )
                    }

                    text.contains("pixeldra", ignoreCase = true) || text.contains("pixel", ignoreCase = true) -> {
                        val baseUrlLink = getBaseUrl(link)
                        val finalURL = if (link.contains("download", true)) link
                        else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"

                        callback(
                            newExtractorLink(
                                "GDFlix Pixeldrain",
                                "GDFlix Pixeldrain $fileName[$fileSize]",
                                finalURL
                            ) { this.quality = quality }
                        )
                    }

                    text.contains("Index Links") -> {
                        try {
                            app.get("$latestUrl$link").document
                                .select("a.btn.btn-outline-info").amap { btn ->
                                    val serverUrl = latestUrl + btn.attr("href")
                                    app.get(serverUrl).document
                                        .select("div.mb-4 > a").amap { sourceAnchor ->
                                            val source = sourceAnchor.attr("href")
                                            callback.invoke(
                                                newExtractorLink("GDFlix[Index]", "GDFlix[Index] $fileName[$fileSize]", source) {
                                                    this.quality = quality
                                                }
                                            )
                                        }
                                }
                        } catch (e: Exception) {
                            Log.d("Index Links", e.toString())
                        }
                    }

                    text.contains("DRIVEBOT") -> {
                        try {
                            val driveLink = link
                            val id = driveLink.substringAfter("id=").substringBefore("&")
                            val doId = driveLink.substringAfter("do=").substringBefore("==")
                            val baseUrls = listOf("https://drivebot.sbs", "https://indexbot.site")

                            baseUrls.amap { baseUrl ->
                                val indexbotLink = "$baseUrl/download?id=$id&do=$doId"
                                val indexbotResponse = app.get(indexbotLink, timeout = 100L)

                                if (indexbotResponse.isSuccessful) {
                                    val cookiesSSID = indexbotResponse.cookies["PHPSESSID"]
                                    val indexbotDoc = indexbotResponse.document

                                    val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""")
                                        .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                    val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                                        .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                    val requestBody = FormBody.Builder()
                                        .add("token", token)
                                        .build()

                                    val headers = mapOf("Referer" to indexbotLink)
                                    val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                                    val downloadLink = app.post(
                                        "$baseUrl/download?id=$postId",
                                        requestBody = requestBody,
                                        headers = headers,
                                        cookies = cookies,
                                        timeout = 100L
                                    ).text.let {
                                        Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                                    }

                                    callback.invoke(
                                        newExtractorLink("GDFlix[DriveBot]", "GDFlix[DriveBot] $fileName[$fileSize]", downloadLink) {
                                            this.referer = baseUrl
                                            this.quality = quality
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("DriveBot", e.toString())
                        }
                    }

                    text.contains("GoFile") -> {
                        try {
                            app.get(link).document
                                .select(".row .row a").amap { gofileAnchor ->
                                    val gLink = gofileAnchor.attr("href")
                                    if (gLink.contains("gofile")) {
                                        Gofile().getUrl(gLink, "", subtitleCallback, callback)
                                    }
                                }
                        } catch (e: Exception) {
                            Log.d("Gofile", e.toString())
                        }
                    }

                    else -> {
                        Log.d("Error", "No Server matched")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "Error in GDFlix: ${e.message}")
        }
    }

    fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (_: Exception) {
            ""
        }
    }
}

class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {
            val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return
            val responseText = app.post("$mainApi/accounts").text
            val json = JSONObject(responseText)
            val token = json.getJSONObject("data").getString("token")

            val globalJs = app.get("$mainUrl/dist/js/global.js").text
            val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""")
                .find(globalJs)?.groupValues?.getOrNull(1) ?: return

            val responseTextfile = app.get(
                "$mainApi/contents/$id?wt=$wt",
                headers = mapOf("Authorization" to "Bearer $token")
            ).text

            val fileDataJson = JSONObject(responseTextfile)

            val data = fileDataJson.getJSONObject("data")
            val children = data.getJSONObject("children")
            val firstFileId = children.keys().asSequence().first()
            val fileObj = children.getJSONObject(firstFileId)

            val link = fileObj.getString("link")
            val fileName = fileObj.getString("name")
            val fileSize = fileObj.getLong("size")

            val sizeFormatted = if (fileSize < 1024L * 1024 * 1024) {
                "%.2f MB".format(fileSize / 1024.0 / 1024)
            } else {
                "%.2f GB".format(fileSize / 1024.0 / 1024 / 1024)
            }

            callback.invoke(
                newExtractorLink(
                    "Gofile",
                    "Gofile [$sizeFormatted]",
                    link
                ) {
                    this.quality = getQuality(fileName)
                    this.headers = mapOf("Cookie" to "accountToken=$token")
                }
            )
        } catch (e: Exception) {
            Log.e("Gofile", "Error occurred: ${e.message}")
        }
    }

    private fun getQuality(fileName: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(fileName ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

open class GDxFiles : ExtractorApi() {
    override val name = "GDxFiles"
    override val mainUrl = "https://gdxfiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GDxFiles"
        try {
            val doc = app.get(url).document
            val titleText = doc.title().substringBefore("|").trim()
            val quality = getIndexQuality(titleText.ifEmpty { referer })
            val labelExtras = when {
                titleText.isNotEmpty() -> " [$titleText]"
                !referer.isNullOrEmpty() -> " [$referer]"
                else -> ""
            }

            val r2Btn = doc.selectFirst("a[href*='/file/r2/']")
            if (r2Btn != null) {
                try {
                    val r2PageUrl = r2Btn.attr("href")
                    val r2Html = app.get(r2PageUrl, referer = url).text
                    val dlHref = Regex("completionUrl\\s*=\\s*[\"']([^\"']+)[\"']")
                        .find(r2Html)?.groupValues?.get(1)?.replace("\\/", "/")
                    val statusUrl = Regex("fetch\\(\\s*['\"]([^'\"]+/status)['\"]")
                        .find(r2Html)?.groupValues?.get(1)

                    // The R2 copy is prepared on demand; poll its status before following the download link
                    var ready = statusUrl == null
                    if (statusUrl != null) {
                        for (i in 0 until 4) {
                            val st = runCatching {
                                JSONObject(
                                    app.get(
                                        statusUrl,
                                        headers = mapOf(
                                            "X-Requested-With" to "XMLHttpRequest",
                                            "Accept" to "application/json"
                                        ),
                                        referer = r2PageUrl
                                    ).text
                                ).optString("status")
                            }.getOrNull()
                            if (st == "completed" || st == "active") {
                                ready = true
                                break
                            }
                            if (st == "failed") break
                            delay(2000)
                        }
                    }

                    if (ready && !dlHref.isNullOrEmpty()) {
                        val resp = app.get(dlHref, referer = r2PageUrl, allowRedirects = false)
                        val loc = resp.headers["location"] ?: resp.headers["Location"] ?: dlHref
                        if (Regex("gdxfiles\\.com/file/[^/?]+/?$").containsMatchIn(loc.substringBefore("?"))) {
                            throw Exception("R2 not ready")
                        }
                        callback(
                            newExtractorLink("GDxFiles [Direct R2]", "GDxFiles [Direct R2]$labelExtras", loc, INFER_TYPE) {
                                this.quality = quality
                                this.referer = r2PageUrl
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(tag, "R2 error: ${e.message}")
                }
            }

            val watchBtn = doc.selectFirst("a[href*='/watch']")
            if (watchBtn != null) {
                try {
                    val watchUrl = watchBtn.attr("href")
                    val watchHtml = app.get(watchUrl, referer = url).text
                    val srcMatch = Regex("const\\s+SRC\\s*=\\s*[\"']([^\"']+)[\"']")
                        .find(watchHtml)?.groupValues?.get(1)
                    if (!srcMatch.isNullOrEmpty()) {
                        val unescaped = srcMatch.replace("\\/", "/").replace("\\u0026", "&")
                        callback(
                            newExtractorLink("GDxFiles [Stream]", "GDxFiles [Stream]$labelExtras", unescaped, INFER_TYPE) {
                                this.quality = quality
                                this.referer = watchUrl
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Watch error: ${e.message}")
                }
            }

            val gofileBtn = doc.selectFirst("a[href*='/file/gofile/']")
            if (gofileBtn != null) {
                try {
                    val gofileDoc = app.get(gofileBtn.attr("href"), referer = url).document
                    val gofileLink = gofileDoc.selectFirst("a[href*='gofile.io']")?.attr("href")
                    if (!gofileLink.isNullOrEmpty()) {
                        Gofile().getUrl(gofileLink, referer, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Gofile error: ${e.message}")
                }
            }

            val pixelBtn = doc.selectFirst("a[href*='/file/pixeldrain/']")
            if (pixelBtn != null) {
                try {
                    val pixelDoc = app.get(pixelBtn.attr("href"), referer = url).document
                    val pixelLink = pixelDoc.selectFirst("a[href*='pixeldrain.com']")?.attr("href")
                    if (!pixelLink.isNullOrEmpty()) {
                        val id = pixelLink.substringAfterLast("/").substringBefore("?")
                        val dUrl = if (pixelLink.contains("download")) pixelLink
                        else "https://pixeldrain.com/api/file/$id?download"
                        callback(
                            newExtractorLink("GDxFiles Pixeldrain", "GDxFiles Pixeldrain$labelExtras", dUrl, INFER_TYPE) {
                                this.quality = quality
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Pixeldrain error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract GDxFiles: ${e.message}")
        }
    }
}

open class MultiCloudLinks : ExtractorApi() {
    override val name = "MultiCloud"
    override val mainUrl = "https://new2.multicloudlinks.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "MultiCloud"
        try {
            val res = app.get(url).document
            val pageTitle = (res.selectFirst("h1, h2, h3, title")?.text() ?: "")
                .replace("Download Links - MultiCloud", "")
                .replace("Download Links", "")
                .trim()
            val quality = getIndexQuality(pageTitle.ifEmpty { referer })
            val labelExtras = when {
                pageTitle.isNotEmpty() -> " [$pageTitle]"
                !referer.isNullOrEmpty() -> " [$referer]"
                else -> ""
            }

            res.select("a[href]").forEach { element ->
                val href = element.attr("href").trim()
                val text = element.text().trim()
                val lower = "$text $href".lowercase()

                when {
                    "turbo download" in lower || "multidownload.rent" in lower || "multidownload.website" in lower -> {
                        val streamUrl = when {
                            href.contains("action=download") -> href
                            href.contains("?") -> "$href&action=download"
                            else -> "$href?action=download"
                        }
                        callback(
                            newExtractorLink("MultiCloud [Turbo R2]", "MultiCloud [Turbo R2]$labelExtras", streamUrl, INFER_TYPE) {
                                this.quality = quality
                                this.referer = url
                            }
                        )
                    }

                    "pixeldra" in lower || "pixel" in lower -> {
                        try {
                            val headResp = app.get(href, allowRedirects = false)
                            val loc = headResp.headers["location"] ?: headResp.headers["Location"] ?: href
                            val pId = loc.substringAfterLast("/").substringBefore("?")
                            val finalUrl = if (loc.contains("download")) loc
                            else "https://pixeldrain.com/api/file/$pId?download"
                            callback(
                                newExtractorLink("MultiCloud Pixeldrain", "MultiCloud Pixeldrain$labelExtras", finalUrl, INFER_TYPE) {
                                    this.quality = quality
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(tag, "Pixeldrain error: ${e.message}")
                        }
                    }

                    "viking" in lower || "vikingfile" in lower -> {
                        try {
                            val resp = app.get(href, allowRedirects = false)
                            val loc = resp.headers["location"] ?: resp.headers["Location"]
                            if (!loc.isNullOrEmpty()) {
                                loadExtractor(loc, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Viking error: ${e.message}")
                        }
                    }

                    "watch online" in lower || "player.php" in lower -> {
                        try {
                            val playerHtml = app.get(href, referer = url).text
                            val streamSrc = Regex("const\\s+streamSrc\\s*=\\s*[\"']([^\"']+)[\"']")
                                .find(playerHtml)?.groupValues?.get(1)
                                ?: Regex("const\\s+dbStreamSrc\\s*=\\s*[\"']([^\"']+)[\"']")
                                    .find(playerHtml)?.groupValues?.get(1)
                            if (!streamSrc.isNullOrEmpty()) {
                                callback(
                                    newExtractorLink("MultiCloud [Stream]", "MultiCloud [Stream]$labelExtras", streamSrc, INFER_TYPE) {
                                        this.quality = quality
                                        this.referer = href
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Player error: ${e.message}")
                        }
                    }

                    "filepress" in lower || "filebee" in lower -> {
                        try {
                            Filepress().getUrl(href, referer, subtitleCallback, callback)
                        } catch (_: Exception) {
                        }
                    }

                    "gdflix" in lower -> {
                        try {
                            GDFlix().getUrl(href, referer, subtitleCallback, callback)
                        } catch (_: Exception) {
                        }
                    }

                    "10 gbps" in lower || "instant download" in lower -> {
                        callback(
                            newExtractorLink("MultiCloud [10Gbps]", "MultiCloud [10Gbps]$labelExtras", href, INFER_TYPE) {
                                this.quality = quality
                                this.referer = url
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error extracting MultiCloud: ${e.message}")
        }
    }
}

open class Filepress : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://new1.filepress.lat"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "FilePress"
        try {
            // Old FilePress domains redirect to the current mirror
            val initialResponse = try {
                app.get(url, allowRedirects = true)
            } catch (e: Exception) {
                null
            }
            val resolvedUrl = initialResponse?.url?.ifEmpty { url } ?: url
            val uri = runCatching { URI(resolvedUrl) }.getOrNull()
                ?: runCatching { URI(url) }.getOrNull()
                ?: return
            val baseUrl = "${uri.scheme}://${uri.host}"
            val fileId = Regex("/file/([a-zA-Z0-9]+)").find(resolvedUrl)?.groupValues?.get(1)
                ?: Regex("/file/([a-zA-Z0-9]+)").find(url)?.groupValues?.get(1)
                ?: return

            val headers = mapOf(
                "Content-Type" to "application/json",
                "Referer" to resolvedUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            var fileName = ""
            var fileSize = ""
            try {
                val info = JSONObject(app.get("$baseUrl/api/file/get/$fileId", headers = headers).text)
                    .optJSONObject("data")
                fileName = info?.optString("name") ?: ""
                val size = info?.optLong("size") ?: 0L
                if (size > 0) {
                    fileSize = if (size < 1024L * 1024 * 1024) {
                        "%.2f MB".format(size / 1024.0 / 1024)
                    } else {
                        "%.2f GB".format(size / 1024.0 / 1024 / 1024)
                    }
                }
            } catch (_: Exception) {
            }

            val quality = getIndexQuality(fileName.ifEmpty { referer })
            val labelExtras = buildString {
                if (fileName.isNotEmpty()) append(" $fileName")
                if (fileSize.isNotEmpty()) {
                    append(" [$fileSize]")
                } else if (!referer.isNullOrEmpty()) {
                    append(" [$referer]")
                }
            }

            val methods = listOf("cloudR2Downlaod" to "R2", "indexDownlaod" to "Index")
            for ((method, methodTag) in methods) {
                try {
                    val step1Payload = JSONObject().put("id", fileId).put("method", method).toString()
                    val step1Resp = app.post(
                        "$baseUrl/api/file/downlaod/",
                        headers = headers,
                        requestBody = step1Payload.toRequestBody("application/json".toMediaType())
                    )
                    val step1Json = JSONObject(step1Resp.text)
                    if (!step1Json.optBoolean("status", false)) continue

                    val sid = when (val data = step1Json.opt("data")) {
                        is String -> data
                        is JSONObject -> data.optString("downloadId").ifEmpty { data.optString("id") }
                        else -> null
                    } ?: continue

                    val step2Payload = JSONObject().put("id", sid).put("method", method).toString()
                    val step2Resp = app.post(
                        "$baseUrl/api/file/downlaod2/",
                        headers = headers,
                        requestBody = step2Payload.toRequestBody("application/json".toMediaType())
                    )
                    val step2Json = JSONObject(step2Resp.text)
                    if (!step2Json.optBoolean("status", false)) continue

                    val links = when (val data = step2Json.opt("data")) {
                        is JSONArray -> (0 until data.length()).map { data.optString(it) }
                        is String -> listOf(data)
                        else -> emptyList()
                    }
                    for (dlUrl in links) {
                        if (!dlUrl.startsWith("http")) continue
                        callback(
                            newExtractorLink("FilePress", "FilePress [$methodTag]$labelExtras", dlUrl, INFER_TYPE) {
                                this.quality = quality
                                this.headers = mapOf("Referer" to "$baseUrl/")
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error in method $method: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract: ${e.message}")
        }
    }
}

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}
