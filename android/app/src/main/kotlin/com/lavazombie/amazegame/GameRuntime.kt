package com.lavazombie.amazegame

import android.content.Context
import com.lavazombie.amazegame.ui.shapes.ShapeRef
import com.lavazombie.amazegame.ui.skins.DEFAULT_SKIN_ID
import com.lavazombie.amazegame.ui.skins.Skin
import com.lavazombie.amazegame.ui.skins.getSkin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * High-level game container. Wraps the CoreBridge (WAMR + Rust core) and
 * keeps a StateFlow that Compose + the puppet HTTP server both observe.
 * Owns the difficulty cadence + per-completion bookkeeping.
 */
class GameRuntime(context: Context) {

    private val bridge = CoreBridge(context)
    private val store = PlayerStore(context)
    private var gamePtr: Long = 0L

    private val _state = MutableStateFlow(GameState.EMPTY)
    val state: StateFlow<GameState> = _state

    private val _player = MutableStateFlow(loadPlayer())
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

    fun setSkin(skinId: String) {
        store.skinId = skinId
        _player.value = _player.value.copy(skin = getSkin(skinId))
    }

    fun setShapeOverride(slot: PlayerStore.ShapeSlot, shapeName: String?) {
        store.setShapeOverride(slot, shapeName)
        _player.value = _player.value.copy(overrides = loadOverrides())
    }

    fun setColorOverride(slot: PlayerStore.ShapeSlot, hex: String?) {
        store.setColorOverride(slot, hex)
        _player.value = _player.value.copy(overrides = loadOverrides())
    }

    fun setLegacyMovement(value: Boolean) {
        store.legacyMovement = value
        _player.value = _player.value.copy(legacyMovement = value)
        if (gamePtr != 0L) bridge.setLegacyMovement(gamePtr, value)
    }

    fun setSpeedMultiplier(value: Float) {
        store.speedMultiplier = value
        _player.value = _player.value.copy(speedMultiplier = value)
    }

    fun reset() {
        nextRound(size = pickSize())
    }

    fun nextRound(size: Int, seed: Int = Random.nextInt()) {
        if (gamePtr != 0L) bridge.gameDrop(gamePtr)
        gamePtr = bridge.gameNew(size, seed)
        // Per-game flag — re-apply on each new round.
        bridge.setLegacyMovement(gamePtr, _player.value.legacyMovement)
        publish()
    }

    fun tick(dtMs: Int): Boolean {
        val scaled = (dtMs * _player.value.speedMultiplier)
            .coerceAtLeast(0f)
            .toInt()
            .coerceAtLeast(1)
        val flags = bridge.step(gamePtr, scaled)
        publish()
        val reachedGoal = (flags and 1) != 0
        if (reachedGoal) {
            val n = store.incrementMazesCompleted()
            _player.value = _player.value.copy(mazesCompleted = n)
            // PRD §6.0 alternation: parity drives bucket.
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
        )
    }

    private fun pickSize(simple: Boolean = _player.value.mazesCompleted % 2 == 0): Int =
        if (simple) Random.nextInt(12, 15) else Random.nextInt(15, 18)

    private fun loadPlayer(): PlayerState = PlayerState(
        name = store.name,
        mazesCompleted = store.mazesCompleted,
        skin = getSkin(store.skinId.ifBlank { DEFAULT_SKIN_ID }),
        overrides = loadOverrides(),
        legacyMovement = store.legacyMovement,
        speedMultiplier = store.speedMultiplier,
    )

    private fun loadOverrides() = ShapeOverrides(
        character = store.shapeOverride(PlayerStore.ShapeSlot.Character),
        start = store.shapeOverride(PlayerStore.ShapeSlot.Start),
        goal = store.shapeOverride(PlayerStore.ShapeSlot.Goal),
        characterColor = store.colorOverride(PlayerStore.ShapeSlot.Character),
        startColor = store.colorOverride(PlayerStore.ShapeSlot.Start),
        goalColor = store.colorOverride(PlayerStore.ShapeSlot.Goal),
    )
}

enum class Direction { N, E, S, W }

data class PlayerState(
    val name: String?,
    val mazesCompleted: Int,
    val skin: Skin,
    val overrides: ShapeOverrides,
    val legacyMovement: Boolean = false,
    val speedMultiplier: Float = 1f,
)

/**
 * Optional per-slot shape names. When non-null, the renderer uses this shape
 * keeping the skin's style + size factor (just like web).
 */
data class ShapeOverrides(
    val character: String? = null,
    val start: String? = null,
    val goal: String? = null,
    /** Hex strings like "#c83b3b"; null = inherit skin default. */
    val characterColor: String? = null,
    val startColor: String? = null,
    val goalColor: String? = null,
)

fun ShapeOverrides.apply(slot: PlayerStore.ShapeSlot, base: ShapeRef): ShapeRef {
    val shapeName = when (slot) {
        PlayerStore.ShapeSlot.Character -> character
        PlayerStore.ShapeSlot.Start -> start
        PlayerStore.ShapeSlot.Goal -> goal
    }
    val colorHex = when (slot) {
        PlayerStore.ShapeSlot.Character -> characterColor
        PlayerStore.ShapeSlot.Start -> startColor
        PlayerStore.ShapeSlot.Goal -> goalColor
    }
    val withName = if (shapeName != null) base.copy(name = shapeName) else base
    if (colorHex == null) return withName
    val color = parseColor(colorHex) ?: return withName
    return withName.copy(
        style = withName.style.copy(
            fill = color,
            stroke = color,
            innerFill = color,
        ),
    )
}

private fun parseColor(hex: String): androidx.compose.ui.graphics.Color? = try {
    androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    null
}

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
) {
    companion object {
        val EMPTY = GameState(
            mazeSize = 0,
            startX = 0, startY = 0, goalX = 0, goalY = 0,
            walls = ByteArray(0),
            playerX = 0f, playerY = 0f,
            visitedCells = IntArray(0),
            hashHex = "",
        )
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
