package com.cncverse

import android.util.Base64
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object MoviePlusWorker {
    private const val BASE = "https://api.appsxploit.dev"
    private const val FB_PROJECT = "dev-movieplus"
    private const val VERSION_CODE = 90354
    private const val RESOLVE_CONCURRENCY = 4
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val ANDROID_HEADERS = mapOf(
        "X-Android-Package" to "com.dvex.movp",
        "X-Android-Cert" to BuildConfig.MOVIEPLUS_ANDROID_CERT,
    )

    private val fbLock = Mutex()
    private var firebaseIdToken: String? = null
    private var idTokenAtMs = 0L
    private const val idTokenTtlMs = 720_000L

    private val seasonKeyRe = Regex("(?i)temporada\\s*0*(\\d+)")
    private val sxeRe = Regex("(?i)S0*(\\d+)\\s*E0*(\\d+)")
    private val availCache = ConcurrentHashMap<String, Boolean>()

    data class ResolvedLink(val quality: String, val size: String, val url: String)

    data class EpisodeLink(val season: Int, val episode: Int, val quality: String, val envelope: String)

    // One throwaway device identity per resolve: the backend rate-limits playback per device.
    private class Session(val keyPair: KeyPair, val spkiB64: String, val deviceId: String) {
        var sessionToken: String? = null
        var grants: Map<String, String> = emptyMap()
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun signB64(session: Session, msg: String): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(session.keyPair.private)
        sig.update(msg.toByteArray(Charsets.UTF_8))
        return b64(sig.sign())
    }

    private fun esc(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private fun json(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) ->
            val value = when (v) {
                is String -> "\"${esc(v)}\""
                is Boolean, is Int, is Long -> v.toString()
                null -> "null"
                else -> "\"${esc(v.toString())}\""
            }
            "\"${esc(k)}\":$value"
        }

    private suspend fun post(path: String, bodyBytes: ByteArray, headers: Map<String, String>): Pair<Int, String> {
        val r = app.post(
            BASE + path,
            headers = mapOf("User-Agent" to "okhttp/4.12.0") + headers,
            requestBody = bodyBytes.toRequestBody(JSON_MEDIA),
            timeout = 30L,
        )
        return r.code to r.text
    }

    private fun hybridHeaders(session: Session, path: String, bodyBytes: ByteArray): Map<String, String> {
        val token = session.sessionToken ?: ""
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val canon = "MOVP-HYBRID-REQUEST-V1\nPOST\n$path\n${sha256Hex(bodyBytes)}\n$nonce\n" +
            sha256Hex(token.toByteArray(Charsets.UTF_8))
        return mapOf(
            "X-MOVP-Hybrid-Session" to token,
            "X-MOVP-Hybrid-Nonce" to nonce,
            "X-MOVP-Hybrid-Signature" to signB64(session, canon),
        )
    }

    private suspend fun postRetry(
        session: Session?,
        path: String,
        bodyBytes: ByteArray,
        hybrid: Boolean,
        tries: Int = 6,
        stopOn: Set<Int> = emptySet(),
    ): Pair<Int, String> {
        var last = -1 to ""
        repeat(tries) {
            val headers = if (hybrid && session != null) hybridHeaders(session, path, bodyBytes) else emptyMap()
            last = post(path, bodyBytes, headers)
            if (last.first == 200 || last.first in stopOn) return last
            delay(2500)
        }
        return last
    }

    private fun generateIdentity(): Session {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        return Session(kp, b64(kp.public.encoded), UUID.randomUUID().toString())
    }

    private suspend fun connect(session: Session): Boolean {
        repeat(8) {
            try {
                val (cst, cb) = post(
                    "/v2/client/challenge",
                    json("schemaVersion" to 1, "versionCode" to VERSION_CODE).toByteArray(Charsets.UTF_8),
                    emptyMap(),
                )
                if (cst != 200) {
                    delay(2500)
                    return@repeat
                }
                val challenge = JSONObject(cb).getString("challenge")
                val sBody = json(
                    "schemaVersion" to 1,
                    "publicKeySpki" to session.spkiB64,
                    "challenge" to challenge,
                    "deviceId" to session.deviceId,
                    "signature" to signB64(session, "MOVP-HYBRID-SESSION-V1\n$challenge\n${session.deviceId}"),
                ).toByteArray(Charsets.UTF_8)
                val (sst, sb) = post("/v2/client/session", sBody, emptyMap())
                if (sst == 200) {
                    session.sessionToken = JSONObject(sb).getString("sessionToken")
                    return true
                }
            } catch (_: Throwable) {
            }
            delay(2500)
        }
        return false
    }

    private suspend fun bootstrap(session: Session): Boolean {
        val body = json(
            "schemaVersion" to 1,
            "deviceId" to session.deviceId,
            "channel" to "prod",
            "settingsProfile" to 7,
        ).toByteArray(Charsets.UTF_8)
        val (st, b) = postRetry(session, "/v2/client/bootstrap", body, true)
        if (st != 200) return false
        val g = JSONObject(b).optJSONObject("admission")?.optJSONObject("grants") ?: return false
        val m = HashMap<String, String>()
        for (k in g.keys()) m[k] = g.getString(k)
        session.grants = m
        return session.grants.containsKey("catalog") && session.grants.containsKey("catalog-value")
    }

    private suspend fun newSession(): Session? {
        val session = generateIdentity()
        if (!connect(session)) return null
        if (!bootstrap(session)) return null
        return session
    }

    private suspend fun playback(session: Session, envelope: String): String? {
        val body = json(
            "schemaVersion" to 1,
            "deviceId" to session.deviceId,
            "grantId" to (session.grants["catalog-value"] ?: ""),
            "token" to envelope,
            "issuedAtMs" to System.currentTimeMillis() - 1000L,
            "resolveProvider" to true,
        ).toByteArray(Charsets.UTF_8)
        val (st, b) = postRetry(session, "/v2/client/playback", body, true, 3, setOf(503, 429))
        if (st != 200) return null
        val o = JSONObject(b)
        if (o.optString("status") != "resolved" || !o.optBoolean("ok", false)) return null
        return o.optString("value").takeIf { it.startsWith("http") }
    }

    private suspend fun resolveFresh(envelope: String): String? {
        val session = newSession() ?: return null
        return try {
            playback(session, envelope)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun resolveMovie(movieId: Int, fallbackEnvelopes: List<String> = emptyList()): List<ResolvedLink> = try {
        val session = newSession()
        if (session == null) {
            emptyList()
        } else {
            val body = json(
                "schemaVersion" to 1,
                "deviceId" to session.deviceId,
                "grantId" to (session.grants["catalog"] ?: ""),
                "movieId" to movieId,
            ).toByteArray(Charsets.UTF_8)
            val (st, b) = postRetry(session, "/v2/client/movie-variants", body, true, 3)
            val variants = if (st == 200) {
                JSONObject(b).optJSONObject("movieLinks")?.optJSONArray("variants")
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: emptyList()
            } else {
                emptyList()
            }
            val entries = if (variants.isEmpty() && fallbackEnvelopes.isNotEmpty()) fallbackEnvelopes else variants
            val byQuality = LinkedHashMap<String, List<String>>()
            entries.mapNotNull { e -> e.split(",_,", limit = 3).takeIf { it.size == 3 } }
                .forEach { parts -> byQuality.getOrPut(parts[0].trim()) { parts } }
            val gate = Semaphore(RESOLVE_CONCURRENCY)
            val resolved = byQuality.values.toList().amap { parts ->
                gate.withPermit {
                    resolveFresh(parts[2].trim())?.let { ResolvedLink(parts[0].trim(), parts[1].trim(), it) }
                }
            }.filterNotNull()
            val seen = HashSet<String>()
            resolved.filter { seen.add(it.url) }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private suspend fun ensureFirebaseToken(): String? = fbLock.withLock {
        firebaseIdToken?.let { if (System.currentTimeMillis() - idTokenAtMs < idTokenTtlMs) return@withLock it }
        val session = newSession() ?: return@withLock null
        val authBody = json(
            "schemaVersion" to 1,
            "deviceId" to session.deviceId,
            "action" to "guest",
            "payload" to "__PAYLOAD__",
        ).replace(
            "\"__PAYLOAD__\"",
            json("deviceKey" to session.deviceId, "appVersion" to "17.3", "platform" to "android"),
        ).toByteArray(Charsets.UTF_8)
        val (ast, ab) = postRetry(session, "/v2/client/auth", authBody, true, 4)
        if (ast != 200) return@withLock null
        val customToken = JSONObject(ab).optString("token").takeIf { it.isNotBlank() } ?: return@withLock null
        val signInBody = json("token" to customToken, "returnSecureToken" to true).toByteArray(Charsets.UTF_8)
        val resp = app.post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=${BuildConfig.MOVIEPLUS_FIREBASE_API_KEY}",
            headers = ANDROID_HEADERS,
            requestBody = signInBody.toRequestBody(JSON_MEDIA),
            timeout = 25L,
        )
        if (resp.code != 200) return@withLock null
        firebaseIdToken = JSONObject(resp.text).optString("idToken").takeIf { it.isNotBlank() }
        idTokenAtMs = System.currentTimeMillis()
        firebaseIdToken
    }

    suspend fun episodeEnvelopes(seriesId: Int): List<EpisodeLink> {
        return try {
            val token = ensureFirebaseToken() ?: return emptyList()
            val url = "https://firestore.googleapis.com/v1/projects/$FB_PROJECT/databases/(default)/documents/tv/$seriesId"
            val resp = app.get(url, headers = ANDROID_HEADERS + ("Authorization" to "Bearer $token"), timeout = 25L)
            if (resp.code != 200) return emptyList()
            val fields = JSONObject(resp.text).optJSONObject("fields") ?: return emptyList()
            val out = ArrayList<EpisodeLink>()
            for (key in fields.keys()) {
                val season = seasonKeyRe.find(key)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val quality = key.substringAfter('/', "").trim()
                val values = fields.optJSONObject(key)?.optJSONObject("arrayValue")?.optJSONArray("values") ?: continue
                for (i in 0 until values.length()) {
                    val raw = values.optJSONObject(i)?.optString("stringValue") ?: continue
                    val parts = raw.trim().trim('"').split("_SPLIT_")
                    if (parts.size < 2) continue
                    val envelope = parts[1].trim()
                    if (!envelope.startsWith("Movp2")) continue
                    val ep = sxeRe.find(parts[0])?.groupValues?.get(2)?.toIntOrNull() ?: (i + 1)
                    out.add(EpisodeLink(season, ep, quality, envelope))
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun availKey(id: Int, isMovie: Boolean): String = (if (isMovie) "movie" else "series") + "_" + id

    suspend fun filterAvailable(items: List<Pair<Int, Boolean>>): Set<Int> {
        val result = HashSet<Int>()
        val need = ArrayList<Pair<Int, Boolean>>()
        for (item in items) {
            when (availCache[availKey(item.first, item.second)]) {
                true -> result.add(item.first)
                false -> Unit
                null -> need.add(item)
            }
        }
        if (need.isEmpty()) return result
        val token = ensureFirebaseToken() ?: return items.map { it.first }.toSet()
        for (chunk in need.chunked(100)) {
            try {
                val docs = chunk.joinToString(",") { (id, isMovie) ->
                    "\"projects/$FB_PROJECT/databases/(default)/documents/contentLinkVersions/${availKey(id, isMovie)}\""
                }
                val body = "{\"documents\":[$docs]}".toByteArray(Charsets.UTF_8)
                val resp = app.post(
                    "https://firestore.googleapis.com/v1/projects/$FB_PROJECT/databases/(default)/documents:batchGet",
                    headers = ANDROID_HEADERS + ("Authorization" to "Bearer $token"),
                    requestBody = body.toRequestBody(JSON_MEDIA),
                    timeout = 25L,
                )
                if (resp.code != 200) {
                    chunk.forEach { result.add(it.first) }
                    continue
                }
                val arr = JSONArray(resp.text)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val found = o.optJSONObject("found")
                    if (found != null) {
                        val name = found.optString("name").substringAfterLast('/')
                        val deleted = found.optJSONObject("fields")?.optJSONObject("deleted")
                            ?.optBoolean("booleanValue", false) ?: false
                        availCache[name] = !deleted
                        if (!deleted) name.substringAfterLast('_').toIntOrNull()?.let { result.add(it) }
                    } else {
                        availCache[o.optString("missing").substringAfterLast('/')] = false
                    }
                }
            } catch (_: Throwable) {
                chunk.forEach { result.add(it.first) }
            }
        }
        return result
    }

    suspend fun currentCatalogUrls(): List<String> = try {
        val session = newSession()
        if (session == null) {
            emptyList()
        } else {
            val body = json(
                "schemaVersion" to 1,
                "deviceId" to session.deviceId,
                "grantId" to (session.grants["catalog"] ?: ""),
                "collection" to "app",
                "documentId" to "db",
            ).toByteArray(Charsets.UTF_8)
            val (st, b) = postRetry(session, "/v2/client/document", body, true, 4)
            val urls = if (st == 200) {
                JSONObject(b).optJSONObject("data")?.optJSONObject("config_gz")
                    ?.optJSONObject("prod")?.optJSONObject("urls")
            } else {
                null
            }
            if (urls == null) {
                emptyList()
            } else {
                listOf("github", "server1", "server2").mapNotNull { key ->
                    urls.optString(key).takeIf { it.startsWith("http") }
                }
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    suspend fun resolveEnvelope(envelope: String): String? = try {
        resolveFresh(envelope)
    } catch (_: Throwable) {
        null
    }
}
