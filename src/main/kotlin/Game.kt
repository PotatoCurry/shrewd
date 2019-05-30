import com.jessecorbett.diskord.api.model.User
import io.github.potatocurry.kashoot.api.Question
import io.github.potatocurry.kashoot.api.Quiz
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
    private val terms: Iterator<Pair<String, String>>
    private lateinit var currentTerm: Pair<String, String>

    init {
        terms = set.termPairs.shuffled().iterator()
    }

    fun hasNext(): Boolean {
        return terms.hasNext()
    }

    fun peek(): Pair<String, String> {
        return currentTerm
    }

    fun next(): Pair<String, String> {
        currentTerm = terms.next()
        return currentTerm
    }

    fun check(answer: String): Boolean {
        return FuzzySearch.ratio(answer.toLowerCase(), currentTerm.first.toLowerCase()) >= 60
    }
}

class KahootGame(creator: User, quizID: String): Game(creator) {
    val quiz: Quiz = kashoot.getQuiz(quizID)
    private val questions: Iterator<Question>
    private lateinit var currentQuestion: Question

    init {
        questions = quiz.questions.shuffled().iterator()
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
