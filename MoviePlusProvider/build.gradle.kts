// use an integer for version numbers
version = 2

android {
    defaultConfig {
        // TMDB key, Firebase web key and Android signing-cert header used by MoviePlusProvider.
        // Declared here instead of being hardcoded in Kotlin. No env vars.
        buildConfigField("String", "MOVIEPLUS_TMDB_API_KEY", "\"ec4ff1b6182572d3e74735e74ca3a8ef\"")
        buildConfigField("String", "MOVIEPLUS_FIREBASE_API_KEY", "\"AIzaSyCVuJ2EQpizrj_dPJqfNUIMXOt_m8Tl9MM\"")
        buildConfigField("String", "MOVIEPLUS_ANDROID_CERT", "\"BA89250E17180469CBB5CC9DFA087AE59FD7E008\"")
    }
}

cloudstream {
    description = "Movie! Plus — Spanish/Latino movies & series (TMDB metadata, Yandex.Disk sources, no login)"
    authors = listOf("ToonTamilIndia")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "mx"
    iconUrl = "https://raw.githubusercontent.com/ToonTamilIndia/CNCVerse-Cloud-Stream-Extension/master/MoviePlusProvider/icon.png"
}
