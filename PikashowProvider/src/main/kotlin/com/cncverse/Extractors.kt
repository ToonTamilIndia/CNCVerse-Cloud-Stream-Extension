package com.cncverse

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/**
 * StreamTape variant for streamtape.site and other TLDs not covered by the
 * built-in .com/.net/.xyz extractors. Mirrors the official StreamTape resolver:
 * evaluates the obfuscated `botlink').innerHTML = ...` script with Rhino to
 * recover the direct download path.
 */
open class StreamTapeSite : ExtractorApi() {
    override var name = "StreamTape"
    override var mainUrl = "https://streamtape.site"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        return with(app.get(url)) {
            val result = this.document.select("script")
                .firstOrNull { it.html().contains("botlink').innerHTML") }
                ?.html()?.lines()?.firstOrNull { it.contains("botlink').innerHTML") }
                ?.let { line ->
                    val scriptContent = line.substringAfter(").innerHTML")
                        .replaceFirst("=", "var url =")
                    evalJsUrl(scriptContent)
                }

            if (!result.isNullOrEmpty()) {
                listOf(
                    newExtractorLink(name, name, "https:$result&stream=1") {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                null
            }
        }
    }

    private fun evalJsUrl(scriptContent: String): String? {
        val cx = Context.enter()
        return try {
            val scope: Scriptable = cx.initStandardObjects()
            cx.evaluateString(scope, scriptContent, "StreamTapeSite", 1, null)
            val value = scope.get("url", scope)
            value?.toString()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            Context.exit()
        }
    }
}
