import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.*
import com.jessecorbett.diskord.util.isFromBot
import com.jessecorbett.diskord.util.mention
import com.jessecorbett.diskord.util.sendMessage
import com.jessecorbett.diskord.util.words
import io.github.potatocurry.kashoot.api.Kashoot
import io.github.potatocurry.kwizlet.api.Kwizlet
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URL
import kotlin.random.Random

val kwizlet = Kwizlet(System.getenv("QuizletClientID"))
val kashoot = Kashoot()

fun main() {
    val activeGames = mutableMapOf<String, Game>()

    bot(System.getenv("shrewddiscordtoken")) {
        commands(">") {
            command("help") {
                reply(
                    """
                    >quizlet [setURL/query] - Start a Quizlet trivia game
                    >kahoot [quizURL] - Start a Kahoot trivia game
                    >abort - Stop the current game
                    """.trimIndent()
                )
            }
            command("quizlet") {
                val setID = if (words[1].contains("http"))
                    kwizlet.parseURL(URL(words[1]))
                else
                    kwizlet.search(words.drop(1).joinToString(" ")).searchSets[0].id.toString()
                val quizGame = QuizletGame(author, setID)
                activeGames[channelId] = quizGame
                reply {
                    title = quizGame.set.title
                    description = quizGame.set.description
                    author = EmbedAuthor(quizGame.set.author)
                    field("Total Terms", quizGame.set.termCount.toString(), false)
                }
                delay(2500)
                sendQuizletQuestion(channel, quizGame)
            }
            command("kahoot") {
                val kahootPath = URI(words[1]).path.split("/")
                val quizID = kahootPath.last(String::isNotEmpty)
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
            command("skip") {
                val game = activeGames[channelId]
                when {
                    game == null -> reply("No game running in this channel")
                    game !is QuizletGame -> reply("Kahoot questions cannot be skipped")
                    author != game.creator -> reply("Only the game creator can skip a question")
                    else -> {
                        reply("Skipped question - ${game.peek().first} was the correct answer")
                        delay(2500)
                        sendQuizletQuestion(channel, game)
                    }
                }
            }
            command("abort") {
                val game = activeGames[channelId]
                when {
                    game == null -> reply("No game running in this channel")
                    author != game.creator -> reply("Only the game creator can abort the game")
                    else -> {
                        activeGames.remove(channelId, game)
                        val winner = game.scores.maxBy{ it.value }?.key
                        if (winner == null)
                            reply("Aborted game - Nobody had any points")
                        else
                            reply("Aborted game - ${winner.mention} had the highest score with ${game.scores[winner]} points")
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
                            sendQuizletQuestion(message.channel, quizGame)
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

suspend fun sendQuizletQuestion(channel: ChannelClient, quizGame: QuizletGame) {
    val termPair = quizGame.next()
    channel.sendMessage("") {
        field("Question", termPair.second, false)
    }
    delay(5000)
    if (termPair == quizGame.peek())
        channel.sendMessage("") {
            field("Question", termPair.second, false)
            field("Hint", generateHint(termPair.first), false)
        }
}

fun generateHint(answer: String): String {
    val charArray = answer.toCharArray()
    val stringBuilder = StringBuilder()
    for (char in charArray)
        stringBuilder.append(if (Random.nextInt(4) > 1) "\\_" else char)
    return stringBuilder.toString()
}
