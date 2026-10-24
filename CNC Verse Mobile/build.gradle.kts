version = 9


dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "ta"
    description = "Netflix, PrimeVideo, Disney+ Hotstar Contents in Multiple Languages (Mobile)"
    authors = listOf("ToonTamilIndia")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    requiresResources = true

    iconUrl = "https://github.com/ToonTamilIndia/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/CNC%20Verse%20Mobile/logo.jpeg"
}
