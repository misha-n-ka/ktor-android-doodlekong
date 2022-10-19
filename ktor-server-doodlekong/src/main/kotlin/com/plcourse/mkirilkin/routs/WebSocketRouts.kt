package com.plcourse.mkirilkin.routs

import com.google.gson.JsonParser
import com.plcourse.mkirilkin.data.Room
import com.plcourse.mkirilkin.data.models.BaseModel
import com.plcourse.mkirilkin.data.models.ChatMessage
import com.plcourse.mkirilkin.data.models.DrawData
import com.plcourse.mkirilkin.gson
import com.plcourse.mkirilkin.server
import com.plcourse.mkirilkin.session.DrawingSession
import com.plcourse.mkirilkin.util.Constants.TYPE_CHAT_MESSAGE
import com.plcourse.mkirilkin.util.Constants.TYPE_DRAW_DATA
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

fun Route.gameWebSocketRoute() {
    route("/ws/draw") {
        standardWebSocket { socket, clientId, message, payload ->
            when(payload) {
                is DrawData -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    if (room.phase == Room.Phase.GAME_RUNNING) {
                        room.broadcastToAllExcept(message, clientId)
                    }
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