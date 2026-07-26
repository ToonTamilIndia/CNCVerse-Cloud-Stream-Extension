package com.cncverse

data class PlayFyChannel(
    val id: String? = null,
    val title: String? = null,
    val image: String? = null,
    val cat: String? = null,
    val category: String? = null,
    val eventInfo: PlayFyEventInfo? = null,
    val publish: String? = null,
    val formats: List<String>? = null
)
