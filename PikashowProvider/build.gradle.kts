// use an integer for version numbers
version = 30

android {
    buildFeatures {
        viewBinding = true
    }
}
dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    // All of these properties are optional, you can safely remove them
    language = "en"
    description = "Stream movies and series from Pikashow"
    authors = listOf("toonTamilIndia")

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
        "TvSeries",
    )
    requiresResources = false

    iconUrl = "https://raw.githubusercontent.com/toonTamilIndia/CNCVerse-Cloud-Stream-Extension/refs/heads/master/PikashowProvider/logo.png"

}