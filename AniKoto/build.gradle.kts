version = 12

android {
    defaultConfig {
        // MegaPlay CDN token-signing secret and the fallback key/IV seeds
        // for its `enc` payload.
        buildConfigField("String", "MEGAPLAY_TOKEN_KEY", "\"MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s\"")
        buildConfigField("String", "MEGAPLAY_FALLBACK_KEY_SEED", "\"i?LMTAx0Q6,:}50U\"")
        buildConfigField("String", "MEGAPLAY_FALLBACK_IV_SEED", "\"W0;27ToaUpl_P%'c\"")
    }
}

cloudstream {
    language = "en"
    description = "Anime Provider"
    authors = listOf("ToonTamilIndia")
    status = 1
    tvTypes = listOf("Anime", "TvSeries")
    requiresResources = false
    iconUrl = "https://anikototv.to/AnikotoTheme/assets/images/favicon.png"
}
