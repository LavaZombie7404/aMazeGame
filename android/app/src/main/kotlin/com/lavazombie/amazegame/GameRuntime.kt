package com.lavazombie.amazegame

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * High-level game container. Wraps the CoreBridge (WAMR + Rust core) and
 * keeps a StateFlow that Compose + the puppet HTTP server both observe.
 *
 * Owns the difficulty cadence and the per-completion bookkeeping (mirrors
 * web's main.ts).
 */
class GameRuntime(context: Context) {

    private val bridge = CoreBridge(context)
    private val store = PlayerStore(context)
    private var gamePtr: Long = 0L

    private val _state = MutableStateFlow(GameState.EMPTY)
    val state: StateFlow<GameState> = _state

    private val _player = MutableStateFlow(PlayerState(name = store.name, mazesCompleted = store.mazesCompleted))
    val player: StateFlow<PlayerState> = _player

    fun boot() {
        bridge.init()
        nextRound(size = pickSize())
    }

    fun shutdown() {
        if (gamePtr != 0L) bridge.gameDrop(gamePtr)
        bridge.close()
    }

    fun setPlayerName(name: String) {
        store.name = name
        _player.value = _player.value.copy(name = name)
    }

    fun reset() {
        nextRound(size = pickSize())
    }

    fun nextRound(size: Int, seed: Int = Random.nextInt()) {
        if (gamePtr != 0L) bridge.gameDrop(gamePtr)
        gamePtr = bridge.gameNew(size, seed)
        publish()
    }

    /**
     * Advance the simulation. Returns true if the player just reached the
     * goal — caller should award score + spawn a new maze. We do the
     * bookkeeping internally so both Compose and the puppet HTTP path stay
     * in sync without duplicating logic.
     */
    fun tick(dtMs: Int): Boolean {
        val flags = bridge.step(gamePtr, dtMs)
        publish()
        val reachedGoal = (flags and 1) != 0
        if (reachedGoal) {
            val n = store.incrementMazesCompleted()
            _player.value = _player.value.copy(mazesCompleted = n)
            // PRD §6.0 alternation: parity drives bucket. The seed is
            // independent random; we just pick a size in the right bucket.
            nextRound(size = pickSize(simple = n % 2 == 0))
        }
        return reachedGoal
    }

    fun queue(direction: Direction) {
        bridge.queueDirection(gamePtr, direction.ordinal)
    }

    private fun publish() {
        if (gamePtr == 0L) return
        val size = bridge.mazeSize(gamePtr)
        val start = bridge.mazeStart(gamePtr)
        val goal = bridge.mazeGoal(gamePtr)
        val player = bridge.playerRender(gamePtr)
        _state.value = GameState(
            mazeSize = size,
            startX = start[0],
            startY = start[1],
            goalX = goal[0],
            goalY = goal[1],
            walls = bridge.mazeWalls(gamePtr),
            playerX = player[0],
            playerY = player[1],
            visitedCells = bridge.visited(gamePtr),
            hashHex = bridge.mazeHash(gamePtr).toHex(),
            seed = _state.value.seed, // seed isn't exported by the core; keep last
        )
    }

    private fun pickSize(simple: Boolean = _player.value.mazesCompleted % 2 == 0): Int =
        if (simple) Random.nextInt(12, 15) else Random.nextInt(15, 18)
}

/** N, E, S, W — same numbering as the WASM ABI. */
enum class Direction { N, E, S, W }

data class PlayerState(
    val name: String?,
    val mazesCompleted: Int,
)

data class GameState(
    val mazeSize: Int,
    val startX: Int,
    val startY: Int,
    val goalX: Int,
    val goalY: Int,
    val walls: ByteArray,
    val playerX: Float,
    val playerY: Float,
    val visitedCells: IntArray,
    val hashHex: String,
    val seed: Int = 0,
) {
    companion object {
        val EMPTY = GameState(
            mazeSize = 0,
            startX = 0, startY = 0, goalX = 0, goalY = 0,
            walls = ByteArray(0),
            playerX = 0f, playerY = 0f,
            visitedCells = IntArray(0),
            hashHex = "",
            seed = 0,
        )
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
