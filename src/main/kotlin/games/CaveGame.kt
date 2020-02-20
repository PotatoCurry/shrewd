package io.github.potatocurry.shrewd.games

import com.beust.klaxon.Json
import com.beust.klaxon.KlaxonException
import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.field
import com.jessecorbett.diskord.util.pngAvatar
import com.jessecorbett.diskord.util.sendMessage
import humanize.Humanize
import io.github.potatocurry.shrewd.games
import io.github.potatocurry.shrewd.klaxon
import io.github.potatocurry.shrewd.logger
import io.github.potatocurry.shrewd.models.Room

class CaveGame(channel: ChannelClient, creator: User): Game(channel, creator) {
    var room: Room
    lateinit var currentMessage: String
    private val locationPaths = mutableListOf<String>()

    init {
        val caveJson = khttp.post("https://api.noopschallenge.com/pathbot/start").text
        room = klaxon.parse<Room>(caveJson)!!
    }

    suspend fun sendCommand(direction: String) {
        val roomJson = khttp.post(
                "https://api.noopschallenge.com${room.locationPath}",
                json = mapOf("direction" to direction)
        ).text
        try {
            room = klaxon.parse<Room>(roomJson)!!
            if (room.status == "finished") {
                channel.sendMessage("") {
                    title = "Cave Exploration"
                    description = room.description
                    author = EmbedAuthor(creator.username, authorImageUrl = creator.pngAvatar())
                    field("Moves", locationPaths.size.toString(), true)
                }
                games.remove(channel.channelId)
                logger.trace("Cave game ended in channel {}", channel.channelId)
            } else {
                locationPaths += room.locationPath
                currentMessage = channel.sendMessage("") {
                    // TODO: Edit one message instead of sending dozens
                    title = "Cave Exploration"
                    description = room.description
                    author = EmbedAuthor(creator.username, authorImageUrl = creator.pngAvatar())
                    field("Exits", Humanize.oxford(room.exits), true)
                    val visitCount = locationPaths.count { it == room.locationPath } - 1
                    if (visitCount > 0)
                        field("Warning", "You already visited this area ${Humanize.times(visitCount)} before.", true)
                }.apply {
                    for (exit in room.exits)
                        react(emojiMap.getValue(exit))
                }.id
            }
        } catch (e: KlaxonException) {
            channel.sendMessage("Congratulations! You have escaped the maze.") // TODO: Figure out a better solution
//            logger.warn("Error parsing room JSON {}", roomJson)
        }
    }

    override suspend fun abort() {
        channel.sendMessage("Aborted game")
        super.abort()
    }
}
