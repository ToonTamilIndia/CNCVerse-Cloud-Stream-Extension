package com.cncverse.vegamovies

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf

class RogmoviesProvider : VegaMoviesProvider() {
    override var mainUrl = "https://rogmovies.one"
    override var name = "Rogmovies"
    override val domainKey = "rogmovies"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "/page/%d/" to "Home",
        "/category/web-series/netflix/page/%d/" to "Netflix",
        "/category/web-series/disney-plus-hotstar/page/%d/" to "Disney Plus Hotstar",
        "/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime",
        "/category/web-series/mx-original/page/%d/" to "MX Original"
    )
}
