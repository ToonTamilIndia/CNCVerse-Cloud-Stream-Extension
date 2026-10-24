// use an integer for version numbers
version = 53

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

android {
    defaultConfig {
        // AES key material for the v2.3 / legacy / v2.5 payload formats used by
        // SKLiveCryptoUtils. Declared here instead of being hardcoded in Kotlin.
        // No environment variables involved.
        buildConfigField("String", "SKTECH_V23_KEY", "\"ST00ZGt3UGlPdVJP\"")
        buildConfigField("String", "SKTECH_V23_IV", "\"d2WT1lR4ckEvUsdk\"")
        buildConfigField("String", "SKTECH_LEGACY_AES_KEY", "\"6c326c356b4237784335715031724b31\"")
        buildConfigField("String", "SKTECH_LEGACY_AES_IV", "\"70314b356e50377542386848316c3139\"")
        buildConfigField("String", "SKTECH_V25_KEY1", "\"V9LQR42pNKc7smaX\"")
        buildConfigField("String", "SKTECH_V25_KEY2", "\"d2WT1lR4ckEvUsdk\"")
        buildConfigField("String", "SKTECH_V25_IV", "\"I=4dkwPiOuROD+pD\"")
        buildConfigField("String", "SKTECH_FALLBACK_AES_KEY", "\"l2l5kB7xC5qP1rK1\"")
        buildConfigField("String", "SKTECH_FALLBACK_AES_IV", "\"p1K5nP7uB8hH1l19\"")
    }
}

cloudstream {
    // All of these properties are optional, you can safely remove them
    language = "en"
    description = "Watch Live sports and TV channels via SK Tech"
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

    iconUrl = "https://appteka.store/get/a20p17EuCKFD08AaFsfH3JSJ6OU30ccp18WrKaNDze4LqOsDArhKj13-f-SjAayiWJH6P6pv9AlzhcfzEXlmRokU-I4=/726bbb91e36da425f55efce72cccb3a82df1d265.png"

}
