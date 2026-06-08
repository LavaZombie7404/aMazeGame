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

    // Precomputed shortest-path solution keyed by (cell, entry-axis). Indexed
    // as `cellIdx * 3 + axis`, axis ∈ {0 = horizontal (E/W), 1 = vertical
    // (N/S), 2 = start (no entry yet)}. -1 means the state isn't on the path.
    // State-space BFS so bridge cells route correctly (entered horizontally
    // ⇒ exit horizontally; the perpendicular passage is unreachable).
    private var autoSize: Int = 0
    private var pathLookup: IntArray = IntArray(0)
    // Wall overlay sealing off-path edges. Only populated for non-weave
    // mazes — weave mazes can't be cleanly captured by a per-cell mask.
    private var pathExtraWalls: ByteArray = ByteArray(0)
    private var autoLastCellX: Int = -1
    private var autoLastCellY: Int = -1
    private var autoLastAxis: Int = 2 // start-state sentinel

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
        autoLastAxis = 2
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
     * Long-press on Reset: put the dot back at the start of the *same*
     * maze. Streak still breaks (otherwise it's a free do-over), and we
     * re-apply per-game flags (legacy movement, auto extra-walls overlay)
     * because resetting the movement state clears them on the Rust side.
     */
    fun resetCurrentMaze() {
        if (gamePtr == 0L) return
        store.currentStreak = 0
        _player.value = _player.value.copy(currentStreak = 0)
        bridge.resetPlayer(gamePtr)
        bridge.setLegacyMovement(gamePtr, _player.value.legacyMovement)
        applyExtraWalls()
        publish()
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
        // Precompute the shortest-path solution table + (for non-weave only)
        // an invisible-walls mask for the auto-solver.
        val walls = bridge.mazeWalls(gamePtr)
        val mSize = bridge.mazeSize(gamePtr)
        val start = bridge.mazeStart(gamePtr)
        val goal = bridge.mazeGoal(gamePtr)
        autoSize = mSize
        val solution = computePathSolution(
            walls, mSize, start[0], start[1], goal[0], goal[1],
            weave = _player.value.weaveMazes,
        )
        pathLookup = solution.first
        pathExtraWalls = solution.second
        autoLastCellX = start[0]
        autoLastCellY = start[1]
        autoLastAxis = 2
        applyExtraWalls()
        publish()
    }

    fun tick(dtMs: Int): Boolean {
        // Auto-solver: look up the precomputed direction for the dot's
        // current (cell, entry-axis) state. queue_direction is idempotent on
        // a same-direction call so re-queueing every tick is cheap.
        if (_player.value.autoMode && autoSize > 0 && pathLookup.isNotEmpty()) {
            val cell = bridge.playerCell(gamePtr)
            val cx = cell[0]
            val cy = cell[1]
            // Re-derive entry axis whenever the dot crosses a cell border.
            if (cx != autoLastCellX || cy != autoLastCellY) {
                val dx = cx - autoLastCellX
                val dy = cy - autoLastCellY
                val moveDir = when {
                    dx == 0 && dy == -1 -> 0 // N
                    dx == 1 && dy == 0 -> 1  // E
                    dx == 0 && dy == 1 -> 2  // S
                    dx == -1 && dy == 0 -> 3 // W
                    else -> -1
                }
                if (moveDir >= 0) {
                    autoLastAxis = if (moveDir == 0 || moveDir == 2) 1 else 0
                }
                autoLastCellX = cx
                autoLastCellY = cy
            }
            val stateKey = (cy * autoSize + cx) * 3 + autoLastAxis
            val d = pathLookup.getOrNull(stateKey) ?: -1
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
     * State-space BFS over (cell, entry-axis) pairs. At a bridge cell only
     * same-axis exits are valid (the perpendicular passage crosses *under*).
     * Returns
     *   `first`  = IntArray of size N²×3 where each `cellIdx*3+axis` slot
     *              holds the direction to take from that state on the
     *              shortest path, or -1 if the state is off the path.
     *              axis: 0 = horizontal entry (E/W), 1 = vertical (N/S),
     *              2 = start (no entry yet).
     *   `second` = ByteArray of size N² with bits set for every wall not on
     *              the path. Only meaningful for non-weave mazes — weave
     *              returns an empty array because the per-axis path
     *              semantics can't be encoded in a single per-cell mask.
     */
    private fun computePathSolution(
        walls: ByteArray, size: Int,
        startX: Int, startY: Int,
        goalX: Int, goalY: Int,
        weave: Boolean,
    ): Pair<IntArray, ByteArray> {
        val stateCount = size * size * 3
        val lookup = IntArray(stateCount) { -1 }
        val extra = if (weave) ByteArray(0) else ByteArray(size * size) { 0b1111.toByte() }
        val startIdx = startY * size + startX
        val goalIdx = goalY * size + goalX
        if (startIdx == goalIdx) {
            if (extra.isNotEmpty()) extra[startIdx] = 0
            return lookup to extra
        }

        val visited = BooleanArray(stateCount)
        val parentDir = IntArray(stateCount) { -1 }
        val parentState = IntArray(stateCount) { -1 }
        val vx = intArrayOf(0, 1, 0, -1)
        val vy = intArrayOf(-1, 0, 1, 0)
        val startState = startIdx * 3 + 2
        visited[startState] = true
        val queue = ArrayDeque<Int>()
        queue.addLast(startState)
        var goalState = -1
        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            val cellIdx = state / 3
            val axis = state % 3
            if (cellIdx == goalIdx) {
                goalState = state
                break
            }
            val cx = cellIdx % size
            val cy = cellIdx / size
            val mask = walls[cellIdx].toInt() and 0xff
            val cellIsBridge = (mask and 0b00010000) != 0
            for (d in 0..3) {
                if (mask and (1 shl d) != 0) continue
                val dAxis = if (d == 0 || d == 2) 1 else 0
                if (cellIsBridge && axis != 2 && axis != dAxis) continue
                val nx = cx + vx[d]
                val ny = cy + vy[d]
                if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
                val nIdx = ny * size + nx
                val nState = nIdx * 3 + dAxis
                if (visited[nState]) continue
                visited[nState] = true
                parentDir[nState] = d
                parentState[nState] = state
                queue.addLast(nState)
            }
        }
        if (goalState < 0) return lookup to extra

        var cur = goalState
        while (cur != startState) {
            val dir = parentDir[cur]
            if (dir < 0) break
            val prev = parentState[cur]
            lookup[prev] = dir
            if (extra.isNotEmpty()) {
                val prevCell = prev / 3
                val curCell = cur / 3
                extra[prevCell] = (extra[prevCell].toInt() and (1 shl dir).inv()).toByte()
                val backDir = (dir + 2) % 4
                extra[curCell] = (extra[curCell].toInt() and (1 shl backDir).inv()).toByte()
            }
            cur = prev
        }
        return lookup to extra
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
