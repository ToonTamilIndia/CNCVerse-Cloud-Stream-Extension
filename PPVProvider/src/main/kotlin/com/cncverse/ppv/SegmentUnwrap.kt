package com.cncverse.ppv

import com.lagradost.cloudstream3.app
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * PPV streams disguise MPEG-TS segments as images (WebP EXIF chunk, data after PNG IEND,
 * RGB pixel payload, or TIKTIK-marked blobs). This interceptor refreshes the playlist
 * token, prefetches the newest segments and hands the player plain TS bytes.
 */
object SegmentUnwrap {
    private class Token(val value: String, val renewAt: Long, val expiresAt: Long)

    private val tokens = ConcurrentHashMap<String, Token>()

    val interceptor = Interceptor { intercept(it) }

    private fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url

        if (url.encodedPath.endsWith(".m3u8")) {
            var request = req
            val slug = url.pathSegments.firstOrNull { it.startsWith("premium") }
            val fresh = slug?.let { token(chain, req, it) }
            if (fresh != null && fresh != url.queryParameter("t")) {
                request = req.newBuilder().url(url.newBuilder().setQueryParameter("t", fresh).build()).build()
            }
            val res = chain.proceed(request)
            if (!res.isSuccessful) return res
            val text = res.body.string()
            runCatching { prefetchFrom(text, request) }
            return res.newBuilder()
                .removeHeader("Content-Length")
                .body(text.toResponseBody(res.body.contentType() ?: "application/vnd.apple.mpegurl".toMediaType()))
                .build()
        }

        if (!isImageSegment(url.toString())) return chain.proceed(req)

        pending[url.toString()]?.let { future ->
            runCatching { future.get(25, TimeUnit.SECONDS) }.getOrNull()?.let { return tsResponse(req, it) }
            pending.remove(url.toString())
        }

        var last: Exception? = null
        repeat(3) {
            try {
                val res = chain.withConnectTimeout(8, TimeUnit.SECONDS)
                    .withReadTimeout(10, TimeUnit.SECONDS)
                    .proceed(req)
                if (!res.isSuccessful) return res
                val bytes = res.body.bytes()
                return tsResponse(req, runCatching { unwrap(bytes) }.getOrNull() ?: bytes)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("segment failed")
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val pending: MutableMap<String, Future<ByteArray>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Future<ByteArray>>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Future<ByteArray>>?) = size > 12
        }
    )

    private val client: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun isImageSegment(u: String): Boolean {
        val path = u.substringBefore('?').lowercase()
        return path.contains("~tplv") || path.endsWith(".png") || path.endsWith(".webp") ||
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".image") || path.endsWith(".gif")
    }

    private fun prefetchFrom(playlist: String, req: Request) {
        if (!playlist.contains("#EXTINF")) return
        val headers = listOf("User-Agent", "Origin", "Referer").mapNotNull { h -> req.header(h)?.let { h to it } }
        playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line -> (line.toHttpUrlOrNull() ?: req.url.resolve(line))?.toString() }
            .filter { isImageSegment(it) }
            .toList()
            .takeLast(4)
            .forEach { seg ->
                synchronized(pending) {
                    if (!pending.containsKey(seg)) {
                        pending[seg] = pool.submit(Callable<ByteArray> {
                            val r = Request.Builder().url(seg).apply {
                                headers.forEach { (k, v) -> header(k, v) }
                            }.build()
                            var last: Exception? = null
                            repeat(3) {
                                try {
                                    client.newCall(r).execute().use { res ->
                                        if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                                        val bytes = res.body.bytes()
                                        return@Callable runCatching { unwrap(bytes) }.getOrNull() ?: bytes
                                    }
                                } catch (e: Exception) {
                                    last = e
                                }
                            }
                            throw last ?: IOException("segment failed")
                        })
                    }
                }
            }
    }

    private fun tsResponse(req: Request, ts: ByteArray): Response = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", "video/mp2t")
        .body(ts.toResponseBody("video/mp2t".toMediaType()))
        .build()

    private fun token(chain: Interceptor.Chain, req: Request, slug: String): String? {
        val now = System.currentTimeMillis()
        tokens[slug]?.let { if (now < it.renewAt) return it.value }

        val api = req.url.newBuilder()
            .encodedPath("/api/playlist-token")
            .query(null)
            .addQueryParameter("slug", slug)
            .build()
        val fresh = runCatching {
            val request = Request.Builder().url(api).get().apply {
                listOf("User-Agent", "Origin", "Referer").forEach { h -> req.header(h)?.let { header(h, it) } }
            }.header("Cache-Control", "no-cache").build()
            chain.proceed(request).use { res ->
                if (!res.isSuccessful) return@use null
                val json = JSONObject(res.body.string())
                val ttl = maxOf(20L, json.optLong("ttl", 120))
                json.optString("token").takeIf { it.isNotEmpty() }?.let {
                    Token(it, now + 500 * ttl, now + 1000 * ttl - 2000)
                }
            }
        }.getOrNull()

        if (fresh != null) {
            tokens[slug] = fresh
            return fresh.value
        }
        return tokens[slug]?.takeIf { now < it.expiresAt }?.value
    }

    private val TSGZ = "TIKTIKTSGZ".toByteArray()
    private val TRAW = "TIKTIKRAW".toByteArray()
    private val TPIX = "TIKTIKPX".toByteArray()

    fun unwrap(b: ByteArray): ByteArray? {
        webpExif(b)?.let { return it }
        pngTail(b)?.let { return it }
        if (b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte()) return pngPixels(b)
        indexOf(b, TRAW)?.let { i ->
            val s = i + TRAW.size
            if (s < b.size && b[s] == 0x47.toByte()) return b.copyOfRange(s, b.size)
        }
        indexOf(b, TSGZ)?.let { i -> return gunzip(b.copyOfRange(i + TSGZ.size, b.size)) }
        for (i in 0 until b.size - 188) {
            if (isTs(b, i)) return b.copyOfRange(i, b.size)
        }
        return null
    }

    private fun u32be(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    private fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)

    private fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    private fun isTs(b: ByteArray, o: Int) = o + 188 < b.size && b[o] == 0x47.toByte() && b[o + 188] == 0x47.toByte()

    private fun indexOf(b: ByteArray, pat: ByteArray): Int? {
        outer@ for (i in 0 until b.size - pat.size) {
            for (j in pat.indices) {
                if (b[i + j] != pat[j]) continue@outer
            }
            return i
        }
        return null
    }

    private fun webpExif(b: ByteArray): ByteArray? {
        if (b.size < 16 || tag(b, 0) != "RIFF" || tag(b, 8) != "WEBP") return null
        var off = 12
        while (off + 8 <= b.size) {
            val t = tag(b, off)
            val n = u32le(b, off + 4).toInt()
            off += 8
            if (n < 0 || off + n > b.size) return null
            if (t == "EXIF") return if (n >= 189 && isTs(b, off)) b.copyOfRange(off, off + n) else null
            off += n + (n and 1)
        }
        return null
    }

    private fun pngTail(b: ByteArray): ByteArray? {
        if (b.size < 16 || b[0] != 0x89.toByte() || b[1] != 0x50.toByte()) return null
        var off = 8
        while (off + 8 <= b.size) {
            val len = u32be(b, off)
            if (len > b.size - off - 12) return null
            val t = tag(b, off + 4)
            off += len.toInt() + 12
            if (t == "IEND") return if (isTs(b, off)) b.copyOfRange(off, b.size) else null
        }
        return null
    }

    private fun pngPixels(b: ByteArray): ByteArray? {
        var off = 8
        var w = 0
        var h = 0
        var depth = 0
        var ctype = 0
        var interlace = 0
        val idat = ByteArrayOutputStream(b.size)
        while (off + 8 <= b.size) {
            val len = u32be(b, off).toInt()
            if (len < 0 || len > b.size - off - 12) return null
            val t = tag(b, off + 4)
            val d = off + 8
            if (t == "IHDR") {
                w = u32be(b, d).toInt()
                h = u32be(b, d + 4).toInt()
                depth = b[d + 8].toInt()
                ctype = b[d + 9].toInt()
                interlace = b[d + 12].toInt()
            } else if (t == "IDAT") {
                idat.write(b, d, len)
            }
            off += len + 12
            if (t == "IEND") break
        }
        if (w <= 0 || h <= 0 || depth != 8 || interlace != 0) return null
        if (ctype != 2 && ctype != 6) return null

        val bpp = if (ctype == 6) 4 else 3
        val stride = w * bpp
        val raw = ByteArray((stride + 1) * h)
        val inflater = Inflater()
        try {
            inflater.setInput(idat.toByteArray())
            var n = 0
            while (n < raw.size && !inflater.finished()) {
                val r = inflater.inflate(raw, n, raw.size - n)
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += r
            }
            if (n < raw.size) return null
        } finally {
            inflater.end()
        }

        // Unfilter scanlines into packed RGB; the payload is "TIKTIKPX" + u32 length + gzip(ts).
        val rgb = ByteArray(w * h * 3)
        var row = ByteArray(stride)
        var prev = ByteArray(stride)
        var src = 0
        var dst = 0
        var need = rgb.size
        var y = 0
        while (y < h && dst < need) {
            val filter = raw[src++].toInt()
            if (filter == 0) {
                System.arraycopy(raw, src, row, 0, stride)
            } else {
                for (i in 0 until stride) {
                    val x = raw[src + i].toInt() and 0xff
                    val a = if (i >= bpp) row[i - bpp].toInt() and 0xff else 0
                    val up = prev[i].toInt() and 0xff
                    val c = if (i >= bpp) prev[i - bpp].toInt() and 0xff else 0
                    row[i] = when (filter) {
                        1 -> x + a
                        2 -> x + up
                        3 -> x + ((a + up) shr 1)
                        4 -> x + paeth(a, up, c)
                        else -> return null
                    }.toByte()
                }
            }
            src += stride
            if (bpp == 3) {
                System.arraycopy(row, 0, rgb, dst, stride)
                dst += stride
            } else {
                for (i in 0 until stride step 4) {
                    rgb[dst++] = row[i]
                    rgb[dst++] = row[i + 1]
                    rgb[dst++] = row[i + 2]
                }
            }
            if ((y == 0 || need == rgb.size) && dst >= 12) {
                for (k in TPIX.indices) {
                    if (rgb[k] != TPIX[k]) return null
                }
                val len = u32be(rgb, 8)
                if (len <= 0 || 12 + len > rgb.size) return null
                need = (12 + len).toInt()
            }
            val t = prev
            prev = row
            row = t
            y++
        }

        val size = need - 12
        if (size <= 0 || dst < need || rgb[12] != 0x1f.toByte() || rgb[13] != 0x8b.toByte()) return null
        val ts = gunzip(rgb.copyOfRange(12, size + 12))
        return if (ts.isNotEmpty() && ts[0] == 0x47.toByte()) ts else null
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun gunzip(gz: ByteArray): ByteArray = GZIPInputStream(gz.inputStream(), 65536).use { it.readBytes() }
}
