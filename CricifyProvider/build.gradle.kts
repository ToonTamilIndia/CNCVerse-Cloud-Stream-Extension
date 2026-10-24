// use an integer for version numbers
version = 66

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

android {
    defaultConfig {
        // AES key material (hex "key:iv") used by CryptoUtils to unwrap the
        // provider's encrypted API payloads. Declared here so the constants are
        // not hardcoded in Kotlin source. No environment variables involved.
        buildConfigField("String", "CRICIFY_PROVIDER_SECRET1", "\"3368487a78594167534749382f68616d:557143766b766a656345497a38343256\"")
        buildConfigField("String", "CRICIFY_PROVIDER_SECRET2", "\"4d7165594743543441594b6f484b7254:6f484b725451755078387a6c386f4a2b\"")
    }
}

cloudstream {
    // All of these properties are optional, you can safely remove them
    language = "en"
    description = "Watch Live sports and TV channels"
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

    iconUrl = "https://cricfy.pro/wp-content/uploads/2024/11/cropped-cricfytv-2.png"

}
