package io.github.potatocurry.shrewd.games

import com.jessecorbett.diskord.api.model.Message
import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import io.github.potatocurry.shrewd.choiceMap
import io.github.potatocurry.shrewd.games
import io.github.potatocurry.shrewd.globalClient
import io.github.potatocurry.shrewd.logger

abstract class Game(val channel: ChannelClient, val creator: User) {
    val emojiMap = choiceMap.entries.associate { (emoji, choice) -> choice to emoji }

    open suspend fun abort() {
        games.remove(channel.channelId)
        logger.trace("Game in channel {} aborted", channel.channelId)
    }

    suspend fun Message.react(emoji: String) {
        globalClient.channels[channelId].addMessageReaction(id, emoji)
    }
}
