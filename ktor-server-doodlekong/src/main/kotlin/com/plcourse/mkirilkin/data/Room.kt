package com.plcourse.mkirilkin.data

import com.plcourse.mkirilkin.data.models.messages.*
import com.plcourse.mkirilkin.gson
import com.plcourse.mkirilkin.server
import com.plcourse.mkirilkin.util.getRandomWords
import com.plcourse.mkirilkin.util.transformToUnderscores
import com.plcourse.mkirilkin.util.words
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class Room(
    val name: String,
    val maxPlayers: Int,
    var players: List<Player> = emptyList()
) {

    private val playerRemoveJobs = ConcurrentHashMap<String, Job>()
    private val leftPlayers = ConcurrentHashMap<String, Pair<Player, Int>>()

    private var timerJob: Job? = null
    private var drawingPlayer: Player? = null
    private var winningPlayers = listOf<String>()
    private var word: String? = null
    private var curWords: List<String>? = null
    private var drawingPlayerIndex = 0
    private var startTime = 0L
    private var curRoundDrawData: List<String> = listOf()

    private var phaseChangedListener: ((Phase) -> Unit)? = null
    var lastDrawData: DrawData? = null

    var phase = Phase.WAITING_FOR_PLAYERS
        set(value) {
            synchronized(field) {
                field = value
                phaseChangedListener?.let { change ->
                    change(value)
                }
            }
        }

    init {
        setPhaseChangedListener { newPhase ->
            when (newPhase) {
                Phase.WAITING_FOR_PLAYERS -> waitingForPlayers()
                Phase.WAITING_FOR_START -> waitingForStart()
                Phase.NEW_ROUND -> newRound()
                Phase.GAME_RUNNING -> gameRunning()
                Phase.SHOW_WORD -> showWord()
            }
        }
    }

    suspend fun broadcast(message: String) {
        players.forEach { player ->
            if (player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    suspend fun broadcastToAllExcept(message: String, clientId: String) {
        players.forEach { player ->
            if (player.clientId != clientId && player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    suspend fun addPlayer(clientId: String, userName: String, socket: WebSocketSession): Player {
        var indexToAdd = players.size - 1
        val player = if (leftPlayers.containsKey(clientId)) {
            val leftPlayer = leftPlayers[clientId]
            leftPlayer?.first?.let {
                it.socket = socket
                it.isDrawing = drawingPlayer?.clientId == clientId
                indexToAdd = leftPlayer.second

                playerRemoveJobs[clientId]?.cancel()
                playerRemoveJobs.remove(clientId)
                leftPlayers.remove(clientId)
                it
            } ?: Player(userName, socket, clientId)
        } else {
            Player(userName, socket, clientId)
        }
        indexToAdd = when {
            players.isEmpty() -> 0
            indexToAdd >= players.size -> players.size - 1
            else -> indexToAdd
        }
        val tmpPlayers = players.toMutableList()
        tmpPlayers.add(indexToAdd, player)
        players = tmpPlayers.toList()
        players = players + player

        if (players.size == 1) { // Единственный кто зашел в команту
            phase = Phase.WAITING_FOR_PLAYERS
        } else if (players.size == 2 && phase == Phase.WAITING_FOR_PLAYERS) {
            // При двух игроках в комнате ждем начала игры
            phase = Phase.WAITING_FOR_START
            players = players.shuffled()
        } else if (phase == Phase.WAITING_FOR_START && players.size == maxPlayers) {
            phase = Phase.NEW_ROUND
            players = players.shuffled()
        }

        val announcement = Announcement(
            "$userName joined the party!",
            System.currentTimeMillis(),
            Announcement.TYPE_PLAYER_JOINED
        )
        sendWordToPlayer(player)
        broadcastPlayerStates()
        sendCurRoundDrawInfoToPlayer(player)
        broadcast(gson.toJson(announcement))
        return player
    }

    fun removePlayer(clientId: String) {
        val player = players.find { it.clientId == clientId } ?: return
        val index = players.indexOf(player)
        leftPlayers[clientId] = player to index
        players = players - player
        playerRemoveJobs[clientId] = GlobalScope.launch {
            delay(PLAYER_REMOVE_TIME)
            val playerToRemove = leftPlayers[clientId]
            leftPlayers.remove(clientId)
            playerToRemove?.let {
                players = players - it.first
            }
            playerRemoveJobs.remove(clientId)
        }
        val announcement = Announcement(
            "${player.userName} has left the party",
            System.currentTimeMillis(),
            Announcement.TYPE_PLAYER_LEFT
        )
        GlobalScope.launch {
            broadcastPlayerStates()
            broadcast(gson.toJson(announcement))
            if (players.size == 1) {
                phase = Phase.WAITING_FOR_PLAYERS
                timerJob?.cancel()
            } else if (players.isEmpty()) {
                kill()
                server.rooms.remove(name)
            }
        }
    }

    fun containsPlayer(username: String): Boolean {
        return players.find { it.userName == username } != null
    }

    fun setWordAndSwitchToGameRunning(word: String) {
        this.word = word
        phase = Phase.GAME_RUNNING
    }

    suspend fun checkWordAndNotifyPlayers(message: ChatMessage): Boolean {
        if (isGuessCorrect(message)) {
            val guessingTime = System.currentTimeMillis() - startTime
            val timePercentageLeft = 1f - guessingTime.toFloat() / DELAY_GAME_RUNNING_TO_SHOW_WORD
            val score = GUESS_SCORE_DEFAULT + GUESS_SCORE_PERCENTAGE_MULTIPLIER * timePercentageLeft
            val player = players.find { it.userName == message.from }

            player?.let {
                it.score += score.toInt()
            }
            drawingPlayer?.let {
                it.score += GUESS_SCORE_FOR_DRAWING_PLAYER / players.size
            }
            broadcastPlayerStates()

            val announcement = Announcement(
                "${message.from} has guessed it!",
                System.currentTimeMillis(),
                announcementType = Announcement.TYPE_PLAYER_GUESSES_WORD
            )
            broadcast(gson.toJson(announcement))
            val isRoundOver = addWinningPlayer(message.from)
            if (isRoundOver) {
                val roundOverAnnouncement = Announcement(
                    "Everybody guessed it! New round is starting...",
                    System.currentTimeMillis(),
                    Announcement.TYPE_EVERYBODY_GUESSED_IT
                )
                broadcast(gson.toJson(roundOverAnnouncement))
            }
            return true
        }
        return false
    }

    fun addSerializedDrawInfo(drawAction: String) {
        curRoundDrawData = curRoundDrawData + drawAction
    }

    private fun setPhaseChangedListener(listener: (Phase) -> Unit) {
        phaseChangedListener = listener
    }

    private fun timeAndNotifyNextPhase(ms: Long) {
        timerJob?.cancel()
        timerJob = GlobalScope.launch {
            startTime = System.currentTimeMillis()
            val phaseChange = PhaseChange(
                phase,
                ms,
                drawingPlayer?.userName
            )
            repeat((ms / UPDATE_TIME_FREQUENCY).toInt()) {
                if (it > 0) {
                    phaseChange.phase = null
                }
                broadcast(gson.toJson(phaseChange))
                phaseChange.time -= UPDATE_TIME_FREQUENCY
                delay(UPDATE_TIME_FREQUENCY)
            }
            phase = when (phase) {
                Phase.WAITING_FOR_PLAYERS -> Phase.NEW_ROUND
                Phase.GAME_RUNNING -> {
                    finishOffDrawing()
                    Phase.SHOW_WORD
                }
                Phase.SHOW_WORD -> Phase.NEW_ROUND
                Phase.NEW_ROUND -> {
                    word = null
                    Phase.GAME_RUNNING
                }
                else -> Phase.WAITING_FOR_PLAYERS
            }
        }
    }

    private fun waitingForPlayers() {
        GlobalScope.launch {
            val phaseChange = PhaseChange(
                Phase.WAITING_FOR_PLAYERS,
                DElAY_WAITING_FOR_START_TO_NEW_ROUND,
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun waitingForStart() {
        GlobalScope.launch {
            timeAndNotifyNextPhase(DElAY_WAITING_FOR_START_TO_NEW_ROUND)
            val phaseChange = PhaseChange(
                Phase.WAITING_FOR_START,
                DElAY_WAITING_FOR_START_TO_NEW_ROUND,
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun newRound() {
        curRoundDrawData = listOf()
        curWords = getRandomWords(3)
        val newWords = NewWords(curWords.orEmpty())
        nextDrawingPlayer()
        GlobalScope.launch {
            broadcastPlayerStates()
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(newWords)))
            timeAndNotifyNextPhase(DELAY_NEW_ROUND_TO_GAME_RUNNING)
        }
    }

    private fun gameRunning() {
        winningPlayers = listOf()
        val nullSafetyPlayer = players.random()
        val wordToSend = word ?: curWords?.random() ?: words.random()
        val wordWithUnderscores = wordToSend.transformToUnderscores()
        val drawingUsername = (drawingPlayer ?: nullSafetyPlayer).userName
        val gameStateForDrawingPlayer = GameState(
            drawingUsername,
            wordToSend
        )
        val gameStateForGuessingPlayers = GameState(
            drawingUsername,
            wordWithUnderscores
        )
        GlobalScope.launch {
            broadcastToAllExcept(
                gson.toJson(gameStateForGuessingPlayers),
                (drawingPlayer ?: nullSafetyPlayer).clientId
            )
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(gameStateForDrawingPlayer)))

            timeAndNotifyNextPhase(DELAY_GAME_RUNNING_TO_SHOW_WORD)
            println("Drawing phase in room $name started. It'll last ${DELAY_GAME_RUNNING_TO_SHOW_WORD / 1_000}s")
        }
    }

    private fun showWord() {
        GlobalScope.launch {
            if (winningPlayers.isEmpty()) {
                drawingPlayer?.let {
                    it.score -= PENALTY_NOBODY_GUESSED_IT
                }
            }
            broadcastPlayerStates()
            word?.let {
                val chosenWord = ChosenWord(it, name)
                broadcast(gson.toJson(chosenWord))
            }
            timeAndNotifyNextPhase(DELAY_SHOW_WORD_TO_NEW_ROUND)
            val phaseChange = PhaseChange(Phase.SHOW_WORD, DELAY_SHOW_WORD_TO_NEW_ROUND)
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun nextDrawingPlayer() {
        drawingPlayer?.isDrawing = false
        if (players.isEmpty()) return

        drawingPlayer = if (drawingPlayerIndex <= players.size - 1) {
            players[drawingPlayerIndex]
        } else {
            players.last()
        }

        if (drawingPlayerIndex < players.size - 1) {
            drawingPlayerIndex++
        } else {
            drawingPlayerIndex = 0
        }
    }

    private fun isGuessCorrect(guess: ChatMessage): Boolean {
        return guess.matchesWord(word ?: return false) &&
                !winningPlayers.contains(guess.from) &&
                guess.from != drawingPlayer?.userName && phase == Phase.GAME_RUNNING
    }

    private fun addWinningPlayer(username: String): Boolean {
        winningPlayers = winningPlayers + username
        if (winningPlayers.size == players.size - 1) {
            phase = Phase.NEW_ROUND
            return true
        }
        return false
    }

    private suspend fun broadcastPlayerStates() {
        val playersList = players
            .sortedByDescending { it.score }
            .map {
                PlayerData(it.userName, it.isDrawing, it.score, it.rank)
            }
        playersList.forEachIndexed { index, playerData ->
            playerData.rank = index + 1
        }
        broadcast(gson.toJson(PlayersList(playersList)))
    }

    private suspend fun sendWordToPlayer(player: Player) {
        val delay = when (phase) {
            Phase.WAITING_FOR_START -> DElAY_WAITING_FOR_START_TO_NEW_ROUND
            Phase.NEW_ROUND -> DELAY_NEW_ROUND_TO_GAME_RUNNING
            Phase.GAME_RUNNING -> DELAY_GAME_RUNNING_TO_SHOW_WORD
            Phase.SHOW_WORD -> DELAY_SHOW_WORD_TO_NEW_ROUND
            else -> 0L
        }
        val phaseChange = PhaseChange(phase, delay, drawingPlayer?.userName)

        word?.let { currentWord ->
            drawingPlayer?.let { drawingPlayer ->
                val gameState = GameState(
                    drawingPlayer.userName,
                    if (player.isDrawing || phase == Phase.SHOW_WORD) {
                        currentWord
                    } else {
                        currentWord.transformToUnderscores()
                    }
                )
                player.socket.send(Frame.Text(gson.toJson(gameState)))
            }
        }
        player.socket.send(Frame.Text(gson.toJson(phaseChange)))
    }

    private suspend fun sendCurRoundDrawInfoToPlayer(player: Player) {
        if (phase == Phase.GAME_RUNNING || phase == Phase.SHOW_WORD) {
            player.socket.send(Frame.Text(gson.toJson(RoundDrawInfo(curRoundDrawData))))
        }
    }

    private suspend fun finishOffDrawing() {
        lastDrawData?.let {
            if (curRoundDrawData.isNotEmpty() && it.motionEvent == 2) {
                val finishDrawData = it.copy(motionEvent = 1)
                broadcast(gson.toJson(finishDrawData))
            }
        }
    }

    private fun kill() {
        playerRemoveJobs.values.forEach { it.cancel() }
        timerJob?.cancel()
    }

    enum class Phase {
        WAITING_FOR_PLAYERS,
        WAITING_FOR_START,
        NEW_ROUND,
        GAME_RUNNING,
        SHOW_WORD
    }

    companion object {

        const val UPDATE_TIME_FREQUENCY = 1_000L // millis in one second

        const val PLAYER_REMOVE_TIME = 60_000L

        const val DElAY_WAITING_FOR_START_TO_NEW_ROUND = 10_000L
        const val DELAY_NEW_ROUND_TO_GAME_RUNNING = 20_000L
        const val DELAY_GAME_RUNNING_TO_SHOW_WORD = 60_000L
        const val DELAY_SHOW_WORD_TO_NEW_ROUND = 10_000L

        const val PENALTY_NOBODY_GUESSED_IT = 50
        const val GUESS_SCORE_DEFAULT = 50
        const val GUESS_SCORE_PERCENTAGE_MULTIPLIER = 50
        const val GUESS_SCORE_FOR_DRAWING_PLAYER = 50
    }
}
