package com.lavazombie.amazegame.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
 *
 * A camera (scale + offset) sits on top so big mazes — the 50×50 daily — load
 * zoomed in on the start and can be pinch-zoomed / two-finger panned. One-cell
 * mazes that fit the screen behave exactly as before (scale 1, centred).
 */
private const val MAX_CELLS_ON_SCREEN = 17
private const val MAX_SCALE = 4f

@Composable
fun MazeCanvas(game: GameRuntime, modifier: Modifier = Modifier) {
    val state by game.state.collectAsStateWithLifecycle()
    val player by game.player.collectAsStateWithLifecycle()
    val skin = player.skin
    val overrides = player.overrides

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var camScale by remember { mutableFloatStateOf(1f) }
    var camOffX by remember { mutableFloatStateOf(0f) }
    var camOffY by remember { mutableFloatStateOf(0f) }

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

    // Reset the camera whenever a new maze loads (hash changes) or the canvas
    // is first measured: scale 1, centred on the start cell.
    LaunchedEffect(state.hashHex, boxSize) {
        if (boxSize.width > 0 && boxSize.height > 0 && state.mazeSize > 0) {
            val cam = defaultCamera(
                boxSize.width.toFloat(), boxSize.height.toFloat(),
                state.mazeSize, state.startX, state.startY,
            )
            camScale = cam[0]; camOffX = cam[1]; camOffY = cam[2]
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(skin.palette.paper)
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastDir: Direction? = null
                    var anchor: Offset = down.position
                    var twoFinger = false
                    var prevCentroid = Offset.Zero
                    var prevSpread = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val w = boxSize.width.toFloat()
                        val h = boxSize.height.toFloat()
                        val size = game.state.value.mazeSize
                        if (pressed.size >= 2) {
                            twoFinger = true
                            var sx = 0f; var sy = 0f
                            for (c in pressed) { sx += c.position.x; sy += c.position.y }
                            val centroid = Offset(sx / pressed.size, sy / pressed.size)
                            var spread = 0f
                            for (c in pressed) spread += (c.position - centroid).getDistance()
                            spread /= pressed.size
                            if (prevSpread > 0f && size > 0) {
                                val factor = if (spread > 0f) spread / prevSpread else 1f
                                val z = zoomCamera(
                                    camScale, camOffX, camOffY, w, h, size,
                                    factor, centroid.x, centroid.y,
                                )
                                val p = clampCamera(
                                    z[0],
                                    z[1] + (centroid.x - prevCentroid.x),
                                    z[2] + (centroid.y - prevCentroid.y),
                                    w, h, size,
                                )
                                camScale = p[0]; camOffX = p[1]; camOffY = p[2]
                            }
                            prevCentroid = centroid
                            prevSpread = spread
                            for (c in pressed) c.consume()
                        } else {
                            // Single finger: steer (unless a pinch is in progress).
                            if (!twoFinger) {
                                val pos = pressed[0].position
                                val dx = pos.x - anchor.x
                                val dy = pos.y - anchor.y
                                if (dx * dx + dy * dy >= 24f * 24f) {
                                    val dir = if (abs(dx) > abs(dy)) {
                                        if (dx > 0) Direction.E else Direction.W
                                    } else {
                                        if (dy > 0) Direction.S else Direction.N
                                    }
                                    if (dir != lastDir) {
                                        game.queue(dir)
                                        lastDir = dir
                                        anchor = pos
                                    }
                                }
                            }
                            prevSpread = 0f
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = state
            if (s.mazeSize == 0) return@Canvas
            val base = max(8f, min(size.width, size.height) / MAX_CELLS_ON_SCREEN)
            val cell = base * camScale
            val offsetX = camOffX
            val offsetY = camOffY

            skin.drawBackground(this, size, cell, offsetX, offsetY)
            drawTrail(s, cell, offsetX, offsetY, skin.trail)

            // Only the cells on screen (plus a one-cell margin) are drawn, so a
            // 50×50 costs the same per frame as a 17×17 at the same zoom.
            val c0 = max(0, (-offsetX / cell).toInt() - 1)
            val c1 = min(s.mazeSize - 1, ((size.width - offsetX) / cell).toInt() + 1)
            val r0 = max(0, (-offsetY / cell).toInt() - 1)
            val r1 = min(s.mazeSize - 1, ((size.height - offsetY) / cell).toInt() + 1)

            for (y in r0..r1) {
                for (x in c0..c1) {
                    val idx = y * s.mazeSize + x
                    val mask = s.walls[idx].toInt() and 0xff
                    val px = offsetX + x * cell
                    val py = offsetY + y * cell
                    // Seed wobble per cell so it stays stable while panning
                    // (a single running RNG would shimmer as the visible set
                    // of cells changes frame to frame).
                    val rng = Mulberry32(s.hashHex.hashCode() xor (idx * 0x9e3779b9.toInt()))
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
            for (y in r0..r1) {
                for (x in c0..c1) {
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

/** Notebook-square size at scale 1: min(w,h)/17, independent of maze N. */
private fun baseCell(w: Float, h: Float): Float =
    max(8f, min(w, h) / MAX_CELLS_ON_SCREEN)

/** Smallest scale allowed: the one at which the whole maze just fits (≤ 1). */
private fun minScaleFor(size: Int): Float =
    min(1f, MAX_CELLS_ON_SCREEN.toFloat() / size)

/** Clamp zoom to [fit, MAX_SCALE] and keep the board from leaving the viewport. */
private fun clampCamera(
    scale: Float, offX: Float, offY: Float, w: Float, h: Float, size: Int,
): FloatArray {
    val s = scale.coerceIn(minScaleFor(size), MAX_SCALE)
    val board = baseCell(w, h) * s * size
    val ox = if (board <= w) (w - board) / 2f else offX.coerceIn(w - board, 0f)
    val oy = if (board <= h) (h - board) / 2f else offY.coerceIn(h - board, 0f)
    return floatArrayOf(s, ox, oy)
}

/** Fresh-maze camera: scale 1, centred on the start cell, then clamped. */
private fun defaultCamera(
    w: Float, h: Float, size: Int, startX: Int, startY: Int,
): FloatArray {
    val cell = baseCell(w, h)
    return clampCamera(
        1f,
        w / 2f - (startX + 0.5f) * cell,
        h / 2f - (startY + 0.5f) * cell,
        w, h, size,
    )
}

/** Zoom by `factor`, keeping the world point under (cx, cy) fixed on screen. */
private fun zoomCamera(
    scale: Float, offX: Float, offY: Float, w: Float, h: Float, size: Int,
    factor: Float, cx: Float, cy: Float,
): FloatArray {
    val base = baseCell(w, h)
    val oldCell = base * scale
    val wx = (cx - offX) / oldCell
    val wy = (cy - offY) / oldCell
    val ns = (scale * factor).coerceIn(minScaleFor(size), MAX_SCALE)
    val nCell = base * ns
    return clampCamera(ns, cx - wx * nCell, cy - wy * nCell, w, h, size)
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
