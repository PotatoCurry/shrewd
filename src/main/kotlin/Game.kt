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
            if (response.has("description"))
                channel.sendMessage("") {
                    title = "Cave Exploration"
                    description = response.getString("description")
                    with (creator) {
                        author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                    }
                    field("Exits", Humanize.oxford(response.getJSONArray("exits").toList()), true)
                    val visitCount = locationPaths.count { it == locationPath }
                    if (visitCount > 1)
                        field("Warning", "You already visited this area ${Humanize.times(visitCount)} before.", true)
                }
            else
                channel.sendMessage(response.getString("message"))
            locationPaths += response.getString("locationPath")
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
    private var isActive = false

    init {
        questions = set.questions.shuffled().iterator()
    }

    suspend fun sendQuestion() {
        val question = next()
        channel.sendMessage("") {
            field("Question", question.definition, false)
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
                field("Question", question.definition, false)
                val imageURL = question.imageURL
                if (imageURL != null)
                    image(imageURL)
                field("Hint", hint, false)
            }
            logger.trace("Sending hint \"{}\" in channel {}", hint, channel.channelId)
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
        if (matches)
            isActive = false
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
    private lateinit var currentQuestion: KahootQuestion
    lateinit var currentChoices: MutableMap<String, String>
    var isActive = false
    private lateinit var mainJob: Job

    init {
        questions = quiz.questions.shuffled().iterator()
    }

    fun start() = runBlocking {
        mainJob = launch {
            while (hasNext()) {
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
        currentQuestion = next()
        val send = StringBuilder(currentQuestion.question)
        for (i in 0 until currentQuestion.answerCount)
            send.append("\n${(65 + i).toChar()}. ${currentQuestion.choices[i].answer}")
        val message = channel.sendMessage(send.toString())
        for (i in 0 until currentQuestion.answerCount)
            message.react(emojiMap.getValue((65 + i).toChar().toString()))
        currentMessage = message.id
        currentChoices = mutableMapOf()
        isActive = true

        delay(10000)
        isActive = false
        channel.sendMessage("${Humanize.oxford(currentQuestion.choices.filter { it.correct }.map { it.answer })} was correct")
        val correctUsers = currentChoices.filterValues { check(it) }.keys
        correctUsers.forEach {
            val user = globalClient.discord.getUser(it)
            incScore(user)
        }
    }

    fun hasNext(): Boolean {
        return questions.hasNext()
    }

    fun peek(): KahootQuestion {
        return currentQuestion
    }

    fun next(): KahootQuestion {
        currentQuestion = questions.next()
        return currentQuestion
    }

    val choiceMap = mapOf(
        "\uD83C\uDDE6" to "A",
        "\uD83C\uDDE7" to "B",
        "\uD83C\uDDE8" to "C",
        "\uD83C\uDDE9" to "D"
    )

    val emojiMap = choiceMap.entries.associate{(emoji,choice)-> choice to emoji}

    fun addChoice(userId: String, choice: String) {
        println("Putting in currentChoices")
        currentChoices.putIfAbsent(userId, choice)
    }

    fun check(answer: String): Boolean {
        if (!::currentQuestion.isInitialized)
            return false
        val charAnswer = answer.single()
        if (charAnswer.toInt() - 65 < currentQuestion.answerCount) {
            return currentQuestion.choices[charAnswer.toInt() - 65].answer in currentQuestion.correctAnswers
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
