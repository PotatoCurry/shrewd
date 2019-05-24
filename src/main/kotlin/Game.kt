import com.jessecorbett.diskord.api.model.User
import io.github.potatocurry.kashoot.api.Question
import io.github.potatocurry.kashoot.api.Quiz
import io.github.potatocurry.kwizlet.api.Set

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
