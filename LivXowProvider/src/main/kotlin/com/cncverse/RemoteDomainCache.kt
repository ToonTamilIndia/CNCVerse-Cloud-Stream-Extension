package com.cncverse

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Caches a remotely resolved value (e.g. an API base URL from Firebase) with a TTL.
 * Failed refreshes fall back to the last known value, or [get]'s fallback, for a shorter TTL.
 */
internal class ExpiringRemoteValue(
    private val ttlMs: Long = 30 * 60 * 1000L,
    private val fallbackTtlMs: Long = 2 * 60 * 1000L,
    private val minRetryMs: Long = 60 * 1000L
) {
    @Volatile
    private var value: String? = null

    @Volatile
    private var expiresAt = 0L

    @Volatile
    private var lastAttempt = 0L

    private val mutex = Mutex()

    fun peek(): String? = value

    suspend fun get(fetch: suspend () -> String?, fallback: suspend () -> String): String {
        value?.let { if (System.currentTimeMillis() < expiresAt) return it }
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val current = value
            if (current != null && (now < expiresAt || now - lastAttempt < minRetryMs)) {
                return@withLock current
            }
            lastAttempt = now
            val fresh = try {
                fetch()
            } catch (e: Exception) {
                null
            }?.takeIf { it.isNotBlank() }
            if (fresh != null) {
                value = fresh
                expiresAt = now + ttlMs
                fresh
            } else {
                val resolved = current ?: fallback()
                value = resolved
                expiresAt = now + fallbackTtlMs
                resolved
            }
        }
    }

    fun invalidate() {
        expiresAt = 0L
    }

    fun reset() {
        expiresAt = 0L
        lastAttempt = 0L
    }
}

/** Expires [domain] when a request to its host fails, so the next call re-resolves it. */
internal class StaleDomainInterceptor(private val domain: ExpiringRemoteValue) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val ours = domain.peek()?.toHttpUrlOrNull()?.host?.equals(host, ignoreCase = true) == true
        try {
            val response = chain.proceed(chain.request())
            if (ours && response.code >= 400) domain.invalidate()
            return response
        } catch (e: IOException) {
            if (ours) domain.invalidate()
            throw e
        }
    }
}
