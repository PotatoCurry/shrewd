import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.dsl.*
import com.jessecorbett.diskord.util.mention
import com.jessecorbett.diskord.util.sendMessage
import com.jessecorbett.diskord.util.words
import io.github.potatocurry.kwizlet.api.Kwizlet
import io.github.potatocurry.kwizlet.api.Set

fun main() {
    val activeGames = mutableMapOf<String, QuizGame>()

    bot(System.getenv("shrewddiscordtoken")) {
        commands(">") {
            command("quizlet") {
                val quizGame = QuizGame(words[1])
                activeGames[channelId] = quizGame
                reply {
                    title = quizGame.set.title
                    description = quizGame.set.description
                    author = EmbedAuthor(quizGame.set.author)
                    field("Total Terms", quizGame.set.termCount.toString(), false)
                }
                reply(quizGame.nextDefinition())
            }
        }

        messageCreated { message ->
            if (activeGames.containsKey(message.channelId)) {
                val quizGame = activeGames[message.channelId]!!
                if (quizGame.check(message.content, quizGame.currentDefinition)) {
                    quizGame.incScore(message.author)
                    message.react("✅")
                    if (quizGame.hasNextDefinition()) {
                        message.channel.sendMessage(quizGame.nextDefinition())
                    } else {
                        activeGames.remove(message.channelId)
                        // TODO: Make embed for this
                        val winner = quizGame.scores.maxBy{ it.value }?.key
                        message.channel.sendMessage("${winner?.mention} won with ${quizGame.scores[winner]} points!")
                    }
                }
            }
        }
    }.block()
}

class QuizGame(setID: String) {
    val set: Set
    private val termMap: Map<String, String>
    private val shuffledDefinitions: Iterator<String>
    var currentDefinition = ""
    val scores = mutableMapOf<User, Int>()

    init {
        val kwizlet = Kwizlet(System.getenv("QuizletClientID"))
        set = kwizlet.getSet(setID)
        termMap = set.termMap.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        shuffledDefinitions = termMap.values.shuffled().iterator()
    }

    fun hasNextDefinition(): Boolean {
        return shuffledDefinitions.hasNext()
    }

    fun nextDefinition(): String {
        currentDefinition = shuffledDefinitions.next()
        return currentDefinition
    }

    fun check(term: String, definition: String): Boolean {
        return termMap[term] == definition
    }

    fun incScore(user: User) {
        if (scores[user]?.inc() == null)
            scores[user] = 0
    }
}
