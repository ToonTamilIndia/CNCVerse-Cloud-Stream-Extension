package com.RowdyAvocado

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
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class LibriVoxAudiobook : MainAPI() {
    override var mainUrl = "https://librivox.org"
    override var name = "Librivox Audiobook"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Others)

    override val mainPage = listOf(
        MainPageData("Latest Audiobook", "$mainUrl/api/feed/audiobooks/title/?format=json")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val resp = app.get(request.data)
        val bookList = resp.parsed<BookList>()
        val home = bookList.books.mapNotNull { book ->
            val title = book.title ?: return@mapNotNull null
            val url = book.url ?: return@mapNotNull null
            newAnimeSearchResponse(title, url, TvType.Others) {}
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, home, false)),
            !home.isEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.get("$mainUrl/api/feed/audiobooks/?title=$query&format=json")
        val bookList = resp.parsed<BookList>()
        return bookList.books.map { book ->
            newAnimeSearchResponse(
                book.title ?: "Unknown",
                book.url ?: "",
                TvType.Others
            ) {}
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.content-wrap h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.book-page-book-cover img")?.attr("src")
            ?: "https://librivox.org/images/librivox-logo.png"

        val episodes = document.select("a.chapter-name").mapNotNull { element ->
            val href = element.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val episodeName = element.text().trim()
            newEpisode(href) { name = episodeName }
        }

        return newMovieLoadResponse(title, url, TvType.Others, url) {
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
    )
}
