package io.github.potatocurry.shrewd.models

data class Room (
        val status: String,
        val message: String,
        val exits: List<String>,
        val description: String,
        val mazeExitDirection: String,
        val mazeExitDistance: Int,
        val locationPath: String
)
