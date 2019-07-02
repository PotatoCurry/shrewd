@file:Suppress("EXPERIMENTAL_API_USAGE")
package io.github.potatocurry.shrewd

import com.jessecorbett.diskord.api.rest.CreateDM
import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.*
import com.jessecorbett.diskord.util.*
import io.github.potatocurry.kashoot.api.Kashoot
import io.github.potatocurry.kwizlet.api.Kwizlet
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
//import org.merriam_api.service.MerriamService
//import net.jeremybrooks.knicker.WordApi
//import net.jeremybrooks.knicker.WordsApi
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.system.exitProcess

val logger = LoggerFactory.getLogger("io.github.potatocurry.shrewd")
val admins = listOf("245007207102545921", "141314236998615040", "318071655857651723")
val kwizlet = Kwizlet(System.getenv("SHREWD_QUIZLET_TOKEN"))
val kashoot = Kashoot()
val games = mutableMapOf<String, Game>()

suspend fun main() {
    val env = System.getenv("SHREWD_ENV")
    if (env == null) {
        logger.error("SHREWD_ENV is null")
        exitProcess(1)
    }
    val envName = "SHREWD_${env}_TOKEN"
    val token = System.getenv(envName)
    if (token == null) {
        logger.error("$envName is null")
        exitProcess(1)
    }
    logger.info("Obtained bot token")

    bot(token) {
        started {
            val dm = clientStore.discord.createDM(CreateDM("245007207102545921"))
            ChannelClient(token, dm.id).sendMessage("") {
                description = "Bot Started"
                field("Environment", if (env == "PROD") "Production" else "Development", true)
//                field("Guilds", clientStore.guilds, true)
                timestamp = LocalDateTime.now(ZoneId.of("GMT")).toString()
            }
            logger.info("Startup message sent")

//            fixedRateTimer("Rich Presence", true, 0L, 60 * 1000) {
//
//            }
        }

        commands(">") {
            command("help") {
                reply(
                    """
                    >wolfram [query] - Query Wolfram Alpha for a simple answer
                    >quizlet [setURL/query] - Start a Quizlet trivia game
                    >kahoot [quizURL] - Start a Kahoot trivia game
                    >skip - Skip the current question
                    >abort - Stop the current game
                    """.trimIndent()
                ) // TODO: Make embed for this
            }

            command("wolfram") {
                val query = words.drop(1).joinToString(" ")
                val wolframID = System.getenv("SHREWD_WOLFRAM_ID")
                if (wolframID == null)
                    logger.error("SHREWD_WOLFRAM_ID is null")
                val params = mapOf("i" to query, "appid" to wolframID)
                val response = khttp.get("https://api.wolframalpha.com/v1/result", params = params)
                val answer = try {
                    when (response.statusCode) {
                        200 -> {
                            logger.trace("WolframAlpha response to query \"{}\" was \"{}\"", query, response.text)
                            response.text
                        }
                        501 -> {
                            logger.trace("WolframAlpha had no response to \"{}\"", query)
                            "idk man"
                        }
                        else -> {
                            logger.error("Received unknown WolframAlpha response")
                            "idk man"
                        }
                    }
                } catch (e: IOException) {
                    logger.error("Error requesting WolframAlpha content", e)
                    "idk man"
                }
                reply(answer)
            }

            command("quizlet") {
                val setID = if (words[1].contains("http"))
                    kwizlet.parseURL(URL(words[1]))
                else
                    kwizlet.search(words.drop(1).joinToString(" ")).searchSets[0].id.toString()
                val quizGame = QuizletGame(channel, author, setID)
                games[channelId] = quizGame
                reply {
                    title = quizGame.set.title
                    description = quizGame.set.description
                    author = EmbedAuthor(quizGame.set.author)
                    field("Total Terms", quizGame.set.termCount.toString(), false)
                }
                delay(2500)
                quizGame.sendQuestion()
            }

            command("kahoot") {
                val kahootPath = URL(words[1]).path.split("/")
                val quizID = kahootPath.last(String::isNotEmpty)
                val kahootGame = KahootGame(channel, author, quizID)
                games[channelId] = kahootGame
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
                val game = games[channelId]
                when {
                    game == null -> reply("No game running in this channel")
                    game !is QuizletGame -> reply("Kahoot questions cannot be skipped")
                    author != game.creator -> reply("Only the game creator can skip a question")
                    else -> {
                        reply("Skipped question - ${game.peek().term} was the correct answer")
                        delay(2500)
                        game.sendQuestion()
                    }
                }
            }

            command("abort") {
                val game = games[channelId]
                when {
                    game == null -> reply("No game running in this channel")
                    author != game.creator -> reply("Only the game creator can abort the game")
                    else -> game.abort(channel)
                }
            }

//            command("word") {
//                val merriamDictionary = MerriamService("----------------------------")
//                val word = words[1]
//                reply {
//                    title = word
//                    description = merriamDictionary.getDefinition(word, 2)[0].transcription
//                }

//                System.setProperty("WORDNIK_API_KEY", System.getenv("SHREWD_WORDNIK_KEY"))
//                val word = words[1]
//                reply {
//                    title = word
//                    description = WordApi.definitions(word)[0].text
//                    for (example in WordApi.examples(word).examples)
//                        field(example.title, example.text, false)
//                    timestamp = LocalDateTime.now(ZoneId.of("GMT")).toString()
//                }
//            }

            command("shutdown") {
                val userLog = "${author.username} ($authorId)"
                if (admins.contains(authorId)) {
                    react("\uD83D\uDE22")
                    reply("Shutting down")
                    for (gameEntry in games) {
                        ChannelClient(token, gameEntry.key).sendMessage("Bot shutting down")
                        gameEntry.value.abort(ChannelClient(token, gameEntry.key))
                    }
                    logger.info("Shutdown all active games")
                    logger.info("Bot shutdown by {}", userLog)
                    exitProcess(0)
                } else {
                    react("\uD83D\uDE20")
                    logger.trace("Shutdown request by {} denied", userLog)
                }
            }
        }

        messageCreated { message ->
            if (!message.isFromBot && games.containsKey(message.channelId)) {
                if (games[message.channelId]!! is QuizletGame) {
                    val quizGame = games[message.channelId]!! as QuizletGame
                    if (quizGame.check(message.content)) {
                        quizGame.incScore(message.author)
                        message.react("✅")
                        if (quizGame.hasNext()) {
                            delay(2500)
                            quizGame.sendQuestion()
                        } else {
                            games.remove(message.channelId)
                            val sortedScores = quizGame.scores.toSortedMap(compareByDescending{ quizGame.scores[it] })
                            message.channel.sendMessage("") {
                                title = "Game Results"
                                for (scores in sortedScores.entries.withIndex())
                                    field("${scores.index + 1}. ${scores.value.key.username}", "${scores.value.value} points", false)
                            }
                        }
                    }
                } else {
                    val kahootGame = games[message.channelId]!! as KahootGame
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
                            games.remove(message.channelId)
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
    }
}
