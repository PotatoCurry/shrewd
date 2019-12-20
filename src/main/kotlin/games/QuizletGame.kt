package io.github.potatocurry.shrewd.games

import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.field
import com.jessecorbett.diskord.dsl.image
import com.jessecorbett.diskord.util.sendMessage
import io.github.potatocurry.kwizlet.api.Question
import io.github.potatocurry.kwizlet.api.Set
import io.github.potatocurry.shrewd.games
import io.github.potatocurry.shrewd.kwizlet
import io.github.potatocurry.shrewd.logger
import kotlinx.coroutines.delay
import me.xdrop.fuzzywuzzy.FuzzySearch

class QuizletGame(channel: ChannelClient, creator: User, setID: String): TriviaGame(channel, creator) {
    val set: Set = kwizlet.getSet(setID)
    private val questions: Iterator<Question>
    private lateinit var currentQuestion: Question
    var isActive = false

    init {
        questions = set.questions.shuffled().iterator()
    }

    suspend fun sendQuestion() {
        val question = next()
        channel.sendMessage("") {
            field("Question", question.definition ?: "No definition", false)
            val imageURL = question.imageURL
            if (imageURL != null)
                image(imageURL)
        }
        isActive = true
        logger.trace("Sending question \"{}\" in channel {}", question.definition, channel.channelId)

        delay(10000)
        if (question == peek() && games.containsKey(channel.channelId)) {
            val hint = generateHint(question.term)
            channel.sendMessage("") {
                field("Question", question.definition ?: "No definition", false)
                val imageURL = question.imageURL
                if (imageURL != null)
                    image(imageURL)
                field("Hint", hint, false)
            }
            logger.trace("Sending hint \"{}\" in channel {}", hint, channel.channelId)
        }

        delay(20000)
        if (question == peek() && games.containsKey(channel.channelId)) {
            channel.sendMessage("") {
                field("Question", question.definition ?: "No definition", false)
                val imageURL = question.imageURL
                if (imageURL != null)
                    image(imageURL)
                field("Answer", question.term, false)
            }
            logger.trace("Sending answer \"{}\" in channel {}", question.term, channel.channelId)

            // TODO: Make method to reduce code duplication
            if (hasNext()) {
                delay(2500)
                sendQuestion()
            } else {
                games.remove(channel.channelId)
                val sortedScores = scores.toSortedMap(compareByDescending{ scores[it] })
                channel.sendMessage("") {
                    title = "TriviaGame Results"
                    for (scores in sortedScores.entries.withIndex())
                        field("${scores.index + 1}. ${scores.value.key.username}", "${scores.value.value} points", false)
                }
            }
        }
    }

    fun hasNext(): Boolean {
        return questions.hasNext()
    }

    fun peek(): Question {
        return currentQuestion
    }

    fun next(): Question {
        currentQuestion = questions.next()
        return currentQuestion
    }

    fun check(answer: String): Boolean {
        if (!::currentQuestion.isInitialized || !isActive)
            return false
        val lowerAnswer = answer.toLowerCase()
        val lowerTerm = currentQuestion.term.toLowerCase()
        val ratio = FuzzySearch.ratio(lowerAnswer, lowerTerm)
        val matches = ratio >= 80
        logger.trace("Answer {} compared to {} with a ratio of {}", lowerAnswer, lowerTerm, ratio)
        return matches
    }

    private fun generateHint(answer: String): String {
        val charArray = answer.toCharArray()
        val hint = StringBuilder()
        for (char in charArray.withIndex())
            hint.append(" ", if ((Math.random() * 127).toInt() % 3 == 0 || char.value == ' ') char.value else "\\_")
        return hint.toString()
    }
}
