package com.cncverse

data class SportzxVODLoadData(
    val id: String,
    val title: String,
    val poster: String? = null,
    val cat: String? = null,
    val formats: List<String>
)
