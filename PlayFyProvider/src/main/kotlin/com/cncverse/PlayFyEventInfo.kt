package com.cncverse

data class PlayFyEventInfo(
    val teamAName: String? = null,
    val teamBName: String? = null,
    val teamAFlag: String? = null,
    val teamBFlag: String? = null,
    val eventName: String? = null,
    val eventLogo: String? = null,
    val category: String? = null,
    val date: String? = null,
    val time: String? = null,
    val end_date: String? = null,
    val end_time: String? = null,
    val links: String? = null,
    val visible: Boolean? = null,
    val priority: Int? = null,
    val link_names: List<Map<String, String>>? = null
) {
    val displayName: String get() {
        val a = teamAName?.trim()
        val b = teamBName?.trim()
        return when {
            !a.isNullOrBlank() && !b.isNullOrBlank() && a != b -> "$a vs $b"
            !a.isNullOrBlank() -> a
            !eventName.isNullOrBlank() -> eventName
            else -> ""
        }
    }

    val categoryName: String get() = category?.trim() ?: "Sports"

    val thumbUrl: String? get() = eventLogo

    val streamSlug: String get() = links?.removeSuffix(".txt") ?: ""

    fun startTimeString(): String? = toIsoString(date, time)
    fun endTimeString(): String? = toIsoString(end_date, end_time)

    companion object {
        fun toIsoString(date: String?, time: String?): String? {
            if (date == null || time == null) return null
            val parts = date.split("/")
            if (parts.size != 3) return null
            val (day, month, year) = parts
            return "$year/$month/$day $time +0000"
        }
    }
}
