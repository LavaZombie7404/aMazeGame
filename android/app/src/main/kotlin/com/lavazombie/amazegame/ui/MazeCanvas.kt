package com.lavazombie.amazegame.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lavazombie.amazegame.Direction
import com.lavazombie.amazegame.GameRuntime
import com.lavazombie.amazegame.GameState
import com.lavazombie.amazegame.PlayerStore
import com.lavazombie.amazegame.apply
import com.lavazombie.amazegame.ui.shapes.drawShape
import com.lavazombie.amazegame.ui.skins.TrailStyle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renders whatever skin the player has active. The skin drives background,
 * walls, character/start/goal shapes (subject to player overrides) and the
 * trail style. The renderer itself is skin-agnostic.
 */
private const val MAX_CELLS_ON_SCREEN = 17

@Composable
fun MazeCanvas(game: GameRuntime, modifier: Modifier = Modifier) {
    val state by game.state.collectAsStateWithLifecycle()
    val player by game.player.collectAsStateWithLifecycle()
    val skin = player.skin
    val overrides = player.overrides

    LaunchedEffect(Unit) {
        var last = System.nanoTime()
        while (true) {
            val now = System.nanoTime()
            val dtMs = ((now - last) / 1_000_000L).toInt().coerceAtMost(50)
            last = now
            game.tick(dtMs)
            delay(16)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(skin.palette.paper)
            .pointerInput(Unit) {
                var startOffset = Offset.Zero
                var lastDir: Direction? = null
                detectDragGestures(
                    onDragStart = {
                        startOffset = it
                        lastDir = null
                    },
                    onDrag = { change, _ ->
                        val dx = change.position.x - startOffset.x
                        val dy = change.position.y - startOffset.y
                        if (dx * dx + dy * dy < 24f * 24f) return@detectDragGestures
                        val dir = if (abs(dx) > abs(dy)) {
                            if (dx > 0) Direction.E else Direction.W
                        } else {
                            if (dy > 0) Direction.S else Direction.N
                        }
                        if (dir != lastDir) {
                            game.queue(dir)
                            lastDir = dir
                            startOffset = change.position
                        }
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = state
            if (s.mazeSize == 0) return@Canvas
            val cell = max(8f, min(size.width, size.height) / MAX_CELLS_ON_SCREEN)
            val board = cell * s.mazeSize
            val offsetX = (size.width - board) / 2f
            val offsetY = (size.height - board) / 2f

            skin.drawBackground(this, size, cell, offsetX, offsetY)
            drawTrail(s, cell, offsetX, offsetY, skin.trail)

            val rng = Mulberry32(s.hashHex.hashCode() xor 0x9e3779b9.toInt())
            for (y in 0 until s.mazeSize) {
                for (x in 0 until s.mazeSize) {
                    val mask = s.walls[y * s.mazeSize + x].toInt() and 0xff
                    val px = offsetX + x * cell
                    val py = offsetY + y * cell
                    if (mask and 0b0001 != 0) {
                        skin.drawWall(this, px, py, px + cell, py) { rng.next() }
                    }
                    if (mask and 0b1000 != 0) {
                        skin.drawWall(this, px, py, px, py + cell) { rng.next() }
                    }
                    if (y == s.mazeSize - 1 && mask and 0b0100 != 0) {
                        skin.drawWall(this, px, py + cell, px + cell, py + cell) { rng.next() }
                    }
                    if (x == s.mazeSize - 1 && mask and 0b0010 != 0) {
                        skin.drawWall(this, px + cell, py, px + cell, py + cell) { rng.next() }
                    }
                }
            }

            // Bridge markers — small "+" glyph at every weave cell so the
            // player can tell where they'll be forced straight-through.
            val bridgeStrokeWidth = max(1.4f, cell * 0.06f)
            for (y in 0 until s.mazeSize) {
                for (x in 0 until s.mazeSize) {
                    val mask = s.walls[y * s.mazeSize + x].toInt() and 0xff
                    if (mask and 0b00010000 == 0) continue
                    val cx = offsetX + (x + 0.5f) * cell
                    val cy = offsetY + (y + 0.5f) * cell
                    val r = cell * 0.22f
                    drawLine(
                        skin.palette.ink.copy(alpha = 0.45f),
                        Offset(cx - r, cy),
                        Offset(cx + r, cy),
                        strokeWidth = bridgeStrokeWidth,
                    )
                    drawLine(
                        skin.palette.ink.copy(alpha = 0.45f),
                        Offset(cx, cy - r),
                        Offset(cx, cy + r),
                        strokeWidth = bridgeStrokeWidth,
                    )
                }
            }

            // start
            val startCenter = Offset(offsetX + (s.startX + 0.5f) * cell, offsetY + (s.startY + 0.5f) * cell)
            drawShape(overrides.apply(PlayerStore.ShapeSlot.Start, skin.start), startCenter, cell)
            // goal
            val goalCenter = Offset(offsetX + (s.goalX + 0.5f) * cell, offsetY + (s.goalY + 0.5f) * cell)
            drawShape(overrides.apply(PlayerStore.ShapeSlot.Goal, skin.goal), goalCenter, cell)
            // player + soft shadow under it
            val playerCenter = Offset(offsetX + (s.playerX + 0.5f) * cell, offsetY + (s.playerY + 0.5f) * cell)
            val character = overrides.apply(PlayerStore.ShapeSlot.Character, skin.character)
            val r = max(4f, cell * character.sizeFactor * 0.5f)
            drawCircle(Color(0x2E1D2433), radius = r, center = Offset(playerCenter.x + 1.5f, playerCenter.y + 2f))
            drawShape(character, playerCenter, cell)
        }
    }
}

/** Mulberry32 — same byte-for-byte seed as the web's wall RNG. */
private class Mulberry32(seed: Int) {
    private var state: Int = seed
    fun next(): Float {
        state = state + 0x6d2b79f5.toInt()
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
        val u = (t xor (t ushr 14)).toLong() and 0xFFFFFFFFL
        return u.toFloat() / 4_294_967_296f
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrail(
    s: GameState,
    cell: Float,
    offsetX: Float,
    offsetY: Float,
    trail: TrailStyle,
) {
    if (s.visitedCells.isEmpty()) return
    val visited = s.visitedCells.toHashSet()
    val n = s.mazeSize
    val path = Path()
    for (idx in s.visitedCells) {
        val x = idx % n
        val y = idx / n
        val cx = offsetX + (x + 0.5f) * cell
        val cy = offsetY + (y + 0.5f) * cell
        val mask = s.walls[idx].toInt() and 0xff
        if (x + 1 < n && visited.contains(idx + 1) && mask and 0b0010 == 0) {
            path.moveTo(cx, cy); path.lineTo(offsetX + (x + 1 + 0.5f) * cell, cy)
        }
        if (y + 1 < n && visited.contains(idx + n) && mask and 0b0100 == 0) {
            path.moveTo(cx, cy); path.lineTo(cx, offsetY + (y + 1 + 0.5f) * cell)
        }
    }
    drawPath(path, trail.color.copy(alpha = trail.alpha), style = Stroke(width = trail.width))
}
