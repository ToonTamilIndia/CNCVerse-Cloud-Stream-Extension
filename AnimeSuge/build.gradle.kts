// use an integer for version numbers
version = 12

android {
    defaultConfig {
        // VRF RC4 key, MegaPlay CDN token-signing secret and the fallback
        // key/IV seeds for MegaPlay's `enc` payload.
        buildConfigField("String", "ANIMESUGE_VRF_KEY", "\"ysJhV6U27FVIjjuk\"")
        buildConfigField("String", "MEGAPLAY_TOKEN_KEY", "\"MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s\"")
        buildConfigField("String", "MEGAPLAY_FALLBACK_KEY_SEED", "\"i?LMTAx0Q6,:}50U\"")
        buildConfigField("String", "MEGAPLAY_FALLBACK_IV_SEED", "\"W0;27ToaUpl_P%'c\"")
    }
}

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

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
        "Anime",
        "AnimeMovie",
        "OVA"
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://animesuge.cz&size=64"

}