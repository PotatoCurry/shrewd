import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.dsl.*
import com.jessecorbett.diskord.util.mention
import com.jessecorbett.diskord.util.sendMessage
import com.jessecorbett.diskord.util.words
import io.github.potatocurry.kashoot.api.Kashoot
import io.github.potatocurry.kashoot.api.Question
import io.github.potatocurry.kashoot.api.Quiz
import io.github.potatocurry.kwizlet.api.Kwizlet
import io.github.potatocurry.kwizlet.api.Set

fun main() {
    val activeGames = mutableMapOf<String, Game>()

    bot(System.getenv("shrewddiscordtoken")) {
        commands(">") {
            command("quizlet") {
                val quizGame = QuizletGame(author, words[1])
                activeGames[channelId] = quizGame
                reply {
                    title = quizGame.set.title
                    description = quizGame.set.description
                    author = EmbedAuthor(quizGame.set.author)
                    field("Total Terms", quizGame.set.termCount.toString(), false)
                }
                reply(quizGame.next())
            }
            command("kahoot") {
                val kahootGame = KahootGame(author, words[1])
                activeGames[channelId] = kahootGame
                reply {
                    title = kahootGame.quiz.title
                    description = kahootGame.quiz.description
                    author = EmbedAuthor(kahootGame.quiz.creator)
                    field("Total Terms", kahootGame.quiz.questions.size.toString(), false)
                }
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
            if (activeGames.containsKey(message.channelId)) {
                if (activeGames[message.channelId]!! is QuizletGame) {
                    val quizGame = activeGames[message.channelId]!! as QuizletGame
                    if (quizGame.check(message.content)) {
                        quizGame.incScore(message.author)
                        message.react("✅")
                        if (quizGame.hasNext()) {
                            message.channel.sendMessage(quizGame.next())
                        } else {
                            activeGames.remove(message.channelId)
                            // TODO: Make embed for this
                            val winner = quizGame.scores.maxBy{ it.value }?.key
                            message.channel.sendMessage("${winner?.mention} won with ${quizGame.scores[winner]} points!")
                        }
                    }
                } else {
                    val kahootGame = activeGames[message.channelId]!! as KahootGame
                    if (message.content.length == 1 && kahootGame.check(message.content)) {
                        kahootGame.incScore(message.author)
                        message.react("✅")
                        if (kahootGame.hasNext()) {
                            val question = kahootGame.next()
                            val send = StringBuilder(question.question)
                            for (i in 0 until question.answerCount)
                                send.append("\n${(65 + i).toChar()}. ${question.choices[i].answer}")
                            message.channel.sendMessage(send.toString())
                        } else {
                            activeGames.remove(message.channelId)
                            // TODO: Make embed for this
                            val winner = kahootGame.scores.maxBy{ it.value }?.key
                            message.channel.sendMessage("${winner?.mention} won with ${kahootGame.scores[winner]} points!")
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

    fun incScore(user: User) {
        if (scores[user] == null)
            scores[user] = 0
        scores[user] = scores[user]!! + 1
    }
}

class QuizletGame(creator: User, setID: String): Game(creator) {
    val set: Set
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

    fun check(term: String): Boolean {
        return termMap[term] == currentDefinition
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

    fun check(answer: String?): Boolean {
        println(answer)
        val charAnswer = answer?.toUpperCase()?.toCharArray()?.single()
        if (charAnswer?.isLetter() == true && charAnswer.toInt() - 65 < currentQuestion.answerCount) {
            return currentQuestion.choices[charAnswer.toInt() - 65].answer == currentQuestion.correctAnswer
        }
        return false
    }

// TODO: Revisit emoji kahoot reactions when Diskord adds such functionality

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
