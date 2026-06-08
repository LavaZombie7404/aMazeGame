package com.lavazombie.amazegame.ui.skins

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.lavazombie.amazegame.ui.shapes.ShapeRef
import com.lavazombie.amazegame.ui.shapes.ShapeStyle

/**
 * Geometry Dash-inspired skin family. Three palettes — Classic Neon, Sub
 * Zero, Meltdown — built off the same factory.
 *
 * Visual rules (port of `web/src/skins/geometry-dash.ts`, static version):
 * - Vertical gradient background.
 * - Faint glowing grid at maze-cell pitch with brighter intersection dots.
 *   The web version scrolls the grid and pulses the brightness on a 1.2 s
 *   sine wave; the native port skips both because the Skin interface
 *   doesn't thread `tMs` through. Adding the animation is a follow-up.
 * - Walls drawn as three concentric strokes (outer halo + mid glow + core
 *   line) for a neon look. No wobble — wobble is the math-textbook trait.
 */

private data class GeometryDashPalette(
    val bgTop: Color,
    val bgBottom: Color,
    val grid: Color,
    val gridGlow: Color,
    val wall: Color,
    val wallGlow: Color,
    val player: Color,
    val start: Color,
    val goal: Color,
    val trail: Color,
    val hudBg: Color,
)

private fun DrawScope.drawNeonLine(
    x1: Float, y1: Float, x2: Float, y2: Float,
    core: Color, glow: Color, baseWidth: Float,
) {
    val a = Offset(x1, y1)
    val b = Offset(x2, y2)
    drawLine(glow.copy(alpha = 0.18f), a, b, strokeWidth = baseWidth * 5f, cap = StrokeCap.Round)
    drawLine(core.copy(alpha = 0.45f), a, b, strokeWidth = baseWidth * 2.2f, cap = StrokeCap.Round)
    drawLine(core, a, b, strokeWidth = baseWidth, cap = StrokeCap.Round)
}

private fun DrawScope.drawGdBackground(
    canvasSize: Size, cell: Float, offsetX: Float, offsetY: Float,
    palette: GeometryDashPalette,
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(palette.bgTop, palette.bgBottom),
        startY = 0f,
        endY = canvasSize.height,
    )
    drawRect(gradient, Offset.Zero, canvasSize)

    val offX = ((offsetX % cell) + cell) % cell
    val offY = ((offsetY % cell) + cell) % cell

    // Faint grid lines.
    var x = offX
    while (x < canvasSize.width) {
        drawLine(
            palette.grid.copy(alpha = 0.28f),
            Offset(x, 0f),
            Offset(x, canvasSize.height),
            strokeWidth = 1f,
        )
        x += cell
    }
    var y = offY
    while (y < canvasSize.height) {
        drawLine(
            palette.grid.copy(alpha = 0.28f),
            Offset(0f, y),
            Offset(canvasSize.width, y),
            strokeWidth = 1f,
        )
        y += cell
    }
    // Intersection dots.
    var ix = offX
    while (ix < canvasSize.width) {
        var iy = offY
        while (iy < canvasSize.height) {
            drawCircle(
                palette.gridGlow.copy(alpha = 0.55f),
                radius = 1.4f,
                center = Offset(ix, iy),
            )
            iy += cell
        }
        ix += cell
    }
}

private fun makeGeometryDashSkin(
    skinId: String,
    skinName: String,
    p: GeometryDashPalette,
): Skin = object : Skin {
    override val id = skinId
    override val name = skinName
    override val palette = SkinPalette(
        ink = p.wall,
        paper = p.bgTop,
        accent = p.wall,
        character = p.player,
        start = p.start,
        goal = p.goal,
        trail = p.trail,
    )
    override val hudBackground = p.hudBg

    override fun drawBackground(
        scope: DrawScope, canvasSize: Size, cell: Float, offsetX: Float, offsetY: Float,
    ) {
        with(scope) { drawGdBackground(canvasSize, cell, offsetX, offsetY, p) }
    }

    override fun drawWall(
        scope: DrawScope,
        x1: Float, y1: Float, x2: Float, y2: Float,
        rand: () -> Float,
    ) {
        // Drain one rand() per wall to stay byte-identical with any consumer
        // that seeds randomness from the maze hash; the value is unused
        // because GD walls have no wobble.
        rand()
        with(scope) { drawNeonLine(x1, y1, x2, y2, p.wall, p.wallGlow, 2.6f) }
    }

    override val character = ShapeRef(
        name = "square",
        style = ShapeStyle(fill = p.player, stroke = p.player, strokeWidth = 1.4f),
        sizeFactor = 0.5f,
    )
    override val start = ShapeRef(
        name = "ring",
        style = ShapeStyle(stroke = p.start, strokeWidth = 2.4f, innerFill = p.start),
        sizeFactor = 0.46f,
    )
    override val goal = ShapeRef(
        name = "ring",
        style = ShapeStyle(stroke = p.goal, strokeWidth = 2.6f, innerFill = p.goal),
        sizeFactor = 0.56f,
    )
    override val trail = TrailStyle(
        color = p.trail,
        width = 4f,
        style = TrailStyle.Style.Solid,
        alpha = 0.7f,
    )
}

val GeometryDashClassicSkin = makeGeometryDashSkin(
    skinId = "geometry-dash-classic",
    skinName = "GD · Classic Neon",
    p = GeometryDashPalette(
        bgTop = Color(0xFF0A0A23),
        bgBottom = Color(0xFF1A0A3A),
        grid = Color(0xFF3B3168),
        gridGlow = Color(0xFF7C5CFF),
        wall = Color(0xFF00DDFF),
        wallGlow = Color(0xFF00DDFF),
        player = Color(0xFFFF2EB5),
        start = Color(0xFF00DDFF),
        goal = Color(0xFFFFD23F),
        trail = Color(0xFF00DDFF),
        hudBg = Color(0xD90A0A23),
    ),
)

val GeometryDashSubZeroSkin = makeGeometryDashSkin(
    skinId = "geometry-dash-sub-zero",
    skinName = "GD · Sub Zero",
    p = GeometryDashPalette(
        bgTop = Color(0xFF03102B),
        bgBottom = Color(0xFF0C2350),
        grid = Color(0xFF23457A),
        gridGlow = Color(0xFF9EE5FF),
        wall = Color(0xFF9EE5FF),
        wallGlow = Color(0xFF9EE5FF),
        player = Color(0xFF3FA9FF),
        start = Color(0xFF9EE5FF),
        goal = Color(0xFFFFFFFF),
        trail = Color(0xFF9EE5FF),
        hudBg = Color(0xE003102B),
    ),
)

val GeometryDashMeltdownSkin = makeGeometryDashSkin(
    skinId = "geometry-dash-meltdown",
    skinName = "GD · Meltdown",
    p = GeometryDashPalette(
        bgTop = Color(0xFF1A0204),
        bgBottom = Color(0xFF2E0A07),
        grid = Color(0xFF5B1A14),
        gridGlow = Color(0xFFFF8A2C),
        wall = Color(0xFFFF8A2C),
        wallGlow = Color(0xFFFF8A2C),
        player = Color(0xFFFFE156),
        start = Color(0xFFFF8A2C),
        goal = Color(0xFFFF2E2E),
        trail = Color(0xFFFF8A2C),
        hudBg = Color(0xE61A0204),
    ),
)
