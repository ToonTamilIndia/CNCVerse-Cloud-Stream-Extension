version = 15


dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "Subscription Management"
    authors = listOf("toonTamilIndia")
    status = 1
    tvTypes = listOf("Settings")
    requiresResources = true
    iconUrl = "https://github.com/ToonTamilIndia/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/SubscriptionManager/icon.png"
}
