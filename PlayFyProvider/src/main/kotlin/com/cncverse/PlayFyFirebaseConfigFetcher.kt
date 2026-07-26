package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.UUID

object PlayFyFirebaseConfigFetcher {

    private const val ANDROID_CERT = "31CD6939D1BEAE32D1B0EF2D9460B170116A1885"
    private const val API_KEY = "AIzaSyDdHIwVAD3XgP5bEwZOcR1QIz7gO5q5EoM"
    private const val APP_ID = "1:239487160038:android:308875071ce6f0fd48f527"
    private const val PACKAGE_NAME = "com.playfy.tv"
    private const val PROJECT_NUMBER = "239487160038"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RemoteConfigResponse(
        val entries: Map<String, String>? = null,
        val state: String? = null,
        val templateVersion: String? = null
    )

    suspend fun fetchRemoteConfig(): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            val appInstanceId = UUID.randomUUID().toString().replace("-", "")
            val payload = """
                {
                    "appVersion": "1.7",
                    "appInstanceIdToken": "",
                    "languageCode": "en-IN",
                    "appBuild": "8",
                    "appInstanceId": "$appInstanceId",
                    "countryCode": "IN",
                    "analyticsUserProperties": {},
                    "appId": "$APP_ID",
                    "platformVersion": "33",
                    "sdkVersion": "22.1.2",
                    "packageName": "$PACKAGE_NAME"
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("https://firebaseremoteconfig.googleapis.com/v1/projects/$PROJECT_NUMBER/namespaces/firebase:fetch")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .header("Connection", "Keep-Alive")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 5 Build/TQ3A.230901.001)")
                .header("X-Android-Cert", ANDROID_CERT)
                .header("X-Android-Package", PACKAGE_NAME)
                .header("X-Firebase-RC-Fetch-Type", "BASE/1")
                .header("X-Goog-Api-Key", API_KEY)
                .header("X-Google-GFE-Can-Retry", "yes")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body.string()
            if (body.isBlank()) return@withContext null
            val cfg = parseJson<RemoteConfigResponse>(body)
            cfg.entries
        } catch (e: Exception) {
            println("PlayFy: Firebase remote config fetch failed: ${e.message}")
            null
        }
    }

    suspend fun getLora(): String? {
        return fetchRemoteConfig()?.get("lora")
    }

    suspend fun getBaseUrl(): String? {
        return fetchRemoteConfig()?.get("baseUrl")?.trimEnd('/')
    }

    suspend fun getBaseApiUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("api_url")?.trimEnd('/')
    }

    suspend fun getTelegramUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("new_telegram_url") ?: entries?.get("telegram_url")
    }

    suspend fun getWebUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("web_url")
    }
}
