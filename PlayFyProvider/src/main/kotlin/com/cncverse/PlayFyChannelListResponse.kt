package com.cncverse

data class PlayFyChannelListResponse(
    val channels: List<PlayFyChannel>? = null,
    val cats: List<PlayFyCatFilter>? = null
)
