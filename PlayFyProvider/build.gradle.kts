version = 9


dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "Watch Live IPTV Channels and Live Events via PlayFy"
    authors = listOf("toonTamilIndia")

    status = 1
    tvTypes = listOf(
        "Live",
    )
    requiresResources = true

    iconUrl = "https://www.playfy.site/logo.jpg"
}
