@file:Suppress("EXPERIMENTAL_API_USAGE")
package io.github.potatocurry.shrewd

//import fastily.jwiki.core.Wiki
//import org.merriam_api.service.MerriamService
//import net.jeremybrooks.knicker.WordApi
//import net.jeremybrooks.knicker.WordsApi
import biweekly.Biweekly
import com.beust.klaxon.Klaxon
import com.jessecorbett.diskord.api.model.Message
import com.jessecorbett.diskord.api.rest.CreateDM
import com.jessecorbett.diskord.api.rest.EmbedAuthor
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.*
import com.jessecorbett.diskord.util.*
import humanize.Humanize
import io.github.potatocurry.kashoot.api.Kashoot
import io.github.potatocurry.kwizlet.api.Kwizlet
import io.github.potatocurry.shrewd.games.CaveGame
import io.github.potatocurry.shrewd.games.Game
import io.github.potatocurry.shrewd.games.KahootGame
import io.github.potatocurry.shrewd.games.QuizletGame
import io.github.potatocurry.shrewd.models.Meme
import io.github.potatocurry.shrewd.models.Summary
import io.github.potatocurry.shrewd.models.XKCDComic
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import moe.tlaster.kotlinpgp.KotlinPGP
import moe.tlaster.kotlinpgp.data.EncryptParameter
import moe.tlaster.kotlinpgp.data.PublicKeyData
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*
import kotlin.system.exitProcess

val logger: Logger = LoggerFactory.getLogger("io.github.potatocurry.shrewd")
lateinit var globalClient: ClientStore
val admins = listOf("245007207102545921", "141314236998615040", "318071655857651723")
val kwizlet = Kwizlet(System.getenv("SHREWD_QUIZLET_TOKEN"))
val kashoot = Kashoot()
val games = mutableMapOf<String, Game>()
val jsonEndpoint = System.getenv("SHREWD_JSONSTORE_TOKEN")
val klaxon = Klaxon()

val choiceMap = mapOf(
        // Kahoot choices
        "\uD83C\uDDE6" to "A",
        "\uD83C\uDDE7" to "B",
        "\uD83C\uDDE8" to "C",
        "\uD83C\uDDE9" to "D",

        // Cave directions
        "\uD83C\uDDF3" to "N",
        "\uD83C\uDDF8" to "S",
        "\uD83C\uDDEA" to "E",
        "\uD83C\uDDFC" to "W"
)

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
        globalClient = clientStore

        started {
            val dm = clientStore.discord.createDM(CreateDM("245007207102545921"))
            ChannelClient(token, dm.id).sendMessage("") {
                description = "Bot Started"
                field("Environment", if (env == "PROD") "Production" else "Development", true)
//                field("Guilds", clientStore.discord.getGuilds().size.toString(), true)
                timestamp = LocalDateTime.now(ZoneId.of("GMT")).toString()
            }
            logger.info("Startup message sent")

//            fixedRateTimer("Rich Presence", true, 0L, 60000) {
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
                    >wiki [title] - Get a Wikipedia page by title
                    >quizlet [setURL/query] - Start a Quizlet trivia game
                    >kahoot [quizURL] - Start a Kahoot trivia game
                    >skip - Skip the current question
                    >abort - Stop the current game
                    >keybase [id] - Link your account to your keybase public key
                    >encrypt [message] [recipients] - Encrypt a message using the given recipients' public key
                    >cal [method] - Link an online calendar
                    >meme [operation] (memeLink/attachment) - Stash or retrieve a meme
                    >bash (ID) - Get a random or specific quote from bash.org
                    >xkcd (number) - Fetch an XKCD comic by number, defaults to latest
                    >suggest [suggestion] - Submit a suggestion to the shrewd starboard
                    >hq - Get a link to Shrewd HQ
                    >shutdown - Shutdown the bot
                    """.trimIndent()
                ) // TODO: Make embed for this
            }

            command("echo") {
                reply(args)
            }

            command("notes"){
                when (words[1]) {
                    "save", "add" -> {
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
                    "list", "show", "display" -> {
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
                    "delete", "remove" -> {
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
                val response = khttp.get(
                    "https://api.wolframalpha.com/v1/result",
                    params = mapOf(
                        "i" to query,
                        "appid" to wolframID
                    )
                )
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
                val articleURL = args
                val summary = getSMMRY(articleURL, 5)
                if (summary != null)
                    reply(summary.content) { // TODO: Add keywords
                        title = summary.title
                        url = articleURL
                        field("Characters", summary.characterCount, true)
                        field("Reduction", summary.contentReduced, true)
                    }
                else {
                    reply("Error processing article")
                    logger.warn("Unable to parse JSON of article {}", articleURL)
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
                if (games[channelId] != null)
                    reply("Game already running in this channel")
                else {
                    val game = CaveGame(channel, author)
                    games[channelId] = game
                    game.run {
                        reply {
                            title = "Cave Exploration"
                            description = game.intro
                            with (this@command.author) {
                                author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                            }
                            field("Instructions", "Navigate with single character directions or reactions", true)
                            field("Seed", initialSeed, true)
                        }

                        delay(2500)
                        currentMessage = reply {
                            title = "Cave Exploration"
                            description = initialDescription
                            with (creator) {
                                author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                            }
                            field("Exits", Humanize.oxford(initialExits), true)
                        }.apply {
                            for (exit in initialExits)
                                react(emojiMap.getValue(exit.toString()))
                        }.id
                    }
                }
            }

            command("number") {
                val response = khttp.get("http://numbersapi.com/${words[1]}")
                response.encoding = Charset.defaultCharset()
                reply(response.text)
            }

//            command("wiki") {
//                val article = words[1]
//                val wiki = Wiki("io.github.potatocurry")
//                val text = wiki.getTextExtract(article)
//                val images = wiki.getImagesOnPage(article)
//                reply {
//                    title = article
//                    description = text
//                    image(images[0])
//                }
//            }

            command("quizlet") {
                if (games[channelId] != null)
                    reply("Game already running in this channel")
                else {
                    val setId = if ("http" in words[1])
                        kwizlet.parseURL(URL(words[1])) // TODO: Find out if URL class is not recommended
                    else
                        kwizlet.search(words.drop(1).joinToString(" ")).searchSets[0].id.toString()
                    val game = QuizletGame(channel, author, setId)
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
            }

            command("kahoot") {
                if (games[channelId] != null)
                    reply("Game already running in this channel")
                else {
                    reply("Kahoot games are undergoing a major overhaul and are not yet stable")
                    val kahootPath = URL(words[1]).path.split("/")
                    val quizId = kahootPath.last(String::isNotEmpty)
                    val game = KahootGame(channel, author, quizId)
                    games[channelId] = game
                    game.run {
                        reply {
                            title = quiz.title
                            description = quiz.description
                            author = EmbedAuthor(quiz.creator)
                            field("Total Terms", quiz.questions.size.toString(), false)
                        }

                        delay(2500)
                        game.start()
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

            command("keybase") {
                khttp.post(
                    "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/pgp",
                    json = mapOf("keybase" to args)
                )
                reply("Set Keybase ID")
            }

            command("encrypt") {
                delete()
                val recipientKeys = usersMentioned.map { PublicKeyData(getKeybaseKey(it.id)) }
                val encrypted = KotlinPGP.encrypt(
                    EncryptParameter(
                        args,
                        recipientKeys
                    )
                )
                reply("```$encrypted```")
            }

            command("cal") {
                when (words[1]) {
                    "set" -> {
                        val calendarUrl = args.removePrefix("set ")
                        khttp.post(
                            "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/calendar",
                            json = mapOf("url" to calendarUrl)
                        )
                        reply("Set calendar URL")
                    }
                    "list" -> {
                        val calendarUrlJson = khttp.get(
                            "https://www.jsonstore.io/$jsonEndpoint/users/$authorId/calendar"
                        ).jsonObject
                        val calendarUrl = calendarUrlJson
                            .getJSONObject("result")
                            .getString("url")
                            .replaceBefore("://", "http")
                        val calendarText = khttp.get(calendarUrl).text
                        val calendar = Biweekly.parse(calendarText).first()
                        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                        val weekAway = today.plusWeeks(1)
                        val events = calendar.events.filter { event ->
                            val date = event.dateStart.value.toInstant()
                            date.isAfter(today.toInstant()) && date.isBefore(weekAway.toInstant())
                        }
                        reply {
                            with (this@command.author) {
                                author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                            }
                            events.forEach { event ->
                                val name = event.summary.value
                                val description = event.description.value
                                val date = Date.from(event.dateStart.value.toInstant())
                                val dateFormat = SimpleDateFormat("EEE, MMM dd")
                                field("$name (${dateFormat.format(date)})", description, false)
                            }
                        }
                    }
                }
            }

            command("meme") {
                when (words[1]) {
                    "stash" -> {
                        val memeLink = if (attachments.isNotEmpty())
                            attachments.first().url // or proxyUrl?
                        else
                            args.removePrefix("stash ")
                        stashMeme(memeLink, authorId) // create repost detection and send "NORMIE ALERT" and emojis
                        reply("Meme stashed")
                    }
                    "random" -> {
                        val response = khttp.get(
                                "https://www.jsonstore.io/$jsonEndpoint/memes"
                        ).jsonObject
                        if (response.isNull("result"))
                            reply("No memes available")
                        else {
                            val memes = response.getJSONArray("result")
                            val memeJson = memes.toList().random() as JSONObject
//                            val meme = memes.toList().random() as Meme
                            val meme = Meme(
                                    memeJson.getString("link"),
                                    memeJson.getString("authorId"),
                                    LocalDateTime.parse(memeJson.getString("time")),
                                    memeJson.getInt("upvotes"),
                                    memeJson.getInt("downvotes"),
                                    memeJson.getInt("reports")
                            )
                            val poster = clientStore.discord.getUser(meme.authorId)
                            val message = reply {
                                author = EmbedAuthor(
                                        name = poster.username,
                                        authorImageUrl = poster.pngAvatar()
                                        // TODO: Add discord link to user profile?
                                )
                                field(":thumbsup:", meme.upvotes.toString(), true)
                                field(":thumbsdown:", meme.downvotes.toString(), true)
                                field(":warning:", meme.reports.toString(), true)
                                image(meme.link)
                                timestamp = meme.time.toString()
                            }
                            message.react("\uD83D\uDC4D")
                            message.react("👎")
                            message.react("⚠️")
                        }
                    }
                    "good" -> {

                    }
                    "bad" -> {

                    }
                }
            }

            command("bash") {
                val arg = words.getOrNull(1)
                val bashUrl = when {
                    arg == "top" -> "http://bash.org/?top"
                    arg == "latest" -> "http://bash.org/?latest"
                    arg?.toIntOrNull() != null -> "http://bash.org/?$arg"
                    else -> "http://bash.org/?random1"
                }
                val document = Jsoup.connect(bashUrl).get()
                val quoteElement = document.getElementsByClass("qt").random()
                val infoElement = quoteElement.previousElementSibling()
                val numberElement = infoElement.getElementsByTag("b").single()
                val voteElement = infoElement.getElementsByTag("font")
                val quote = quoteElement.wholeText()
                val number = numberElement.text().removePrefix("#")
                val vote = voteElement.text()
                reply {
                    text = "```$quote```"
                    field("ID", number, true)
                    field("Votes", vote, true)
                }
            }

            command("xkcd") {
                val number = words.getOrNull(1)
                val jsonUrl = if (number == null)
                    "https://xkcd.com/info.0.json"
                else
                    "https://xkcd.com/$number/info.0.json"
                val comicJson = khttp.get(jsonUrl).text
                val comic = klaxon.parse<XKCDComic>(comicJson)
                if (comic != null)
                    reply {
                        title = comic.title
                        description = comic.alt
                        url = "https://xkcd.com/${comic.num}/"
                        image(comic.img)
                        timestamp = LocalDate.of(comic.year.toInt(), comic.month.toInt(), comic.day.toInt()).toString()
                    }
                else {
                    reply("Error processing XKCD comic")
                    logger.warn("Unable to parse JSON of XKCD comic {}", number)
                }
            }

            command("suggest") {
                val message = ChannelClient(token, "604144590835941386").sendMessage("") {
                    with (this@command.author) {
                        author = EmbedAuthor(username, authorImageUrl = pngAvatar())
                    }
                    description = args
                    timestamp = LocalDateTime.now(ZoneId.of("GMT")).toString()
                }
                message.react("⭐")
            }

            command("hq") {
                reply("https://discord.gg/eFxB7ck")
            }

            command("shutdown") {
                val userLog = "${author.username} ($authorId)"
                if (authorId in admins) {
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
                        if (game.creator == message.author && direction in listOf("N", "S", "E", "W"))
                            game.sendCommand(direction)
                    }
                    is QuizletGame -> {
                        if (game.check(message.content)) {
                            game.isActive = false
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
                }
            }
        }

        reactionAdded { reaction ->
            if (!clientStore.discord.getUser(reaction.userId).isBot && games.containsKey(reaction.channelId)) {
                val choice = choiceMap[reaction.emoji.name]
                if (choice != null)
                    when (val game = games[reaction.channelId]) {
                        is CaveGame -> {
                            if (reaction.messageId == game.currentMessage && game.creator.id == reaction.userId)
                                game.sendCommand(choice)
                        }
                        is KahootGame -> {
                            if (reaction.messageId == game.currentMessage && game.isActive) {
                                game.addChoice(reaction.userId, choice)
                            }
                        }
                    }
            }
        }
    }
}

val Message.args: String
    get() = content.split(" ", limit = 2)[1]

fun getSMMRY(articleURL: String, sentenceCount: Int = 7, keywordCount: Int = 5): Summary? {
    val smmryKey = System.getenv("SHREWD_SMMRY_KEY")
    val summaryJson = khttp.get( // Causes NPE, perhaps due to lag
        "https://api.smmry.com",
        params = mapOf(
            "SM_API_KEY" to smmryKey,
            "SM_LENGTH" to sentenceCount.toString(),
            "SM_KEYWORD_COUNT" to keywordCount.toString(),
            "SM_URL" to articleURL
        )
    ).text
    return klaxon.parse<Summary>(summaryJson)
}

fun getKeybaseKey(userId: String): String {
    val response = khttp.get(
        "https://www.jsonstore.io/$jsonEndpoint/users/$userId/pgp"
    ).jsonObject
    val keybaseId = response
        .getJSONObject("result")
        .getString("keybase")
    val keybaseUser = khttp.get(
        "https://keybase.io/_/api/1.0/user/lookup.json",
        params = mapOf(
            "usernames" to keybaseId,
            "fields" to "public_keys"
        )
    ).jsonObject
    return keybaseUser
        .getJSONArray("them")
        .getJSONObject(0)
        .getJSONObject("public_keys")
        .getJSONObject("primary")
        .getString("bundle")
}

fun stashMeme(memeLink: String, authorId: String) {
    val meme = Meme(memeLink, authorId)
    val response = khttp.get(
            "https://www.jsonstore.io/$jsonEndpoint/memes"
    ).jsonObject
    val memes = if (response.isNull("result"))
        JSONArray()
    else
        response.getJSONArray("result")
    memes.put(JSONObject(meme))
    khttp.post(
            "https://www.jsonstore.io/$jsonEndpoint/memes",
            json = memes
    )
    logger.debug("Stashed meme {} from {}", memeLink, authorId)
}
