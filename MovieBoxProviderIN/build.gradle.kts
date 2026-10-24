// use an integer for version numbers
version = 49

android {
    defaultConfig {
        // Base64 HMAC-MD5 signing secrets used by MovieBoxProviderIN.
        // Declared here instead of being hardcoded in Kotlin. No env vars.
        buildConfigField("String", "MOVIEBOXIN_SECRET_KEY_DEFAULT", "\"NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==\"")
        buildConfigField("String", "MOVIEBOXIN_SECRET_KEY_ALT", "\"WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ==\"")
    }
}

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "Multi Language Movies and Series Provider (Local homepage based on region)"
    authors = listOf("ToonTamilIndia")

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

    iconUrl = "https://github.com/ToonTamilIndia/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/MovieBoxProviderIN/icon.png"
}
