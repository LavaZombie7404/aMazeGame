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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lavazombie.amazegame.Direction
import com.lavazombie.amazegame.GameRuntime
import com.lavazombie.amazegame.GameState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Compose Canvas renderer for the math-textbook skin, parity with
 * `web/src/skins/math-textbook.ts`. Reads `GameRuntime.state` (driven by the
 * WAMR-hosted Rust core), draws the same notebook paper + hand-drawn
 * pen-stroke walls + dot character.
 */
private const val MAX_CELLS_ON_SCREEN = 17

private val PAPER = Color(0xFFF7F3E8)
private val PAPER_LINE = Color(0xFFB9C7D8)
private val PAPER_MARGIN = Color(0xFFE9A3A6)
private val INK = Color(0xFF1D2433)
private val INK_SOFT = Color(0xFF2A3450)
private val DOT = Color(0xFFC83B3B)
private val GOAL = Color(0xFF2F6FB8)
private val TRAIL = Color(0xFF7896C2)

@Composable
fun MazeCanvas(game: GameRuntime, modifier: Modifier = Modifier) {
    val state by game.state.collectAsStateWithLifecycle()

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
            .background(PAPER)
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

            drawPaper(size, cell, offsetX, offsetY)
            drawTrail(s, cell, offsetX, offsetY)
            // Deterministic wobble per maze so walls don't shimmer between frames.
            val rng = Mulberry32(s.hashHex.hashCode() xor 0x9e3779b9.toInt())
            drawWalls(s, cell, offsetX, offsetY, rng)

            drawGoalMarker(s, cell, offsetX, offsetY)
            drawPlayer(s, cell, offsetX, offsetY)
        }
    }
}

// --- Mulberry32 ports the same seed-deterministic RNG the web uses so the
// wobble is stable across frames. Not cryptographic.
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

private fun DrawScope.drawPaper(
    canvasSize: Size,
    cell: Float,
    offsetX: Float,
    offsetY: Float,
) {
    val offX = ((offsetX % cell) + cell) % cell
    val offY = ((offsetY % cell) + cell) % cell
    var x = offX
    while (x < canvasSize.width) {
        drawLine(PAPER_LINE.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, canvasSize.height), strokeWidth = 1f)
        x += cell
    }
    var y = offY
    while (y < canvasSize.height) {
        drawLine(PAPER_LINE.copy(alpha = 0.5f), Offset(0f, y), Offset(canvasSize.width, y), strokeWidth = 1f)
        y += cell
    }
    val marginX = min(48f, canvasSize.width * 0.08f)
    drawLine(PAPER_MARGIN.copy(alpha = 0.7f), Offset(marginX, 0f), Offset(marginX, canvasSize.height), strokeWidth = 1.2f)
}

/** Web parity: see `web/src/skins/math-textbook.ts`'s `penStroke`. */
private fun DrawScope.penStroke(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    rng: Mulberry32,
) {
    val dx = x2 - x1
    val dy = y2 - y1
    val len = hypot(dx, dy)
    if (len == 0f) return
    val nx = -dy / len
    val ny = dx / len
    val steps = max(6, (len / 6f).toInt())

    val overshoot = 1.5f + rng.next() * 2.5f
    val sx = x1 - dx / len * overshoot
    val sy = y1 - dy / len * overshoot
    val ex = x2 + dx / len * overshoot
    val ey = y2 + dy / len * overshoot

    val lineWidth = 2.2f + rng.next() * 0.6f

    val path = Path().apply {
        moveTo(sx, sy)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val px = sx + (ex - sx) * t
            val py = sy + (ey - sy) * t
            val wobble = (rng.next() - 0.5f) * 1.6f
            lineTo(px + nx * wobble, py + ny * wobble)
        }
    }
    drawPath(
        path,
        INK,
        style = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    // Occasional ink pool at the start endpoint.
    if (rng.next() < 0.35f) {
        drawCircle(INK, radius = 1.5f + rng.next(), center = Offset(sx, sy))
    }
}

private fun DrawScope.drawWalls(
    s: GameState,
    cell: Float,
    offsetX: Float,
    offsetY: Float,
    rng: Mulberry32,
) {
    val n = s.mazeSize
    for (y in 0 until n) {
        for (x in 0 until n) {
            val mask = s.walls[y * n + x].toInt() and 0xff
            val px = offsetX + x * cell
            val py = offsetY + y * cell
            if (mask and 0b0001 != 0) {        // N
                penStroke(px, py, px + cell, py, rng)
            }
            if (mask and 0b1000 != 0) {        // W
                penStroke(px, py, px, py + cell, rng)
            }
            if (y == n - 1 && mask and 0b0100 != 0) {   // S boundary
                penStroke(px, py + cell, px + cell, py + cell, rng)
            }
            if (x == n - 1 && mask and 0b0010 != 0) {   // E boundary
                penStroke(px + cell, py, px + cell, py + cell, rng)
            }
        }
    }
}

private fun DrawScope.drawTrail(
    s: GameState,
    cell: Float,
    offsetX: Float,
    offsetY: Float,
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
    drawPath(path, TRAIL.copy(alpha = 0.55f), style = Stroke(width = 4f))
}

private fun DrawScope.drawGoalMarker(
    s: GameState,
    cell: Float,
    offsetX: Float,
    offsetY: Float,
) {
    val cx = offsetX + (s.goalX + 0.5f) * cell
    val cy = offsetY + (s.goalY + 0.5f) * cell
    val r = cell * 0.28f
    drawCircle(GOAL, radius = r, center = Offset(cx, cy), style = Stroke(width = 2f))
    drawCircle(GOAL.copy(alpha = 0.6f), radius = r * 0.55f, center = Offset(cx, cy))
}

private fun DrawScope.drawPlayer(
    s: GameState,
    cell: Float,
    offsetX: Float,
    offsetY: Float,
) {
    val cx = offsetX + (s.playerX + 0.5f) * cell
    val cy = offsetY + (s.playerY + 0.5f) * cell
    val r = max(4f, cell * 0.22f)
    // soft shadow
    drawCircle(Color(0x2E1D2433), radius = r, center = Offset(cx + 1.5f, cy + 2f))
    drawCircle(DOT, radius = r, center = Offset(cx, cy))
    drawCircle(INK_SOFT, radius = r, center = Offset(cx, cy), style = Stroke(width = 1.3f))
    // suppress unused warnings on imports
    @Suppress("UNUSED_VARIABLE") val _unused = sqrt(1.0)
}
