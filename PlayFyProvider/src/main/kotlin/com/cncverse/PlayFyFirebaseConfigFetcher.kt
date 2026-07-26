package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object PlayFyFirebaseConfigFetcher {

    private const val API_KEY = "AIzaSyBIfjXqlm2QLLctnTUQUNK9j9Kf2ybS7yw"
    private const val APP_ID = "1:459539398637:android:96270124df48971af131e4"
    private const val PROJECT_NUMBER = "459539398637"
    private const val PACKAGE_NAME = "com.playfy.tv"
    private const val APP_VERSION = "2.3"
    private const val APP_BUILD = "5"
    private const val APP_INSTANCE_ID = "e8oXwurwSlyewCIEp8rdgs"
    private const val PLATFORM_VERSION = "33"
    private const val SDK_VERSION = "23.1.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RemoteConfigResponse(
        val entries: Map<String, String>? = null,
        val appName: String? = null,
        val state: String? = null,
        val templateVersion: String? = null
    )

    suspend fun fetchRemoteConfig(): Map<String, String>? {
        if (API_KEY.isBlank() || APP_ID.isBlank() || PROJECT_NUMBER.isBlank()) {
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$PROJECT_NUMBER/namespaces/firebase:fetch"

                val payload = """
                    {
                        "appVersion": "$APP_VERSION",
                        "timeZone": "Asia\/Calcutta",
                        "appInstanceIdToken": "",
                        "languageCode": "en-IN",
                        "appBuild": "$APP_BUILD",
                        "appInstanceId": "$APP_INSTANCE_ID",
                        "countryCode": "IN",
                        "analyticsUserProperties": {},
                        "appId": "$APP_ID",
                        "platformVersion": "$PLATFORM_VERSION",
                        "sdkVersion": "$SDK_VERSION",
                        "packageName": "$PACKAGE_NAME"
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

                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    if (!responseBody.isNullOrBlank()) {
                        val configResponse = parseJson<RemoteConfigResponse>(responseBody)
                        return@withContext configResponse.entries
                    }
                }

                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
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
