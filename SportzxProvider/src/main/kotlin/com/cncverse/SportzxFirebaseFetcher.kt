package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

object SportzxFirebaseFetcher {
    private const val PACKAGE_NAME = "com.sportzx.live"
    private const val API_KEY = "AIzaSyCTIFo_vw_-XrjzDeE1yG4KuAqGLchzZ0M"
    private const val APP_ID = "1:234785582029:android:f5f9299eaa7a0d73c93284"
    private const val PROJECT_NUMBER = "234785582029"
    private const val APP_VERSION = "2.6"
    private const val APP_BUILD = "15"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val probeClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class RemoteConfigResponse(
        val entries: Map<String, String>? = null
    )

    private suspend fun fetchRemoteConfig(): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$PROJECT_NUMBER/namespaces/firebase:fetch"
            val appInstanceId = UUID.randomUUID().toString().replace("-", "")

            val payload = """
                {
                    "appInstanceId": "$appInstanceId",
                    "appInstanceIdToken": "",
                    "appId": "$APP_ID",
                    "countryCode": "IN",
                    "languageCode": "en-IN",
                    "platformVersion": "33",
                    "timeZone": "Asia/Calcutta",
                    "appVersion": "$APP_VERSION",
                    "appBuild": "$APP_BUILD",
                    "packageName": "$PACKAGE_NAME",
                    "sdkVersion": "23.1.0",
                    "analyticsUserProperties": {}
                }
            """.trimIndent()

            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Android-Package", PACKAGE_NAME)
                .header("X-Goog-Api-Key", API_KEY)
                .header("X-Google-GFE-Can-Retry", "yes")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("SportzxFirebase: RemoteConfig failed HTTP ${response.code}")
                return@withContext null
            }
            parseJson<RemoteConfigResponse>(response.body.string()).entries
        } catch (e: Exception) {
            println("SportzxFirebase: RemoteConfig exception — ${e.message}")
            null
        }
    }

    private suspend fun reachable(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/events.json")
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 13)")
                .build()
            probeClient.newCall(request).execute().use { true }
        } catch (e: Exception) {
            false
        }
    }

    /** Returns the first reachable of `api_url` / `api_url_tv`, falling back to the bundled host. */
    suspend fun getBaseApiUrl(): String? {
        val fallbackUrl = "https://streamtvapp.top"
        val entries = fetchRemoteConfig() ?: return fallbackUrl
        val candidates = listOfNotNull(entries["api_url"], entries["api_url_tv"])
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
        return candidates.firstOrNull { reachable(it) } ?: candidates.firstOrNull() ?: fallbackUrl
    }
}
