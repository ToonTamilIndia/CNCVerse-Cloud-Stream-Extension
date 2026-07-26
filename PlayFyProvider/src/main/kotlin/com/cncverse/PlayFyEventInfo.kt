package com.cncverse

data class PlayFyEventInfo(
    val eventName: String? = null,
    val teamA: String? = null,
    val teamB: String? = null,
    val teamAFlag: String? = null,
    val teamBFlag: String? = null,
    val eventBanner: String? = null,
    val isPinned: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null
)
