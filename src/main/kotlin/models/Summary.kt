package io.github.potatocurry.shrewd.models

import com.beust.klaxon.Json

data class Summary (
        @Json(name = "sm_api_keyword_array")
        val keywords: List<String>,

        @Json(name = "sm_api_character_count")
        val characterCount: String,

        @Json(name = "sm_api_content_reduced")
        val contentReduced: String,

        @Json(name = "sm_api_title")
        val title: String,

        @Json(name = "sm_api_content")
        val content: String,

        @Json(name = "sm_api_limitation")
        val limitation: String
)
