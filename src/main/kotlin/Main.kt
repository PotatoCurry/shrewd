@file:Suppress("EXPERIMENTAL_API_USAGE")
package io.github.potatocurry.shrewd

import com.jessecorbett.diskord.api.model.Message
import com.jessecorbett.diskord.api.rest.CreateDM
import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.*
import com.jessecorbett.diskord.util.*
import humanize.Humanize
import io.github.potatocurry.kashoot.api.Kashoot
import io.github.potatocurry.kwizlet.api.Kwizlet
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
//import org.merriam_api.service.MerriamService
//import net.jeremybrooks.knicker.WordApi
//import net.jeremybrooks.knicker.WordsApi
import java.net.URL
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.system.exitProcess

val logger: Logger = LoggerFactory.getLogger("io.github.potatocurry.shrewd")
val admins = listOf("245007207102545921", "141314236998615040", "318071655857651723")
val kwizlet = Kwizlet(System.getenv("SHREWD_QUIZLET_TOKEN"))
val kashoot = Kashoot()
val games = mutableMapOf<String, Game>()
val jsonEndpoint = System.getenv("SHREWD_JSONSTORE_TOKEN")

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
                    >help - Display this help message
                    >echo [text] - Echo the provided text
                    >notes [operation] [text] - Save, edit or delete a note
                    >wolfram [query] - Query WolframAlpha for a simple answer
                    >summary [articleURL] - Summarize an article
                    >http [method] [URL] (args) - Perform an HTTP request
                    >cave - Start a cave exploration game
                    >number [number] - Get interesting number facts
                    >quizlet [setURL/query] - Start a Quizlet trivia game
                    >kahoot [quizURL] - Start a Kahoot trivia game
                    >skip - Skip the current question
                    >abort - Stop the current game
                    >shutdown - Shutdown the bot
                    """.trimIndent()
                ) // TODO: Make embed for this
            }

            command("echo") {
                reply(args)
            }

            command("notes"){
                when (words[1]) {
                    "save" -> {
                        val note = args.removePrefix("save ")
                        val response = khttp.get(
                            "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/notes"
                        ).jsonObject
                        val notes = if (response.isNull("result"))
                            JSONArray()
                        else
                            response.getJSONArray("result")
                        notes.put(note)
                        khttp.post(
                            "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/notes",
                            json = notes
                        )
                        reply("Saved note")
                    }
                    "list" -> {
                        val response = khttp.get(
                            "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/notes"
                        ).jsonObject
                        val notes = if (response.isNull("result"))
                            null
                        else
                            response.getJSONArray("result")
                        if (notes == null)
                            reply("You have not saved any notes")
                        else
                            reply {
                                title = "Notes"
                                with (this@command.author) {
                                    author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                                }
                                for (note in notes.withIndex())
                                    field((note.index + 1).toString(), note.value.toString(), false)
                            }
                    }
                    "edit" -> {
                        val id = words[2].toInt()
                        val note = args.removePrefix("edit $id ")
                        val response = khttp.get(
                            "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/notes"
                        ).jsonObject
                        val notes = if (response.isNull("result"))
                            JSONArray()
                        else
                            response.getJSONArray("result")
                        if (id > notes.length())
                            reply("There is no element at index $id")
                        else {
                            notes.put(id - 1, note)
                            khttp.post(
                                "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/notes",
                                json = notes
                            )
                            reply("Edited note")
                        }
                    }
                    "delete" -> {
                        val response = khttp.get(
                            "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/notes"
                        ).jsonObject
                        val notes = if (response.isNull("result"))
                            null
                        else
                            response.getJSONArray("result")
                        val id = words[2].toInt()
                        when {
                            notes == null -> reply("You have not saved any notes")
                            notes.isNull(id - 1) -> reply("There is no note at index $id")
                            else -> {
                                notes.remove(id - 1)
                                khttp.post(
                                    "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/notes",
                                    json = notes
                                )
                                reply("Deleted note $id")
                            }
                        }
                    }
                    "clear" -> {
                        val response = khttp.get(
                            "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/notes"
                        ).jsonObject
                        val notes = if (response.isNull("result"))
                            null
                        else
                            response.getJSONArray("result")
                        if (notes == null)
                            reply("You have not saved any notes")
                        else {
                            khttp.delete("https://www.jsonstore.io/$jsonEndpoint/users/$authorId/notes")
                            reply("Cleared notes")
                        }
                    }
                    else -> reply("Invalid operation")
                }
            }

            command("wolfram") {
                val query = content.removePrefix(">wolfram ")
                val wolframID = System.getenv("SHREWD_WOLFRAM_ID")
                if (wolframID == null)
                    logger.error("SHREWD_WOLFRAM_ID is null")
                val params = mapOf(
                    "i" to query,
                    "appid" to wolframID
                )
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

            //TODO: Wolfram request method

            command("summary") {
                val articleURL = words[1]
                val smmry = getSMMRY(articleURL, 5)
                val articleTitle = smmry.getString("sm_api_title")
                val articleChars = smmry.getString("sm_api_character_count")
                val articleReduction = smmry.getString("sm_api_content_reduced")
                val articleSummary = smmry.getString("sm_api_content")
                reply(articleSummary) {
                    title = articleTitle
                    url = articleURL
                    field("Characters", articleChars, true)
                    field("Reduction", articleReduction, true)
                }
            }

            command("http") {
                val params = mutableMapOf<String, String>()
                for (rawParam in words.subList(3, words.size)) with (rawParam.split("=", limit = 2)) {
                    params += Pair(component1(), component2())
                }
                val response = try {
                    khttp.request(words[1].toUpperCase(), words[2], params = params, timeout = 5.0).text
                } catch (e: IOException) {
                    e.toString()
                }
                reply("```$response```")
            }

            command("cave") {
                val game = CaveGame(channel, author)
                games[channelId] = game
                game.run {
                    reply {
                        title = "Cave Exploration"
                        description = game.intro
                        with (this@command.author) {
                            author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                        }
                        field("Instructions", "Navigate with single character directions (N, S, E, W)", true)
                        field("Seed", initialSeed, true)
                    }

                    delay(2500)
                    reply {
                        title = "Cave Exploration"
                        description = initialDescription
                        with (creator) {
                            author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                        }
                        field("Exits", Humanize.oxford(initialExits), true)
                    }
                }
            }

            command("number") {
                val response = khttp.get("http://numbersapi.com/${words[1]}")
                response.encoding = Charset.defaultCharset()
                reply(response.text)
            }

            command("quizlet") {
                val setID = if (words[1].contains("http"))
                    kwizlet.parseURL(URL(words[1]))
                else
                    kwizlet.search(words.drop(1).joinToString(" ")).searchSets[0].id.toString()
                val game = QuizletGame(channel, author, setID)
                games[channelId] = game
                game.run {
                    reply {
                        title = set.title
                        description = set.description
                        author = EmbedAuthor(set.author)
                        field("Total Terms", set.termCount.toString(), false)
                    }

                    delay(2500)
                    sendQuestion()
                }
            }

            command("kahoot") {
                reply("Kahoot game are unstable as fuck right now play at your own risk")
                val kahootPath = URL(words[1]).path.split("/")
                val quizID = kahootPath.last(String::isNotEmpty)
                val game = KahootGame(channel, author, quizID)
                games[channelId] = game
                game.run {
                    reply {
                        title = quiz.title
                        description = quiz.description
                        author = EmbedAuthor(quiz.creator)
                        field("Total Terms", quiz.questions.size.toString(), false)
                    }

                    delay(2500)
                    with (next()) {
                        val send = StringBuilder(question)
                        for (i in 0 until answerCount)
                            send.append("\n${(65 + i).toChar()}. ${choices[i].answer}")
                        reply(send.toString())
                    }
                }
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
                    else -> game.abort()
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
                        gameEntry.value.abort()
                    }
                    logger.info("Bot shutdown by {}", userLog)
                    delay(1000)
                    exitProcess(0)
                } else {
                    react("\uD83D\uDE20")
                    val dm = clientStore.discord.createDM(CreateDM(author.id))
                    ChannelClient(token, dm.id).sendMessage("frick you")
                    logger.trace("Shutdown request by {} denied", userLog)
                }
            }
        }

        messageCreated { message ->
            if (!message.isFromBot && games.containsKey(message.channelId)) {
                when (val game = games[message.channelId]) {
                    is CaveGame -> {
                        val direction = message.content.toUpperCase()
                        if (game.creator == message.author && listOf("N", "S", "E", "W").contains(direction))
                            game.sendCommand(direction)
                    }
                    is QuizletGame -> {
                        if (game.check(message.content)) {
                            game.incScore(message.author)
                            message.react("✅")
                            if (game.hasNext()) {
                                delay(2500)
                                game.sendQuestion()
                            } else {
                                games.remove(message.channelId)
                                val sortedScores = game.scores.toSortedMap(compareByDescending{ game.scores[it] })
                                message.channel.sendMessage("") {
                                    title = "TriviaGame Results"
                                    for (scores in sortedScores.entries.withIndex())
                                        field("${scores.index + 1}. ${scores.value.key.username}", "${scores.value.value} points", false)
                                }
                            }
                        }
                    }
                    is KahootGame -> {
                        if (message.content.length == 1 && game.check(message.content)) {
                            game.incScore(message.author)
                            message.react("✅")
                            if (game.hasNext()) {
                                delay(2500)
                                val question = game.next()
                                val send = StringBuilder(question.question)
                                for (i in 0 until question.answerCount)
                                    send.append("\n${(65 + i).toChar()}. ${question.choices[i].answer}")
                                message.channel.sendMessage(send.toString())
                            } else {
                                games.remove(message.channelId)
                                val sortedScores = game.scores.toSortedMap(compareByDescending{ game.scores[it] })
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
}

val Message.args: String
    get() = content.split(" ", limit = 2)[1]

fun getSMMRY(articleURL: String, sentenceCount: Int = 7, keywordCount: Int = 5): JSONObject { // TODO: Make object for this when ported to edukate
    val smmryKey = System.getenv("SHREWD_SMMRY_KEY")
    val params = mapOf(
        "SM_API_KEY" to smmryKey,
        "SM_LENGTH" to sentenceCount.toString(),
        "SM_KEYWORD_COUNT" to keywordCount.toString(),
        "SM_URL" to articleURL
    )
    val response = khttp.get("https://api.smmry.com", params = params)
    return response.jsonObject
}
