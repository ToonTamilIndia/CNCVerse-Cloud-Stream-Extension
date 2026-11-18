package com.cncverse

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Serves a panel `.ts` live link as one endless MPEG-TS stream, reconnecting through fresh
// edge redirects (two alternating identities) and dropping packets replayed after a reconnect.
object TsStitcher {
    const val STITCH_HEADER = "X-EasyPlay-Stitch"

    private const val MAX_CHAIN = 6
    private const val PARALLEL_WALKS = 3
    private const val QUEUE_CHUNKS = 64
    private const val STALL_MS = 10_000L
    private const val GIVE_UP_MS = 90_000L
    private const val REFRESH_MS = 8_000L
    private const val MAX_AGE_MS = 20_000L
    private const val PKT = 188
    private const val SYNC = 0x47

    @Volatile
    var panelUrl: ((sid: String, identity: Pair<String, String>) -> String?)? = null

    @Volatile
    var userAgent = "VLC/3.0.20 LibVLC/3.0.20"

    private val resolver by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val streamer by lazy {
        resolver.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(STALL_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "EasyPlayStitch").apply { isDaemon = true }
    }

    private val rng = SecureRandom()
    private val PANEL_TS = Regex("/(\\d{1,30})\\.ts$")
    private val TS_TYPE = "video/mp2t".toMediaType()
    private val current = ConcurrentHashMap<String, Session>()

    private class PlayerGone : IOException()

    private class Ready(val url: String, val slot: Int, val atMs: Long) {
        fun usable() = System.currentTimeMillis() - atMs <= MAX_AGE_MS
    }

    private class Session(val sid: String) {
        val ids = arrayOf(newIdentity(), newIdentity())

        @Volatile
        var active = 0

        @Volatile
        var ready: Ready? = null

        @Volatile
        var alive = true
    }

    private fun hex(n: Int): String =
        ByteArray(n).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun newIdentity(): Pair<String, String> = hex(32).let { it to it.take(16) }

    private fun walk(panel: String): String? {
        var url = panel
        repeat(MAX_CHAIN) {
            val req = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
            resolver.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")
                if (resp.code !in 300..399 || loc == null) return if (isLiveTs(resp)) url else null
                url = resp.request.url.resolve(loc)?.toString() ?: return null
            }
        }
        return null
    }

    private fun isLiveTs(resp: Response): Boolean =
        resp.code == 200 &&
            resp.request.url.encodedPath.endsWith(".ts") &&
            !resp.request.url.host.contains("cloudfront", ignoreCase = true) &&
            resp.header("Content-Type")?.contains("mpegurl", ignoreCase = true) != true

    private fun resolve(s: Session, slot: Int, walks: Int = PARALLEL_WALKS): String? {
        val panel = runCatching { panelUrl?.invoke(s.sid, s.ids[slot]) }.getOrNull() ?: return null
        val cs = ExecutorCompletionService<String?>(pool)
        val futures = (1..walks).map { cs.submit(Callable { runCatching { walk(panel) }.getOrNull() }) }
        try {
            repeat(walks) {
                cs.poll(15, TimeUnit.SECONDS)?.get()?.let { return it }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        return null
    }

    private fun nextEdge(s: Session, notUrl: String?): String? {
        s.ready?.takeIf { it.usable() && it.url != notUrl }?.let {
            s.ready = null
            s.active = it.slot
            return it.url
        }
        val slot = 1 - s.active
        s.active = slot
        return resolve(s, slot)
    }

    // Keeps a fresh edge for the idle identity so a dropped connection can switch over instantly.
    private fun standbyLoop(s: Session) {
        while (s.alive) {
            val slot = 1 - s.active
            val r = s.ready
            if (r == null || r.slot != slot || System.currentTimeMillis() - r.atMs > REFRESH_MS) {
                val url = resolve(s, slot, 2)
                if (url != null && s.alive && slot == 1 - s.active) {
                    s.ready = Ready(url, slot, System.currentTimeMillis())
                }
            }
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun stitch(s: Session, out: OutputStream) {
        val buf = ByteArray(65536)
        var lastBytesAt = System.currentTimeMillis()
        var lastUrl: String? = null
        var standbyStarted = false
        val dedupe = Dedupe()
        while (System.currentTimeMillis() - lastBytesAt < GIVE_UP_MS) {
            val edge = nextEdge(s, lastUrl)
            if (edge == null) {
                Thread.sleep(500)
                continue
            }
            val resp = try {
                streamer.newCall(Request.Builder().url(edge).header("User-Agent", userAgent).get().build()).execute()
            } catch (e: IOException) {
                lastUrl = edge
                continue
            }
            resp.use { r ->
                if (!isLiveTs(r)) return@use
                val input = r.body.byteStream()
                if (!standbyStarted) {
                    standbyStarted = true
                    thread(isDaemon = true, name = "EasyPlayStandby-${s.sid}") { standbyLoop(s) }
                }
                dedupe.newConnection()
                var synced = false
                var fill = 0
                while (true) {
                    val n = try {
                        input.read(buf, fill, buf.size - fill)
                    } catch (e: IOException) {
                        -1
                    }
                    if (n < 0) break
                    fill += n
                    var start = 0
                    if (!synced) {
                        val found = findSync(buf, fill)
                        if (found < 0) {
                            val keep = minOf(fill, PKT * 3)
                            System.arraycopy(buf, fill - keep, buf, 0, keep)
                            fill = keep
                            continue
                        }
                        synced = true
                        start = found
                    }
                    val whole = (fill - start) / PKT * PKT
                    if (whole > 0) {
                        lastBytesAt = System.currentTimeMillis()
                        var kept = start
                        var i = start
                        while (i < start + whole) {
                            if (dedupe.keep(buf, i)) {
                                if (kept != i) System.arraycopy(buf, i, buf, kept, PKT)
                                kept += PKT
                            }
                            i += PKT
                        }
                        if (kept > start) {
                            try {
                                out.write(buf, start, kept - start)
                                out.flush()
                            } catch (e: IOException) {
                                throw PlayerGone()
                            }
                        }
                    }
                    val rest = fill - start - whole
                    if (rest > 0) System.arraycopy(buf, start + whole, buf, 0, rest)
                    fill = rest
                    if (rest > 0 && (buf[0].toInt() and 0xff) != SYNC) synced = false
                }
            }
            lastUrl = edge
        }
    }

    // Drops per-PID packets whose PES timestamp is not newer than what was already sent,
    // so overlapping data from a reconnect does not replay.
    private class Dedupe {
        private val last = HashMap<Int, Long>()
        private val open = HashSet<Int>()
        private var passAll = false

        fun newConnection() {
            open.clear()
            passAll = false
        }

        fun keep(b: ByteArray, i: Int): Boolean {
            val pid = ((b[i + 1].toInt() and 0x1f) shl 8) or (b[i + 2].toInt() and 0xff)
            val pts = pesPts(b, i)
            val prev = last[pid]
            if (passAll || prev == null || pid in open) {
                if (pts != null) {
                    last[pid] = pts
                    open.add(pid)
                }
                return true
            }
            if (pts == null) return false
            var d = (pts - prev) and WRAP_MASK
            if (d >= WRAP / 2) d -= WRAP
            if (d > MAX_JUMP || d < -MAX_JUMP) {
                passAll = true
                last[pid] = pts
                return true
            }
            if (d <= 0) return false
            open.add(pid)
            last[pid] = pts
            return true
        }

        private fun pesPts(b: ByteArray, i: Int): Long? {
            if (b[i + 1].toInt() and 0x40 == 0) return null
            val afc = (b[i + 3].toInt() shr 4) and 3
            if (afc and 1 == 0) return null
            val off = i + 4 + if (afc and 2 != 0) (b[i + 4].toInt() and 0xff) + 1 else 0
            if (off + 14 > i + PKT) return null
            if (b[off].toInt() != 0 || b[off + 1].toInt() != 0 || b[off + 2].toInt() != 1) return null
            if (b[off + 7].toInt() and 0x80 == 0) return null
            fun u(k: Int) = b[off + 9 + k].toLong() and 0xff
            return (((u(0) shr 1) and 7) shl 30) or (u(1) shl 22) or ((u(2) shr 1) shl 15) or
                (u(3) shl 7) or (u(4) shr 1)
        }

        companion object {
            const val WRAP = 1L shl 33
            const val WRAP_MASK = WRAP - 1
            const val MAX_JUMP = 5_400_000L
        }
    }

    private fun findSync(b: ByteArray, len: Int): Int {
        var i = 0
        while (i + PKT * 2 < len) {
            if ((b[i].toInt() and 0xff) == SYNC &&
                (b[i + PKT].toInt() and 0xff) == SYNC &&
                (b[i + PKT * 2].toInt() and 0xff) == SYNC
            ) return i
            i++
        }
        return -1
    }

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val sid = if (req.header(STITCH_HEADER) != null) {
            PANEL_TS.find(req.url.encodedPath)?.groupValues?.get(1)
        } else null
        if (sid == null || panelUrl == null) return@Interceptor chain.proceed(req)
        val body = object : ResponseBody() {
            private val src: BufferedSource by lazy { StitchStream(sid).source().buffer() }
            override fun contentType() = TS_TYPE
            override fun contentLength() = -1L
            override fun source() = src
        }
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "video/mp2t")
            .header("Cache-Control", "no-cache")
            .body(body)
            .build()
    }

    private class StitchStream(sid: String) : InputStream() {
        private val s = Session(sid)
        private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CHUNKS)

        @Volatile
        private var ended = false
        private var cur: ByteArray? = null
        private var pos = 0

        init {
            current.put(sid, s)?.alive = false
            thread(isDaemon = true, name = "EasyPlayStitch-$sid") {
                try {
                    stitch(s, object : OutputStream() {
                        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                        override fun write(b: ByteArray, off: Int, len: Int) {
                            val chunk = b.copyOfRange(off, off + len)
                            while (s.alive) {
                                if (queue.offer(chunk, 500, TimeUnit.MILLISECONDS)) return
                            }
                            throw PlayerGone()
                        }
                    })
                } catch (e: Exception) {
                } finally {
                    ended = true
                    s.alive = false
                    current.remove(sid, s)
                }
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val c = cur
                if (c != null && pos < c.size) {
                    val n = minOf(len, c.size - pos)
                    System.arraycopy(c, pos, b, off, n)
                    pos += n
                    return n
                }
                if (!s.alive && queue.isEmpty()) return -1
                cur = queue.poll(500, TimeUnit.MILLISECONDS)
                pos = 0
                if (cur == null && ended && queue.isEmpty()) return -1
            }
        }

        override fun close() {
            s.alive = false
            queue.clear()
        }
    }
}
