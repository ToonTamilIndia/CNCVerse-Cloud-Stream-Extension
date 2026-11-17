package com.cncverse

import android.util.Base64
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val ANDROID_HEADERS = mapOf(
        "X-Android-Package" to "com.dvex.movp",
        "X-Android-Cert" to BuildConfig.MOVIEPLUS_ANDROID_CERT,
    )

    private val lock = Mutex()
    private var keyPair: KeyPair? = null
    private var spkiB64 = ""
    private var deviceId = UUID.randomUUID().toString()
    private var sessionToken: String? = null
    private var grants: Map<String, String> = emptyMap()
    private var sessionAtMs = 0L
    private const val sessionTtlMs = 600_000L
    private var firebaseIdToken: String? = null
    private var idTokenAtMs = 0L
    private const val idTokenTtlMs = 720_000L

    private val seasonKeyRe = Regex("(?i)temporada\\s*0*(\\d+)")
    private val sxeRe = Regex("(?i)S0*(\\d+)\\s*E0*(\\d+)")
    private val availCache = ConcurrentHashMap<String, Boolean>()

    data class ResolvedLink(val quality: String, val size: String, val url: String)

    data class EpisodeLink(val season: Int, val episode: Int, val quality: String, val envelope: String)

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun ensureKey() {
        if (keyPair != null) return
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        keyPair = kp
        spkiB64 = b64(kp.public.encoded)
        deviceId = UUID.randomUUID().toString()
    }

    private fun signB64(msg: String): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair!!.private)
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

    private fun hybridHeaders(path: String, bodyBytes: ByteArray): Map<String, String> {
        val token = sessionToken ?: ""
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val canon = "MOVP-HYBRID-REQUEST-V1\nPOST\n$path\n${sha256Hex(bodyBytes)}\n$nonce\n" +
            sha256Hex(token.toByteArray(Charsets.UTF_8))
        return mapOf(
            "X-MOVP-Hybrid-Session" to token,
            "X-MOVP-Hybrid-Nonce" to nonce,
            "X-MOVP-Hybrid-Signature" to signB64(canon),
        )
    }

    private suspend fun postRetry(
        path: String,
        bodyBytes: ByteArray,
        hybrid: Boolean,
        tries: Int = 6,
    ): Pair<Int, String> {
        var last = -1 to ""
        repeat(tries) {
            val headers = if (hybrid) hybridHeaders(path, bodyBytes) else emptyMap()
            last = post(path, bodyBytes, headers)
            if (last.first == 200) return last
            delay(2500)
        }
        return last
    }

    private suspend fun connect(): Boolean {
        ensureKey()
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
                    "publicKeySpki" to spkiB64,
                    "challenge" to challenge,
                    "deviceId" to deviceId,
                    "signature" to signB64("MOVP-HYBRID-SESSION-V1\n$challenge\n$deviceId"),
                ).toByteArray(Charsets.UTF_8)
                val (sst, sb) = post("/v2/client/session", sBody, emptyMap())
                if (sst == 200) {
                    sessionToken = JSONObject(sb).getString("sessionToken")
                    return true
                }
            } catch (_: Throwable) {
            }
            delay(2500)
        }
        return false
    }

    private suspend fun bootstrap(): Boolean {
        val body = json(
            "schemaVersion" to 1,
            "deviceId" to deviceId,
            "channel" to "prod",
            "settingsProfile" to 7,
        ).toByteArray(Charsets.UTF_8)
        val (st, b) = postRetry("/v2/client/bootstrap", body, true)
        if (st != 200) return false
        val g = JSONObject(b).optJSONObject("admission")?.optJSONObject("grants") ?: return false
        val m = HashMap<String, String>()
        for (k in g.keys()) m[k] = g.getString(k)
        grants = m
        return grants.containsKey("catalog") && grants.containsKey("catalog-value")
    }

    private suspend fun ensureSession(): Boolean {
        if (sessionToken != null && grants.isNotEmpty() && System.currentTimeMillis() - sessionAtMs < sessionTtlMs) {
            return true
        }
        if (!connect()) return false
        if (!bootstrap()) return false
        sessionAtMs = System.currentTimeMillis()
        return true
    }

    private suspend fun playback(envelope: String): String? {
        val body = json(
            "schemaVersion" to 1,
            "deviceId" to deviceId,
            "grantId" to (grants["catalog-value"] ?: ""),
            "token" to envelope,
            "issuedAtMs" to System.currentTimeMillis() - 1000L,
            "resolveProvider" to true,
        ).toByteArray(Charsets.UTF_8)
        val (st, b) = postRetry("/v2/client/playback", body, true)
        if (st != 200) return null
        val o = JSONObject(b)
        if (o.optString("status") != "resolved" || !o.optBoolean("ok", false)) return null
        return o.optString("value").takeIf { it.startsWith("http") }
    }

    suspend fun resolveMovie(movieId: Int, fallbackEnvelopes: List<String> = emptyList()): List<ResolvedLink> =
        lock.withLock {
            try {
                if (!ensureSession()) {
                    sessionToken = null
                    grants = emptyMap()
                    if (!ensureSession()) return@withLock emptyList()
                }
                val body = json(
                    "schemaVersion" to 1,
                    "deviceId" to deviceId,
                    "grantId" to (grants["catalog"] ?: ""),
                    "movieId" to movieId,
                ).toByteArray(Charsets.UTF_8)
                val (st, b) = postRetry("/v2/client/movie-variants", body, true, 3)
                val variants = if (st == 200) {
                    JSONObject(b).optJSONObject("movieLinks")?.optJSONArray("variants")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                        ?: emptyList()
                } else {
                    emptyList()
                }
                val entries = if (variants.isEmpty() && fallbackEnvelopes.isNotEmpty()) fallbackEnvelopes else variants
                val out = ArrayList<ResolvedLink>()
                val seen = HashSet<String>()
                for (entry in entries) {
                    val parts = entry.split(",_,", limit = 3)
                    if (parts.size != 3) continue
                    val url = try {
                        playback(parts[2].trim())
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    if (seen.add(url)) out.add(ResolvedLink(parts[0].trim(), parts[1].trim(), url))
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }

    private suspend fun ensureFirebaseToken(): String? {
        firebaseIdToken?.let { if (System.currentTimeMillis() - idTokenAtMs < idTokenTtlMs) return it }
        if (!ensureSession()) return null
        val authBody = json(
            "schemaVersion" to 1,
            "deviceId" to deviceId,
            "action" to "guest",
            "payload" to "__PAYLOAD__",
        ).replace(
            "\"__PAYLOAD__\"",
            json("deviceKey" to deviceId, "appVersion" to "17.3", "platform" to "android"),
        ).toByteArray(Charsets.UTF_8)
        val (ast, ab) = postRetry("/v2/client/auth", authBody, true, 4)
        if (ast != 200) return null
        val customToken = JSONObject(ab).optString("token").takeIf { it.isNotBlank() } ?: return null
        val signInBody = json("token" to customToken, "returnSecureToken" to true).toByteArray(Charsets.UTF_8)
        val resp = app.post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=${BuildConfig.MOVIEPLUS_FIREBASE_API_KEY}",
            headers = ANDROID_HEADERS,
            requestBody = signInBody.toRequestBody(JSON_MEDIA),
            timeout = 25L,
        )
        if (resp.code != 200) return null
        firebaseIdToken = JSONObject(resp.text).optString("idToken").takeIf { it.isNotBlank() }
        idTokenAtMs = System.currentTimeMillis()
        return firebaseIdToken
    }

    suspend fun episodeEnvelopes(seriesId: Int): List<EpisodeLink> = lock.withLock {
        try {
            val token = ensureFirebaseToken() ?: return@withLock emptyList()
            val url = "https://firestore.googleapis.com/v1/projects/$FB_PROJECT/databases/(default)/documents/tv/$seriesId"
            val resp = app.get(url, headers = ANDROID_HEADERS + ("Authorization" to "Bearer $token"), timeout = 25L)
            if (resp.code != 200) return@withLock emptyList()
            val fields = JSONObject(resp.text).optJSONObject("fields") ?: return@withLock emptyList()
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

    suspend fun resolveEnvelope(envelope: String): String? = lock.withLock {
        try {
            if (!ensureSession()) {
                sessionToken = null
                grants = emptyMap()
                if (!ensureSession()) return@withLock null
            }
            playback(envelope)
        } catch (_: Throwable) {
            null
        }
    }
}
