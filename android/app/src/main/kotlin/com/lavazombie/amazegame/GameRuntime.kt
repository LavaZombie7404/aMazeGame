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

    // Precomputed shortest-path solution for the current maze.
    // pathDir[cellIdx] = direction to take from that cell to advance toward
    // the goal along the BFS shortest path. -1 for cells off the path.
    // pathExtraWalls[cellIdx] = wall bits for every direction *not* on the
    // path — pushed into the core's `extra_walls` overlay when auto is on,
    // cleared when off.
    private var autoSize: Int = 0
    private var pathDir: IntArray = IntArray(0)
    private var pathExtraWalls: ByteArray = ByteArray(0)

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
        applyExtraWalls()
    }

    fun setWeaveMazes(value: Boolean) {
        store.weaveMazes = value
        _player.value = _player.value.copy(weaveMazes = value)
        // Effective on the next maze; existing maze keeps its wall layout
        // so we don't yank corridors out from under the player.
    }

    private fun applyExtraWalls() {
        if (gamePtr == 0L) return
        if (_player.value.autoMode && pathExtraWalls.isNotEmpty()) {
            bridge.setExtraWalls(gamePtr, pathExtraWalls)
        } else {
            bridge.setExtraWalls(gamePtr, null)
        }
    }

    fun reset() {
        // Resetting mid-maze breaks the user's streak.
        store.currentStreak = 0
        _player.value = _player.value.copy(currentStreak = 0)
        nextRound(size = pickSize())
    }

    /**
     * Today's UTC date as an integer seed (YYYYMMDD). Matches the web's
     * `dailySeed()` so the same maze ships across platforms.
     */
    fun loadDailyMaze() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val seed = cal.get(java.util.Calendar.YEAR) * 10000 +
            (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        nextRound(size = 15, seed = seed)
    }

    fun nextRound(size: Int, seed: Int = Random.nextInt()) {
        if (gamePtr != 0L) bridge.gameDrop(gamePtr)
        gamePtr = bridge.gameNewExt(size, seed, _player.value.weaveMazes)
        // Per-game flag — re-apply on each new round.
        bridge.setLegacyMovement(gamePtr, _player.value.legacyMovement)
        // Precompute the shortest-path solution table + invisible-walls mask
        // for the auto-solver.
        val walls = bridge.mazeWalls(gamePtr)
        val mSize = bridge.mazeSize(gamePtr)
        val start = bridge.mazeStart(gamePtr)
        val goal = bridge.mazeGoal(gamePtr)
        autoSize = mSize
        val solution = computePathSolution(walls, mSize, start[0], start[1], goal[0], goal[1])
        pathDir = solution.first
        pathExtraWalls = solution.second
        applyExtraWalls()
        publish()
    }

    fun tick(dtMs: Int): Boolean {
        // Auto-solver: look up the precomputed direction for the player's
        // current logical cell and queue it. queue_direction is idempotent
        // on a same-direction call so re-queueing every tick is cheap.
        if (_player.value.autoMode && autoSize > 0 && pathDir.isNotEmpty()) {
            val cell = bridge.playerCell(gamePtr)
            val idx = cell[1] * autoSize + cell[0]
            val d = pathDir.getOrNull(idx) ?: -1
            if (d >= 0) bridge.queueDirection(gamePtr, d)
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
                val nextStreak = store.currentStreak + 1
                store.currentStreak = nextStreak
                if (nextStreak > store.bestStreak) store.bestStreak = nextStreak
                _player.value = _player.value.copy(
                    mazesCompleted = nc,
                    currentStreak = nextStreak,
                    bestStreak = store.bestStreak,
                )
                Sfx.playGoalChime()
                nc
            }
            nextRound(size = pickSize(simple = n % 2 == 0))
        }
        return reachedGoal
    }

    /**
     * BFS start → goal, then walk back. Returns
     *   `first`  = IntArray of size N² where path cells hold the direction
     *              to the next cell on the shortest path, and off-path cells
     *              hold -1.
     *   `second` = ByteArray of size N² holding a wall mask whose bits are
     *              set for every direction *not* on the path. Pushed into
     *              the core's `extra_walls` overlay so the walker is locked
     *              onto the path without altering the rendered maze.
     */
    private fun computePathSolution(
        walls: ByteArray, size: Int,
        startX: Int, startY: Int,
        goalX: Int, goalY: Int,
    ): Pair<IntArray, ByteArray> {
        val dirs = IntArray(size * size) { -1 }
        val extra = ByteArray(size * size) { 0b1111.toByte() }
        val startIdx = startY * size + startX
        val goalIdx = goalY * size + goalX
        if (startIdx == goalIdx) {
            extra[startIdx] = 0
            return dirs to extra
        }

        val visited = BooleanArray(size * size)
        val parentDir = IntArray(size * size) { -1 }
        val queue = ArrayDeque<Int>()
        queue.addLast(startIdx)
        visited[startIdx] = true
        val vx = intArrayOf(0, 1, 0, -1)
        val vy = intArrayOf(-1, 0, 1, 0)
        var found = false
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == goalIdx) {
                found = true
                break
            }
            val cx = cur % size
            val cy = cur / size
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
        if (!found) return dirs to extra

        var idx = goalIdx
        while (idx != startIdx) {
            val dir = parentDir[idx]
            if (dir < 0) break
            val px = (idx % size) - vx[dir]
            val py = (idx / size) - vy[dir]
            val pIdx = py * size + px
            dirs[pIdx] = dir
            // Clear the forward-direction wall at the parent and the
            // backward-direction wall at the child on the path edge.
            extra[pIdx] = (extra[pIdx].toInt() and (1 shl dir).inv()).toByte()
            val backDir = (dir + 2) % 4
            extra[idx] = (extra[idx].toInt() and (1 shl backDir).inv()).toByte()
            idx = pIdx
        }
        return dirs to extra
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
        currentStreak = store.currentStreak,
        bestStreak = store.bestStreak,
        weaveMazes = store.weaveMazes,
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
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val weaveMazes: Boolean = false,
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
