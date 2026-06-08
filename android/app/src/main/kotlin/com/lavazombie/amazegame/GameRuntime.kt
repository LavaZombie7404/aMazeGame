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

    // Cached maze structure for the auto-solver BFS — refreshed on nextRound.
    private var autoWalls: ByteArray = ByteArray(0)
    private var autoSize: Int = 0
    private var autoGoalX: Int = 0
    private var autoGoalY: Int = 0
    private var autoLastCellX: Int = -1
    private var autoLastCellY: Int = -1
    private var autoDir: Int = -1

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

    fun setAutoMode(value: Boolean) {
        store.autoMode = value
        _player.value = _player.value.copy(autoMode = value)
        autoLastCellX = -1
        autoLastCellY = -1
        autoDir = -1
    }

    fun reset() {
        nextRound(size = pickSize())
    }

    fun nextRound(size: Int, seed: Int = Random.nextInt()) {
        if (gamePtr != 0L) bridge.gameDrop(gamePtr)
        gamePtr = bridge.gameNew(size, seed)
        // Per-game flag — re-apply on each new round.
        bridge.setLegacyMovement(gamePtr, _player.value.legacyMovement)
        // Cache maze structure for the BFS auto-solver.
        autoWalls = bridge.mazeWalls(gamePtr)
        autoSize = bridge.mazeSize(gamePtr)
        val goal = bridge.mazeGoal(gamePtr)
        autoGoalX = goal[0]
        autoGoalY = goal[1]
        autoLastCellX = -1
        autoLastCellY = -1
        autoDir = -1
        publish()
    }

    fun tick(dtMs: Int): Boolean {
        // Auto-solver: queue the BFS first-step direction whenever the player
        // sits at a new logical cell. The core's queue_direction is a no-op if
        // the same direction is queued repeatedly, so this is cheap.
        if (_player.value.autoMode && autoSize > 0) {
            val cell = bridge.playerCell(gamePtr)
            val cx = cell[0]
            val cy = cell[1]
            if (cx != autoLastCellX || cy != autoLastCellY) {
                autoLastCellX = cx
                autoLastCellY = cy
                autoDir = bfsFirstDir(autoWalls, autoSize, cx, cy, autoGoalX, autoGoalY)
            }
            if (autoDir >= 0) bridge.queueDirection(gamePtr, autoDir)
        }

        val scaled = (dtMs * _player.value.speedMultiplier)
            .coerceAtLeast(0f)
            .toInt()
            .coerceAtLeast(1)
        val flags = bridge.step(gamePtr, scaled)
        publish()
        val reachedGoal = (flags and 1) != 0
        if (reachedGoal) {
            val auto = _player.value.autoMode
            val n = if (auto) {
                _player.value.mazesCompleted
            } else {
                val nc = store.incrementMazesCompleted()
                _player.value = _player.value.copy(mazesCompleted = nc)
                nc
            }
            nextRound(size = pickSize(simple = n % 2 == 0))
        }
        return reachedGoal
    }

    /**
     * BFS from (fromX, fromY) to (goalX, goalY) over the cached wall mask.
     * Returns the direction of the first step on the shortest path, or -1
     * if `from` already equals the goal (or the goal is unreachable, which
     * shouldn't happen for a perfect maze).
     */
    private fun bfsFirstDir(
        walls: ByteArray, size: Int,
        fromX: Int, fromY: Int,
        goalX: Int, goalY: Int,
    ): Int {
        if (fromX == goalX && fromY == goalY) return -1
        val visited = BooleanArray(size * size)
        val parentDir = IntArray(size * size) { -1 }
        val queue = ArrayDeque<Int>()
        val fromIdx = fromY * size + fromX
        queue.addLast(fromIdx)
        visited[fromIdx] = true
        val vx = intArrayOf(0, 1, 0, -1)
        val vy = intArrayOf(-1, 0, 1, 0)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val cx = cur % size
            val cy = cur / size
            if (cx == goalX && cy == goalY) {
                // Walk back to the cell whose parent is fromIdx.
                var idx = cur
                while (true) {
                    val dir = parentDir[idx]
                    if (dir < 0) return -1 // safety; shouldn't happen
                    val px = (idx % size) - vx[dir]
                    val py = (idx / size) - vy[dir]
                    val pIdx = py * size + px
                    if (pIdx == fromIdx) return dir
                    idx = pIdx
                }
            }
            val mask = walls[cur].toInt() and 0xff
            for (d in 0..3) {
                if (mask and (1 shl d) != 0) continue
                val nx = cx + vx[d]
                val ny = cy + vy[d]
                if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
                val nIdx = ny * size + nx
                if (visited[nIdx]) continue
                visited[nIdx] = true
                parentDir[nIdx] = d
                queue.addLast(nIdx)
            }
        }
        return -1
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
        autoMode = store.autoMode,
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
    val autoMode: Boolean = false,
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
