// use an integer for version numbers
version = 35

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

android {
    defaultConfig {
        // Base64 AES key/IV pairs (old + primary payload formats) and the
        // substitution alphabets used by PlayZTVCryptoUtils. Declared here
        // instead of being hardcoded in Kotlin. No environment variables.
        buildConfigField("String", "PLAYZ_AES_KEY", "\"bTVLbDVuazR4SzFrTjdwTg==\"")
        buildConfigField("String", "PLAYZ_AES_IV", "\"azVLNG5NOG1LbE5MN2wxNQ==\"")
        buildConfigField("String", "PLAYZ_PRIMARY_AES_KEY", "\"Yi8xam1sNW5rNHg1azdwTg==\"")
        buildConfigField("String", "PLAYZ_PRIMARY_AES_IV", "\"MTRuTWs4bU41S2w1S0w3bA==\"")
        buildConfigField("String", "PLAYZ_SUB_FROM", "\"aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ\"")
        buildConfigField("String", "PLAYZ_SUB_TO", "\"fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS\"")
    }
}

cloudstream {
    language = "en"
    description = "Watch Live TV channels & sports via PlayZTV"
    authors = listOf("ToonTamilIndia")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Live",
    )
    requiresResources = true

    iconUrl = "https://www.google.com/s2/favicons?domain=playztv.top&sz=%size%"
}
