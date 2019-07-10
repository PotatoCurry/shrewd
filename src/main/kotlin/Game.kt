package io.github.potatocurry.shrewd

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
import kotlinx.coroutines.delay
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
        if (!::currentQuestion.isInitialized)
            return false
        val lowerAnswer = answer.toLowerCase()
        val lowerTerm = currentQuestion.term.toLowerCase()
        val ratio = FuzzySearch.ratio(lowerAnswer, lowerTerm)
        val matches = ratio >= 80
        logger.trace("Answer {} ${if (matches) "matches" else "does not match"} {} with a ratio of {}", lowerAnswer, lowerTerm, ratio)
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
    private lateinit var currentQuestion: KahootQuestion

    init {
        questions = quiz.questions.shuffled().iterator()
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

    fun check(answer: String): Boolean {
        if (!::currentQuestion.isInitialized)
            return false
        val charAnswer = answer.toUpperCase().toCharArray().single()
        if (charAnswer.isLetter() && charAnswer.toInt() - 65 < currentQuestion.answerCount) {
            return currentQuestion.correctAnswers.contains(currentQuestion.choices[charAnswer.toInt() - 65].answer)
        }
        return false
        // TODO: Add logging when converted to emoji reaction answers
    }

// TODO: Revisit emoji kahoot reactions when Diskord adds rich reactions or use newReaction events

//    suspend fun start(channel: ChannelClient) {
//        channel.sendMessage("Starting game @here") {
//            title = quiz.title
//            description = quiz.description
//            author = EmbedAuthor(quiz.creator)
//            field("Total Terms", quiz.questions.size.toString(), false)
//        }
//        while (questions.hasNext()) {
//            val question = questions.next()
//            channel.sendMessage(question.question)
//            delay(2500)
//            val send = StringBuilder()
//            for (i in 0 until question.answerCount)
//                send.append("${(65 + i).toChar()}. ${question.choices[i].answer}\n") // TODO: Use regional indicator emoji
//            send.append("React your answer below!")
//            val message = channel.sendMessage(send.toString())
//            delay(5000)
//            val reactions = message.reactions
//            channel.sendMessage("\"${question.correctAnswer}\" was the correct answer") // TODO: Make DSL with everyone's results
//
//        }
//    }
}
