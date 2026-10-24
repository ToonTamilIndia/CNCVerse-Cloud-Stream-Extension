// use an integer for version numbers
version = 15


dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

android {
    defaultConfig {
        // AES key/IV + substitution alphabets used by LivXowCryptoUtils to
        // unwrap the provider's encrypted API payloads.
        buildConfigField("String", "LIVXOW_AES_KEY", "\"M8mkKlNL75K4nl15\"")
        buildConfigField("String", "LIVXOW_AES_IV", "\"kN7m5Kl1pN5nk4xK\"")
        buildConfigField("String", "LIVXOW_SHUFFLED_ALPHABET", "\"fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS\"")
        buildConfigField("String", "LIVXOW_STANDARD_ALPHABET", "\"aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ\"")
    }
}

cloudstream {
    language = "en"
    description = "Watch Live Sports and TV Channels via LivXow"
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
        "Live",
    )
    requiresResources = true

    iconUrl = "https://www.livxow.site/logo.jpg"
}
