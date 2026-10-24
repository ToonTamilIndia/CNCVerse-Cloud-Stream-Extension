// use an integer for version numbers
version = 34

android {
    defaultConfig {
        // AES/DES key material and websocket secret used by CineTvProvider.
        // Declared here instead of being hardcoded in Kotlin. No env vars.
        buildConfigField("String", "CINETV_SECRET_KEY_ENCRYPTED", "\"MxASAkl/yHTGg+/Tw1R7u96nGqkWsOZ2\"")
        buildConfigField("String", "CINETV_DES_KEY", "\"dsawdf634eebGFHITR5UT9kS0\"")
        buildConfigField("String", "CINETV_DES_IV", "\"32456738\"")
        buildConfigField("String", "CINETV_AES_KEY", "\"0123456789123456\"")
        buildConfigField("String", "CINETV_AES_IV", "\"2015030120123456\"")
        buildConfigField("String", "CINETV_WS_SECRET", "\"00b5f05c40b4f1d91dbc9b3fd8a059ef\"")
    }
}

cloudstream {
    language = "ta"
    // All of these properties are optional, you can safely remove them

    description = "Clone of Cinema, Memesapp , FilmTV etc"
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

    iconUrl = "https://modapk.world/wp-content/uploads/2025/11/Et-90x90.png"
}
