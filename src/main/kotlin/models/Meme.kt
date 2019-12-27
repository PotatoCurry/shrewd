package io.github.potatocurry.shrewd.models

import java.time.LocalDateTime
import java.time.ZoneId

data class Meme(
        val link: String,
        val authorId: String,
        val time: LocalDateTime = LocalDateTime.now(ZoneId.of("GMT")),
        val upvotes: Int = 0,
        val downvotes: Int = 0,
        val reports: Int = 0
)
