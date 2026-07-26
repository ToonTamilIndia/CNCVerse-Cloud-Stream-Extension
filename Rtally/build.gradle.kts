// use an integer for version numbers
version = 46

android {
    buildFeatures {
        buildConfig = true
    }
}

android {
    namespace = "com.cncverse"
}

cloudstream {
    description = "Movie and TV Series provider"
    authors = listOf("Redowan, toonTamilIndia")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "AsianDrama"
    )
    language = "en"

    iconUrl = "https://github.com/toonTamilIndia/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/Rtally/icon.png"
}
