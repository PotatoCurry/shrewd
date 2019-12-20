package io.github.potatocurry.shrewd.games

import com.jessecorbett.diskord.api.model.User
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.dsl.field
import com.jessecorbett.diskord.util.sendMessage
import humanize.Humanize
import io.github.potatocurry.kashoot.api.Question
import io.github.potatocurry.kashoot.api.Quiz
import io.github.potatocurry.shrewd.games
import io.github.potatocurry.shrewd.globalClient
import io.github.potatocurry.shrewd.kashoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class KahootGame(channel: ChannelClient, creator: User, quizID: String): TriviaGame(channel, creator) {
    val quiz: Quiz = kashoot.getQuiz(quizID)
    private val questions: Iterator<Question>
    lateinit var currentMessage: String
    lateinit var currentChoices: MutableMap<String, String>
    var isActive = false
    private lateinit var mainJob: Job

    init {
        questions = quiz.questions.shuffled().iterator()
    }

    fun start() = runBlocking {
        mainJob = launch {
            while (questions.hasNext()) {
                sendQuestion()
                delay(2500)
            }

            games.remove(channel.channelId)
            val sortedScores = scores.toSortedMap(compareByDescending { scores[it] })
            channel.sendMessage("") {
                title = "Game Results"
                for (scores in sortedScores.entries.withIndex())
                    field("${scores.index + 1}. ${scores.value.key.username}", "${scores.value.value} points", false)
            }
        }
    }

    private suspend fun sendQuestion() {
        val question = questions.next()
        val send = StringBuilder(question.question)
        for (i in 0 until question.answerCount)
            send.append("\n${(65 + i).toChar()}. ${question.choices[i].answer}")
        currentMessage = channel.sendMessage(send.toString()).apply {
            for (i in 0 until question.answerCount)
                react(emojiMap.getValue((65 + i).toChar().toString()))
        }.id
        currentChoices = mutableMapOf()
        isActive = true

        delay(10000)
        isActive = false
        channel.sendMessage("${Humanize.oxford(question.choices.filter { it.correct }.map { it.answer })} was correct")
        val correctUsers = currentChoices.filterValues { check(question, it) }.keys
        correctUsers.forEach {
            val user = globalClient.discord.getUser(it)
            incScore(user)
        }
    }

    fun addChoice(userId: String, choice: String) {
        currentChoices.putIfAbsent(userId, choice)
    }

    private fun check(question: Question, answer: String): Boolean {
        val charAnswer = answer.single()
        if (charAnswer.toInt() - 65 < question.answerCount) {
            return question.choices[charAnswer.toInt() - 65].answer in question.correctAnswers
        }
        return false
    }

    override suspend fun abort() {
        mainJob.cancel()
        super.abort()
    }
}
