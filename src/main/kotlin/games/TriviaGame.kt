package io.github.potatocurry.shrewd.games

import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.util.mention
import com.jessecorbett.diskord.util.sendMessage

abstract class TriviaGame(channel: ChannelClient, creator: User): Game(channel, creator) {
    val scores = mutableMapOf<User, Int>()

    fun incScore(user: User) {
        scores[user] = scores.getOrDefault(user, 0) + 1
    }

    override suspend fun abort() {
        val winner = scores.maxBy{ it.value }?.key
        if (winner == null)
            channel.sendMessage("Aborted game - nobody had any points")
        else
            channel.sendMessage("Aborted game - ${winner.mention} had the highest score with ${scores[winner]} points")
        super.abort()
    }
}
