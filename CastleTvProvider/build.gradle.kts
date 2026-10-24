// use an integer for version numbers
version = 39

android {
    defaultConfig {
        // Key suffix appended to the API key before AES derivation in
        // CastleTvProvider. Declared here instead of being hardcoded in Kotlin.
        buildConfigField("String", "CASTLE_KEY_SUP_FIXX", "\"T!BgJB\"")
    }
}

cloudstream {
    language = "ta"
    // All of these properties are optional, you can safely remove them

    description = "Castle TV Movies and Series Provider"
    authors = listOf("CNCVerse")

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
        "TvSeries"
    )

    iconUrl = "https://github.com/ToonTamilIndia/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/CastleTvProvider/icon.png"
}

