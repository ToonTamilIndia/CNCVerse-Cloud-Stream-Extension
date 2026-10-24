version = 9


dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

android {
    defaultConfig {
        // AES key/IV, substitution alphabets and the key-derivation seeds used
        // by PlayFyCryptoUtils. Declared here instead of being hardcoded in
        // Kotlin. No environment variables involved.
        buildConfigField("String", "PLAYFY_DEFAULT_LORA", "\"zH7hY9@lO=8uXk#f%mI/VvJd2G10Z5eU+L6Pi&aEbwA4scBCStQ3KyWqRjDgnoMRP\"")
        buildConfigField("String", "PLAYFY_DEFAULT_SIG", "\"Mc1pOdG+rjLRsO8tlGCxcBFqGIU\"")
        buildConfigField("String", "PLAYFY_AES_KEY", "\"hg47Dd84jfK83ncG\"")
        buildConfigField("String", "PLAYFY_AES_IV", "\"47hgD84sjG83nFkH\"")
        buildConfigField("String", "PLAYFY_SHUFFLED_ALPHABET", "\"fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS\"")
        buildConfigField("String", "PLAYFY_STANDARD_ALPHABET", "\"aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ\"")
        buildConfigField("String", "PLAYFY_FALLBACK_KEY", "\"ouAzyvGQqd5yAi5G\"")
        buildConfigField("String", "PLAYFY_FALLBACK_IV", "\"QC4a1NX)XXEHq1bf\"")
    }
}

cloudstream {
    language = "en"
    description = "Watch Live IPTV Channels and Live Events via PlayFy"
    authors = listOf("ToonTamilIndia")

    status = 1
    tvTypes = listOf(
        "Live",
    )
    requiresResources = true

    iconUrl = "https://www.playfy.site/logo.jpg"
}
