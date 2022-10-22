package com.plcourse.mkirilkin.routs

import com.google.gson.JsonParser
import com.plcourse.mkirilkin.data.Player
import com.plcourse.mkirilkin.data.Room
import com.plcourse.mkirilkin.data.models.messages.*
import com.plcourse.mkirilkin.gson
import com.plcourse.mkirilkin.server
import com.plcourse.mkirilkin.session.DrawingSession
import com.plcourse.mkirilkin.util.Constants.TYPE_ANNOUNCEMENT
import com.plcourse.mkirilkin.util.Constants.TYPE_CHAT_MESSAGE
import com.plcourse.mkirilkin.util.Constants.TYPE_CHOSEN_WORD
import com.plcourse.mkirilkin.util.Constants.TYPE_DRAW_DATA
import com.plcourse.mkirilkin.util.Constants.TYPE_JOIN_ROOM_HANDSHAKE
import com.plcourse.mkirilkin.util.Constants.TYPE_PHASE_CHANGE
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

fun Route.gameWebSocketRoute() {
    route("/ws/draw") {
        standardWebSocket { socket, clientId, message, payload ->
            when (payload) {
                is JoinRoomHandshake -> {
                    val room = server.rooms[payload.roomName]
                    if (room == null) {
                        val gameError = GameError(GameError.ERROR_ROOM_NOT_FOUND)
                        socket.send(Frame.Text(gson.toJson(gameError)))
                        return@standardWebSocket
                    }
                    val player = Player(
                        payload.username,
                        socket,
                        payload.clientId
                    )
                    server.playerJoined(player)
                    if (!room.containsPlayer(player.userName)) {
                        room.addPlayer(player.clientId, player.userName, socket)
                    }
                }

                is DrawData -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    if (room.phase == Room.Phase.GAME_RUNNING) {
                        room.broadcastToAllExcept(message, clientId)
                    }
                }

                is ChosenWord -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    room.setWordAndSwitchToGameRunning(payload.chosenWord)
                }

                is ChatMessage -> {

                }
            }
        }
    }
}

fun Route.standardWebSocket(
    handleFrame: suspend (
        socket: DefaultWebSocketServerSession,
        clientId: String,
        message: String,
        payload: BaseModel
    ) -> Unit
) {
    webSocket {
        val sessions = call.sessions.get<DrawingSession>()
        if (sessions == null) {
            close(
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session")
            )
            return@webSocket
        }
        try {
            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val message = frame.readText()
                    val jsonObject = JsonParser.parseString(message).asJsonObject
                    val type = when (jsonObject.get("type").asString) {
                        TYPE_CHAT_MESSAGE -> ChatMessage::class.java
                        TYPE_DRAW_DATA -> DrawData::class.java
                        TYPE_ANNOUNCEMENT -> Announcement::class.java
                        TYPE_JOIN_ROOM_HANDSHAKE -> JoinRoomHandshake::class.java
                        TYPE_PHASE_CHANGE -> PhaseChange::class.java
                        TYPE_CHOSEN_WORD -> ChosenWord::class.java
                        else -> BaseModel::class.java
                    }
                    val payload = gson.fromJson(message, type)
                    handleFrame(this, sessions.clientId, message, payload)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Handle disconnects
        }
    }
}
