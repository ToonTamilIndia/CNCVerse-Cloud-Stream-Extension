package com.cncverse

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

object MoviePlusCatalog {
    private val MIRRORS = listOf(
        "https://raw.githubusercontent.com/dev-mplus01/mplus01T/main/db/items_up.json.gz",
        "https://api.appsdevxp.com/db/items_up.json.gz",
        "https://developerxploit.com/app/db/items_up.json.gz",
    )
    const val IMAGE_BASE = "https://image.tmdb.org/t/p"
    const val TYPE_MOVIE = 1
    const val TYPE_SERIES = 2
    private const val CACHE_TTL_MS = 1_800_000L

    val GENRE_NAMES = mapOf(
        1 to "Acción", 2 to "Aventura", 3 to "Animación", 4 to "Comedia", 5 to "Crimen",
        6 to "Documental", 7 to "Drama", 8 to "Familia", 9 to "Fantasía", 10 to "Historia",
        11 to "Terror", 12 to "Música", 13 to "Misterio", 14 to "Romance", 15 to "Ciencia ficción",
        16 to "Película de TV", 17 to "Suspense", 18 to "Bélica", 19 to "Western",
        20 to "Acción y aventura", 21 to "Niños", 22 to "Noticias", 23 to "Reality",
        24 to "Sci-Fi & Fantasy", 25 to "Soap", 26 to "Talk show", 27 to "Bélico y político",
    )

    private val mapper = jacksonObjectMapper()
    private val mutex = Mutex()

    @Volatile private var cacheMovies: List<CatalogItem> = emptyList()
    @Volatile private var cacheSeries: List<CatalogItem> = emptyList()
    @Volatile private var cacheTime = 0L

    data class CatalogItem(
        val id: Int,
        val name: String,
        val otherNames: String,
        val date: String,
        val genres: List<Int>,
        val type: Int,
        val image: String,
        val resolutions: String,
        val urls: List<String>,
    )

    suspend fun loadCatalog(): Pair<List<CatalogItem>, List<CatalogItem>> {
        val now = System.currentTimeMillis()
        if (cacheMovies.isNotEmpty() && now - cacheTime < CACHE_TTL_MS) return cacheMovies to cacheSeries
        return mutex.withLock {
            if (cacheMovies.isNotEmpty() && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS) {
                return@withLock cacheMovies to cacheSeries
            }
            for (mirror in MIRRORS) {
                try {
                    val bytes = app.get(mirror, timeout = 60L).body.bytes()
                    val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }
                    val (movies, series) = parseCatalog(json)
                    if (movies.isNotEmpty() || series.isNotEmpty()) {
                        cacheMovies = movies
                        cacheSeries = series
                        cacheTime = System.currentTimeMillis()
                        return@withLock movies to series
                    }
                } catch (_: Throwable) {
                }
            }
            cacheMovies to cacheSeries
        }
    }

    private fun parseCatalog(json: String): Pair<List<CatalogItem>, List<CatalogItem>> {
        val root = mapper.readValue(json, Map::class.java)

        fun parseList(raw: Any?): List<CatalogItem> {
            val list = raw as? List<*> ?: return emptyList()
            return list.mapNotNull { element ->
                val item = element as? Map<*, *> ?: return@mapNotNull null
                val id = (item["id"] as? Number)?.toInt() ?: return@mapNotNull null
                CatalogItem(
                    id = id,
                    name = item["nameFile"] as? String ?: "",
                    otherNames = item["othersNames"] as? String ?: "",
                    date = item["date"] as? String ?: "",
                    genres = parseGenres(item["generos"]),
                    type = (item["type"] as? Number)?.toInt() ?: TYPE_MOVIE,
                    image = item["urlImage"] as? String ?: "",
                    resolutions = item["resolutions"] as? String ?: "",
                    urls = (item["urls"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                )
            }
        }

        return parseList(root["movies"]) to parseList(root["series"])
    }

    private fun parseGenres(raw: Any?): List<Int> = when (raw) {
        is List<*> -> raw.mapNotNull { (it as? Number)?.toInt() }
        is String -> raw.split(",", " ").mapNotNull { it.trim().toIntOrNull() }
        else -> emptyList()
    }

    fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace("á", "a")
        .replace("é", "e")
        .replace("í", "i")
        .replace("ó", "o")
        .replace("ú", "u")
        .replace("ü", "u")
        .replace("ñ", "n")

    fun findMovie(id: Int): CatalogItem? = cacheMovies.firstOrNull { it.id == id }

    fun findSeries(id: Int): CatalogItem? = cacheSeries.firstOrNull { it.id == id }
}
