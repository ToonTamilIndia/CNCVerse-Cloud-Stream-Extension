package com.cncverse.cinevood

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

open class Filepress : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://new1.filepress.lat"
    override val requiresReferer = false

    // `referer` carries the post's quality label when called from the providers.
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val initialResponse = try {
                app.get(url, allowRedirects = true)
            } catch (_: Exception) {
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
                val data = JSONObject(app.get("$baseUrl/api/file/get/$fileId", headers = headers).text)
                    .optJSONObject("data")
                fileName = data?.optString("name") ?: ""
                val size = data?.optLong("size") ?: 0L
                if (size > 0) {
                    fileSize = if (size < 1073741824) "%.2f MB".format(size / 1024.0 / 1024)
                    else "%.2f GB".format(size / 1024.0 / 1024 / 1024)
                }
            } catch (_: Exception) {
            }

            val quality = getIndexQuality(fileName.ifEmpty { referer })
            val labelExtras = buildString {
                if (fileName.isNotEmpty()) append(" $fileName")
                if (fileSize.isNotEmpty()) append(" [$fileSize]")
                else if (!referer.isNullOrEmpty()) append(" [$referer]")
            }

            val methods = listOf("cloudR2Downlaod" to "R2", "indexDownlaod" to "Index")
            for ((method, tag) in methods) {
                try {
                    val step1Payload = JSONObject().apply {
                        put("id", fileId)
                        put("method", method)
                    }.toString()
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

                    val step2Payload = JSONObject().apply {
                        put("id", sid)
                        put("method", method)
                    }.toString()
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
                    }.map { it.replace(" ", "%20") }

                    for (dlUrl in links) {
                        if (!dlUrl.startsWith("http")) continue
                        callback(
                            newExtractorLink("FilePress", "FilePress [$tag]$labelExtras", dlUrl, INFER_TYPE) {
                                this.quality = quality
                                this.headers = mapOf("Referer" to "$baseUrl/")
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("FilePress", "Error in method $method: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("FilePress", "Failed to extract: ${e.message}")
        }
    }
}

fun getIndexQuality(str: String?): Int =
    Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value

private suspend fun followToLink(start: String): String? {
    var current = start
    repeat(6) {
        if (current.contains("link=http")) return current.substringAfter("link=")
        val location = runCatching {
            app.get(current, headers = browserHeaders, allowRedirects = false).headers["location"]
        }.getOrNull() ?: return null
        current = if (location.startsWith("http")) location else URI(current).resolve(location).toString()
    }
    return null
}

suspend fun hubCloud(url: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
    val id = Regex("/drive/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: return false
    val drive = latestDomain("hubcloud", "https://hubcloud.ist") + "/drive/" + id
    val page = runCatching { app.get(drive, headers = browserHeaders).text }.getOrNull() ?: return false
    val next = Regex("var\\s+url\\s*=\\s*'([^']+)'").find(page)?.groupValues?.get(1)
        ?: Jsoup.parse(page).selectFirst("#download")?.attr("href")
        ?: return false
    val doc = runCatching {
        app.get(next, headers = browserHeaders + ("Referer" to drive)).document
    }.getOrNull() ?: return false
    val file = doc.selectFirst("div.card-header")?.text()?.ifEmpty { null } ?: label
    val size = doc.selectFirst("i#size")?.text() ?: ""
    var found = false
    doc.select("a[href]").distinctBy { it.attr("href") }.amap { a ->
        val href = a.attr("href")
        val (server, link) = when {
            href.contains("r2.dev") || href.contains("r2.cloudflarestorage.com") -> "FSL" to href
            href.contains("workers.dev") && a.text().contains("Download File") -> "Direct" to href
            Regex("//(?:gpdl\\d*|pixel)\\.hubcloud\\.").containsMatchIn(href) -> {
                val server = if (href.contains("pixel")) "Pixel" else "10Gbps"
                server to followToLink(href)
            }
            else -> return@amap
        }
        if (link == null) return@amap
        callback(
            newExtractorLink(
                "HubCloud",
                "HubCloud [$server] $file${if (size.isNotEmpty()) " [$size]" else ""}",
                link,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = getIndexQuality(file)
            }
        )
        found = true
    }
    return found
}

suspend fun gdFlix(url: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
    fun urlParam(u: String) =
        Regex("[?&]url=([^&]+)").find(u)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }

    val path = runCatching { URI(url).rawPath }.getOrNull() ?: return false
    val pageUrl = latestDomain("gdflix", "https://new5.gdflix.io") + path
    val doc = runCatching { getProtected(pageUrl).document }.getOrNull() ?: return false
    val file = doc.select("li.list-group-item:contains(Name)").text().substringAfter("Name :").trim().ifEmpty { label }
    val size = doc.select("li.list-group-item:contains(Size)").text()
        .substringAfter("Size :").substringBefore("|").trim()
    var found = false
    doc.select("a[href]").forEach { a ->
        val text = a.text()
        val (server, link) = when {
            text.contains("Instant DL") -> "Instant" to runCatching {
                urlParam(app.get(a.attr("href"), headers = browserHeaders).url)
            }.getOrNull()
            text.contains("CLOUD DOWNLOAD") ->
                "R2" to (urlParam(a.attr("href")) ?: a.attr("href").takeIf { it.contains("r2.dev") })
            else -> return@forEach
        }
        if (link == null || !link.startsWith("http")) return@forEach
        callback(
            newExtractorLink(
                "GDFlix",
                "GDFlix [$server] $file${if (size.isNotEmpty()) " [$size]" else ""}",
                link,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = getIndexQuality(file)
            }
        )
        found = true
    }
    return found
}
