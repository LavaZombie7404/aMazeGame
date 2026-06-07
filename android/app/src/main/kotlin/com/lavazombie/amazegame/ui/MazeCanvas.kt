package com.lavazombie.amazegame.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lavazombie.amazegame.Direction
import com.lavazombie.amazegame.GameRuntime
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Compose Canvas renderer for the math-textbook skin on Android. Reads
 * GameRuntime.state (driven by the WAMR-hosted Rust core), draws the same
 * notebook paper + hand-drawn pen walls + dot character as the web version.
 *
 * Touch swipes are translated to Direction enums and pushed back into the
 * core via GameRuntime.queue.
 */
private const val MAX_CELLS_ON_SCREEN = 17

private val PAPER = Color(0xFFF7F3E8)
private val PAPER_LINE = Color(0xFFB9C7D8)
private val PAPER_MARGIN = Color(0xFFE9A3A6)
private val INK = Color(0xFF1D2433)
private val DOT = Color(0xFFC83B3B)
private val GOAL = Color(0xFF2F6FB8)
private val TRAIL = Color(0xFF7896C2)

@Composable
fun MazeCanvas(game: GameRuntime) {
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
        modifier = Modifier
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

            drawPaper(this, size, cell, offsetX, offsetY)
            drawTrail(this, s, cell, offsetX, offsetY)
            drawWalls(this, s, cell, offsetX, offsetY)

            // start & goal markers
            drawCircle(
                color = GOAL,
                radius = cell * 0.28f,
                center = Offset(offsetX + (s.goalX + 0.5f) * cell, offsetY + (s.goalY + 0.5f) * cell),
                style = Stroke(width = 3f),
            )
            // player dot
            drawCircle(
                color = DOT,
                radius = max(4f, cell * 0.22f),
                center = Offset(
                    offsetX + (s.playerX + 0.5f) * cell,
                    offsetY + (s.playerY + 0.5f) * cell,
                ),
            )
        }
    }
}

private fun drawPaper(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    canvasSize: Size,
    cell: Float,
    offsetX: Float,
    offsetY: Float,
) {
    val offX = ((offsetX % cell) + cell) % cell
    val offY = ((offsetY % cell) + cell) % cell
    var x = offX
    while (x < canvasSize.width) {
        scope.drawLine(PAPER_LINE, Offset(x, 0f), Offset(x, canvasSize.height), strokeWidth = 1f)
        x += cell
    }
    var y = offY
    while (y < canvasSize.height) {
        scope.drawLine(PAPER_LINE, Offset(0f, y), Offset(canvasSize.width, y), strokeWidth = 1f)
        y += cell
    }
    val marginX = min(48f, canvasSize.width * 0.08f)
    scope.drawLine(PAPER_MARGIN, Offset(marginX, 0f), Offset(marginX, canvasSize.height), strokeWidth = 1.2f)
}

private fun drawWalls(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    s: com.lavazombie.amazegame.GameState,
    cell: Float,
    offsetX: Float,
    offsetY: Float,
) {
    val path = Path()
    val n = s.mazeSize
    for (y in 0 until n) {
        for (x in 0 until n) {
            val mask = s.walls[y * n + x].toInt() and 0xff
            val px = offsetX + x * cell
            val py = offsetY + y * cell
            if (mask and 0b0001 != 0) { path.moveTo(px, py); path.lineTo(px + cell, py) }
            if (mask and 0b1000 != 0) { path.moveTo(px, py); path.lineTo(px, py + cell) }
            if (y == n - 1 && mask and 0b0100 != 0) {
                path.moveTo(px, py + cell); path.lineTo(px + cell, py + cell)
            }
            if (x == n - 1 && mask and 0b0010 != 0) {
                path.moveTo(px + cell, py); path.lineTo(px + cell, py + cell)
            }
        }
    }
    scope.drawPath(path, INK, style = Stroke(width = 2.4f))
}

private fun drawTrail(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    s: com.lavazombie.amazegame.GameState,
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
        // east neighbour
        if (x + 1 < n && visited.contains(idx + 1)) {
            val mask = s.walls[idx].toInt() and 0xff
            if (mask and 0b0010 == 0) {
                path.moveTo(cx, cy); path.lineTo(offsetX + (x + 1 + 0.5f) * cell, cy)
            }
        }
        // south neighbour
        if (y + 1 < n && visited.contains(idx + n)) {
            val mask = s.walls[idx].toInt() and 0xff
            if (mask and 0b0100 == 0) {
                path.moveTo(cx, cy); path.lineTo(cx, offsetY + (y + 1 + 0.5f) * cell)
            }
        }
    }
    scope.drawPath(path, TRAIL.copy(alpha = 0.55f), style = Stroke(width = 4f))
}
