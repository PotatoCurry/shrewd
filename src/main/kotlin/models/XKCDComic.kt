package io.github.potatocurry.shrewd.models

import com.beust.klaxon.Json

data class XKCDComic (
        val month: String,
        val num: Int,
        val link: String,
        val year: String,
        val news: String,

        @Json(name = "safe_title")
        val safeTitle: String,

        val transcript: String,
        val alt: String,
        val img: String,
        val title: String,
        val day: String
)
