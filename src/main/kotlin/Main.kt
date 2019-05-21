import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.dsl.*
import com.jessecorbett.diskord.util.isFromBot
import com.jessecorbett.diskord.util.mention
import com.jessecorbett.diskord.util.sendMessage
import com.jessecorbett.diskord.util.words
import io.github.potatocurry.kashoot.api.Kashoot
import io.github.potatocurry.kashoot.api.Question
import io.github.potatocurry.kashoot.api.Quiz
import io.github.potatocurry.kwizlet.api.Kwizlet
import io.github.potatocurry.kwizlet.api.Set
import kotlinx.coroutines.delay
import java.net.URI

fun main() {
    val activeGames = mutableMapOf<String, Game>()

    bot(System.getenv("shrewddiscordtoken")) {
        commands(">") {
            command("quizlet") {
                val quizletPath = URI(words[1]).path.split("/")
                val setID = quizletPath.first{ it.isNotEmpty() }
                val quizGame = QuizletGame(author, setID)
                activeGames[channelId] = quizGame
                reply {
                    title = quizGame.set.title
                    description = quizGame.set.description
                    author = EmbedAuthor(quizGame.set.author)
                    field("Total Terms", quizGame.set.termCount.toString(), false)
                }
                delay(2500)
                reply(quizGame.next())
            }
            command("kahoot") {
                val kahootPath = URI(words[1]).path.split("/")
                val quizID = kahootPath.last{ it.isNotEmpty() }
                val kahootGame = KahootGame(author, quizID)
                activeGames[channelId] = kahootGame
                reply {
                    title = kahootGame.quiz.title
                    description = kahootGame.quiz.description
                    author = EmbedAuthor(kahootGame.quiz.creator)
                    field("Total Terms", kahootGame.quiz.questions.size.toString(), false)
                }
                delay(2500)
                val question = kahootGame.next()
                val send = StringBuilder(question.question)
                for (i in 0 until question.answerCount)
                    send.append("\n${(65 + i).toChar()}. ${question.choices[i].answer}")
                reply(send.toString())
            }
            command("abort") {
                val game = activeGames.remove(channelId)
                when {
                    game == null -> reply("No game running in this channel")
                    author != game.creator -> reply("Only the game creator can abort the game")
                    else -> {
                        activeGames.remove(channelId, game)
                        val winner = game.scores.maxBy{ it.value }?.key
                        reply("Aborted game - ${winner?.mention} had the highest score with ${game.scores[winner]} points")
                    }
                }
            }
        }

        messageCreated { message ->
            if (!message.isFromBot && activeGames.containsKey(message.channelId)) {
                if (activeGames[message.channelId]!! is QuizletGame) {
                    val quizGame = activeGames[message.channelId]!! as QuizletGame
                    if (quizGame.check(message.content)) {
                        quizGame.incScore(message.author)
                        message.react("✅")
                        if (quizGame.hasNext()) {
                            delay(2500)
                            message.channel.sendMessage(quizGame.next())
                        } else {
                            activeGames.remove(message.channelId)
                            val sortedScores = quizGame.scores.toSortedMap(compareByDescending{ quizGame.scores[it] })
                            message.channel.sendMessage("") {
                                title = "Game Results"
                                for (scores in sortedScores.entries.withIndex())
                                    field("${scores.index + 1}. ${scores.value.key.username}", "${scores.value.value} points", false)
                            }
                        }
                    }
                } else {
                    val kahootGame = activeGames[message.channelId]!! as KahootGame
                    if (message.content.length == 1 && kahootGame.check(message.content)) {
                        kahootGame.incScore(message.author)
                        message.react("✅")
                        if (kahootGame.hasNext()) {
                            delay(2500)
                            val question = kahootGame.next()
                            val send = StringBuilder(question.question)
                            for (i in 0 until question.answerCount)
                                send.append("\n${(65 + i).toChar()}. ${question.choices[i].answer}")
                            message.channel.sendMessage(send.toString())
                        } else {
                            activeGames.remove(message.channelId)
                            val sortedScores = kahootGame.scores.toSortedMap(compareByDescending{ kahootGame.scores[it] })
                            message.channel.sendMessage("") {
                                title = "Game Results"
                                for (scores in sortedScores.entries.withIndex())
                                    field("${scores.index + 1}. ${scores.value.key.username}", "${scores.value.value} points", false)
                            }
                        }
                    }
                }
            }
        }
    }.block()
}

abstract class Game(val creator: User) {
    val scores = mutableMapOf<User, Int>()

    abstract fun hasNext(): Boolean

    abstract fun next(): Any

    abstract fun check(answer: String): Boolean

    fun incScore(user: User) {
        if (scores[user] == null)
            scores[user] = 0
        scores[user] = scores[user]!! + 1
    }
}

class QuizletGame(creator: User, setID: String): Game(creator) {
    val set: Set // TODO: Rename to quiz and put in Game superclass?
    private val termMap: Map<String, String>
    private val shuffledDefinitions: Iterator<String>
    private lateinit var currentDefinition: String

    init {
        val kwizlet = Kwizlet(System.getenv("QuizletClientID"))
        set = kwizlet.getSet(setID)
        termMap = set.termMap.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        shuffledDefinitions = termMap.values.shuffled().iterator()
    }

    override fun hasNext(): Boolean {
        return shuffledDefinitions.hasNext()
    }

    override fun next(): String {
        currentDefinition = shuffledDefinitions.next()
        return currentDefinition
    }

    override fun check(answer: String): Boolean {
        return termMap[answer] == currentDefinition
    }
}

class KahootGame(creator: User, quizID: String): Game(creator) {
    val quiz: Quiz
    private val questions: Iterator<Question>
    private lateinit var currentQuestion: Question

    init {
        val kashoot = Kashoot()
        quiz = kashoot.getQuiz(quizID)
        questions = quiz.questions.shuffled().iterator()
    }

    override fun hasNext(): Boolean {
        return questions.hasNext()
    }

    override fun next(): Question {
        currentQuestion = questions.next()
        return currentQuestion
    }

    override fun check(answer: String): Boolean {
        val charAnswer = answer.toUpperCase().toCharArray().single()
        if (charAnswer.isLetter() && charAnswer.toInt() - 65 < currentQuestion.answerCount) {
            return currentQuestion.correctAnswers.contains(currentQuestion.choices[charAnswer.toInt() - 65].answer)
        }
        return false
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
