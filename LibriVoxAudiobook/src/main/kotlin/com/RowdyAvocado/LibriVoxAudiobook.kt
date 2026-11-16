package com.RowdyAvocado

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class LibriVoxAudiobook : MainAPI() {

    companion object {
        var context: Context? = null
        private const val PAGE_SIZE = 50
        private const val LIST_FIELDS = "&extended=1&fields=%7Bid,title,url_librivox,url_iarchive%7D"
    }

    override var mainUrl = "https://librivox.org"
    override var name = "Librivox Audiobook"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Others)

    override val mainPage = listOf(
        MainPageData("Latest Audiobook", "$mainUrl/api/feed/audiobooks/title/?format=json$LIST_FIELDS")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val resp = app.get("${request.data}&limit=$PAGE_SIZE&offset=${(page - 1) * PAGE_SIZE}")
        val bookList = resp.parsed<BookList>()
        val home = bookList.books.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(
            listOf(HomePageList(request.name, home, false)),
            home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.get("$mainUrl/api/feed/audiobooks/?title=${URLEncoder.encode(query, "UTF-8")}&format=json$LIST_FIELDS")
        val bookList = resp.parsed<BookList>()
        return bookList.books.mapNotNull { it.toSearchResponse() }
    }

    private fun Book.toSearchResponse(): SearchResponse? {
        val title = title ?: return null
        val link = url ?: return null
        val iaId = iarchive?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        return newAnimeSearchResponse(title, link, TvType.Anime) {
            this.posterUrl = iaId?.let { "https://archive.org/services/img/$it" }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.content-wrap h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.book-page-book-cover img")?.attr("src")
            ?: "https://librivox.org/images/librivox-logo.png"

        val episodes = document.select("a.chapter-name").map { element ->
            val href = fixUrl(element.attr("href"))
            val episodeName = element.text().trim()
            newEpisode(href) { name = episodeName }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            newExtractorLink("Librivox Audiobook", "Librivox Audiobook", data) {
                referer = "$mainUrl/"
                quality = Qualities.P360.value
            }
        )
        return true
    }

    data class BookList(@JsonProperty("books") val books: ArrayList<Book> = arrayListOf())
    data class Book(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url_librivox") val url: String? = null,
        @JsonProperty("url_iarchive") val iarchive: String? = null,
    )
}
