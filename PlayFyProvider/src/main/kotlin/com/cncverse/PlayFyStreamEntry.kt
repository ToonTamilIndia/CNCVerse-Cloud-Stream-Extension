package com.cncverse

data class PlayFyStreamEntry(
    val id: Int? = null,
    val title: String? = null,
    val link: String? = null,
    val api: String? = null,
    val type: String? = null,
    val webLink: String? = null,
    val defaultLanguage: String? = null,
    val name: String? = null,
    val url: String? = null,
    val stream_url: String? = null,
    val scheme: Int? = null,
    val drm: String? = null,
    val tokenApi: String? = null,
    val linkTag: String? = null,
    val colorHex: String? = null,
    val headers: Map<String, String>? = null
) {
    val streamUrl: String? get() = url ?: stream_url ?: link
}
