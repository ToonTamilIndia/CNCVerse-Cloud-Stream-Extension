package com.cncverse

data class PlayFyStreamEntry(
    val name: String?,
    val link: String?,
    val url: String?,
    val stream_url: String?,
    val scheme: Int?,
    val api: String?,
    val drm: String?,
    val tokenApi: String?,
    val linkTag: String?,
    val colorHex: String?,
    val headers: Map<String, String>? = null
) {
    val streamUrl: String? get() = url ?: stream_url ?: link
}
