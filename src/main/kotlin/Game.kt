@file:Suppress("EXPERIMENTAL_API_USAGE")
package io.github.potatocurry.shrewd

import com.jessecorbett.diskord.api.model.Message
import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.field
import com.jessecorbett.diskord.dsl.image
import com.jessecorbett.diskord.util.mention
import com.jessecorbett.diskord.util.pngAvatar
import com.jessecorbett.diskord.util.sendMessage
import humanize.Humanize
import io.github.potatocurry.kashoot.api.Question as KahootQuestion
import io.github.potatocurry.kashoot.api.Quiz
import io.github.potatocurry.kwizlet.api.Question as QuizletQuestion
import io.github.potatocurry.kwizlet.api.Set
import kotlinx.coroutines.*
import me.xdrop.fuzzywuzzy.FuzzySearch

val choiceMap = mapOf(
    // Kahoot choices
    "\uD83C\uDDE6" to "A",
    "\uD83C\uDDE7" to "B",
    "\uD83C\uDDE8" to "C",
    "\uD83C\uDDE9" to "D",

    // Cave directions
    "\uD83C\uDDF3" to "N",
    "\uD83C\uDDF8" to "S",
    "\uD83C\uDDEA" to "E",
    "\uD83C\uDDFC" to "W"
)

val emojiMap = choiceMap.entries.associate{ (emoji, choice) -> choice to emoji }

abstract class Game(val channel: ChannelClient, val creator: User) {
    open suspend fun abort(){
        games.remove(channel.channelId)
        logger.trace("Game in channel {} aborted", channel.channelId)
    }
}

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

class QuizletGame(channel: ChannelClient, creator: User, setID: String): TriviaGame(channel, creator) {
    val set: Set = kwizlet.getSet(setID)
    private val questions: Iterator<QuizletQuestion>
    private lateinit var currentQuestion: QuizletQuestion
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

    fun peek(): QuizletQuestion {
        return currentQuestion
    }

    fun next(): QuizletQuestion {
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

class KahootGame(channel: ChannelClient, creator: User, quizID: String): TriviaGame(channel, creator) {
    val quiz: Quiz = kashoot.getQuiz(quizID)
    private val questions: Iterator<KahootQuestion>
    lateinit var currentMessage: String
    lateinit var currentChoices: MutableMap<String, String>
    var isActive = false
    private lateinit var mainJob: Job

    init {
        questions = quiz.questions.shuffled().iterator()
    }

    fun start() = runBlocking {
        mainJob = launch {
            while (questions.hasNext()) {
                sendQuestion()
                delay(2500)
            }

            games.remove(channel.channelId)
            val sortedScores = scores.toSortedMap(compareByDescending { scores[it] })
            channel.sendMessage("") {
                title = "Game Results"
                for (scores in sortedScores.entries.withIndex())
                    field("${scores.index + 1}. ${scores.value.key.username}", "${scores.value.value} points", false)
            }
        }
    }

    private suspend fun sendQuestion() {
        val question = questions.next()
        val send = StringBuilder(question.question)
        for (i in 0 until question.answerCount)
            send.append("\n${(65 + i).toChar()}. ${question.choices[i].answer}")
        currentMessage = channel.sendMessage(send.toString()).apply {
            for (i in 0 until question.answerCount)
                react(emojiMap.getValue((65 + i).toChar().toString()))
        }.id
        currentChoices = mutableMapOf()
        isActive = true

        delay(10000)
        isActive = false
        channel.sendMessage("${Humanize.oxford(question.choices.filter { it.correct }.map { it.answer })} was correct")
        val correctUsers = currentChoices.filterValues { check(question, it) }.keys
        correctUsers.forEach {
            val user = globalClient.discord.getUser(it)
            incScore(user)
        }
    }

    fun addChoice(userId: String, choice: String) {
        currentChoices.putIfAbsent(userId, choice)
    }

    private fun check(question: KahootQuestion, answer: String): Boolean {
        val charAnswer = answer.single()
        if (charAnswer.toInt() - 65 < question.answerCount) {
            return question.choices[charAnswer.toInt() - 65].answer in question.correctAnswers
        }
        return false
    }

    override suspend fun abort() {
        mainJob.cancel()
        super.abort()
    }
}

suspend fun Message.react(emoji: String) {
    globalClient.channels[channelId].addMessageReaction(id, emoji)
}
