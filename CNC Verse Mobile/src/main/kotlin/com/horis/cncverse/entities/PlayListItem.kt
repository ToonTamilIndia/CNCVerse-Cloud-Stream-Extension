package com.horis.cncverse.entities

data class PlayListItem(
    val image: String? = null,
    val image2: String? = null,
    val sources: List<Source>,
    val tracks: List<Tracks>? = null,
    val title: String? = null
)
