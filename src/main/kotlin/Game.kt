import com.jessecorbett.diskord.api.model.User
import io.github.potatocurry.kashoot.api.Question as KahootQuestion
import io.github.potatocurry.kashoot.api.Quiz
import io.github.potatocurry.kwizlet.api.Question as QuizletQuestion
import io.github.potatocurry.kwizlet.api.Set
import me.xdrop.fuzzywuzzy.FuzzySearch

abstract class Game(val creator: User) {
    val scores = mutableMapOf<User, Int>()

    fun incScore(user: User) {
        if (scores[user] == null)
            scores[user] = 0
        scores[user] = scores[user]!! + 1
    }
}

class QuizletGame(creator: User, setID: String): Game(creator) {
    val set: Set = kwizlet.getSet(setID)
    private val questions: Iterator<QuizletQuestion>
    private lateinit var currentQuestion: QuizletQuestion

    init {
        questions = set.questions.shuffled().iterator()
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
        return FuzzySearch.ratio(answer.toLowerCase(), currentQuestion.term.toLowerCase()) >= 75
    }
}

class KahootGame(creator: User, quizID: String): Game(creator) {
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
