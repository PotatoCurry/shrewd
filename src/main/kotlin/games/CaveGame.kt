package io.github.potatocurry.shrewd.games

import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.field
import com.jessecorbett.diskord.util.pngAvatar
import com.jessecorbett.diskord.util.sendMessage
import humanize.Humanize
import io.github.potatocurry.shrewd.games
import io.github.potatocurry.shrewd.logger

class CaveGame(channel: ChannelClient, creator: User): Game(channel, creator) {
    val intro: String
    val initialDescription: String
    val initialExits: List<Any>
    val initialSeed: String
    lateinit var currentMessage: String
    private val locationPaths = mutableListOf<String>()
    private val locationPath: String
        get() = locationPaths.last()

    init {
        val response = khttp.post("https://api.noopschallenge.com/pathbot/start").jsonObject
        intro = response.getString("message")
        initialDescription = response.getString("description")
        initialExits = response.getJSONArray("exits").toList()
        locationPaths += response.getString("locationPath")
        initialSeed = locationPath.split("/").last()
    }

    suspend fun sendCommand(direction: String) {
        val response = khttp.post("https://api.noopschallenge.com$locationPath", json = mapOf("direction" to direction)).jsonObject
        if (response.getString("status") == "finished") {
            channel.sendMessage("") {
                title = "Cave Exploration"
                description = response.getString("description")
                with (creator) {
                    author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                }
                field("Moves", locationPaths.size.toString(), true)
            }
            games.remove(channel.channelId)
            logger.trace("Cave game ended in channel {}", channel.channelId)
        } else {
            locationPaths += response.getString("locationPath")
            val exits = response.getJSONArray("exits").toList()

            if (response.has("description")) {
                currentMessage = channel.sendMessage("") { // TODO: Edit one message instead of sending dozens
                    title = "Cave Exploration"
                    description = response.getString("description")
                    with (creator) {
                        author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                    }
                    field("Exits", Humanize.oxford(exits), true)
                    val visitCount = locationPaths.count { it == locationPath } - 1
                    if (visitCount > 1)
                        field("Warning", "You already visited this area ${Humanize.times(visitCount)} before.", true)
                }.apply {
                    for (exit in exits)
                        react(emojiMap.getValue(exit.toString()))
                }.id
            } else
                channel.sendMessage(response.getString("message"))
        }
    }

    override suspend fun abort() {
        channel.sendMessage("Aborted game")
        super.abort()
    }
}
