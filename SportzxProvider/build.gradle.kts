// use an integer for version numbers
version = 20

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

android {
    defaultConfig {
        // Key-derivation password, alphabet and HKDF material used by
        // SportzxCryptoUtils. Declared here instead of being hardcoded in
        // Kotlin. No environment variables involved.
        buildConfigField("String", "SPORTZX_APP_PASSWORD", "\"oAR80SGuX3EEjUGFRwLFKBTiris=\"")
        buildConfigField("String", "SPORTZX_CHARSET", "\"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+!@#\$%&=\"")
        buildConfigField("String", "SPORTZX_PRK", "\"d1a7ebbaed93b2f68ac092ade2b0075d917634447a3519b41e817ad631020053\"")
        buildConfigField("String", "SPORTZX_CTX_HASH", "\"1676ec7db4771b0d826d70369b579684b182d2c0133be041bdd55f5d6d79a98b\"")
    }
}

cloudstream {
    language = "en"
    description = "Watch Live Sports Events via SportzX"
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

    iconUrl = "https://sportzxlive.com/wp-content/uploads/2026/04/cropped-logo-1-2048x1611-1-1024x806.png"

}
