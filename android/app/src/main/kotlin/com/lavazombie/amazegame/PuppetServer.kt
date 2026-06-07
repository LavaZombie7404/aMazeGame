package com.lavazombie.amazegame

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Local HTTP server that exposes a control + observation surface for Claude
 * Code (or any other tool) to puppet the running game. Mirrors the
 * chrome://inspect / CDP workflow we already use against the web version —
 * `adb forward tcp:8088 tcp:8088` on the dev machine and the endpoints below
 * become reachable over `http://localhost:8088/…`.
 *
 * Only binds 127.0.0.1 so the device exposes nothing on the wider network.
 */
class PuppetServer(private val game: GameRuntime, private val port: Int = 8088) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                install(ContentNegotiation) { json() }
                routing {
                    get("/health") {
                        call.respond(
                            HealthResponse(
                                ok = true,
                                abiVersion = CoreBridge.EXPECTED_ABI_VERSION,
                                platform = "android",
                            ),
                        )
                    }
                    get("/state") {
                        call.respond(snapshot(game))
                    }
                    post("/control/swipe") {
                        val req = call.receive<SwipeRequest>()
                        val dir = Direction.entries.firstOrNull { it.name == req.direction.uppercase() }
                        if (dir == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown direction"))
                            return@post
                        }
                        game.queue(dir)
                        call.respond(snapshot(game))
                    }
                    post("/control/reset") {
                        val req = call.receive<ResetRequest>()
                        game.nextRound(req.size ?: 14, req.seed ?: kotlin.random.Random.nextInt())
                        call.respond(snapshot(game))
                    }
                    post("/scenarios") {
                        // Hook point for batch scenarios — receive a list of
                        // {action, args} steps + assertions and play them
                        // back. Stubbed for the initial scaffold.
                        call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "scenarios not implemented yet"))
                    }
                }
            }.start(wait = false)
        }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
    }

    private fun snapshot(g: GameRuntime): StateResponse {
        val s = g.state.value
        return StateResponse(
            mazeSize = s.mazeSize,
            start = listOf(s.startX, s.startY),
            goal = listOf(s.goalX, s.goalY),
            player = listOf(s.playerX, s.playerY),
            visitedLen = s.visitedCells.size,
            hash = s.hashHex,
        )
    }
}

@Serializable
private data class HealthResponse(val ok: Boolean, val abiVersion: Int, val platform: String)

@Serializable
private data class SwipeRequest(val direction: String)

@Serializable
private data class ResetRequest(val size: Int? = null, val seed: Int? = null)

@Serializable
private data class StateResponse(
    val mazeSize: Int,
    val start: List<Int>,
    val goal: List<Int>,
    val player: List<Float>,
    val visitedLen: Int,
    val hash: String,
)
