package com.lavazombie.amazegame.ui.skins

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.lavazombie.amazegame.ui.shapes.ShapeRef
import com.lavazombie.amazegame.ui.shapes.ShapeStyle
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val INK = Color(0xFF1D2433)
private val INK_SOFT = Color(0xFF2A3450)
private val PAPER = Color(0xFFF7F3E8)
private val PAPER_LINE = Color(0xFFB9C7D8)
private val PAPER_MARGIN = Color(0xFFE9A3A6)
private val DOT_RED = Color(0xFFC83B3B)
private val GOAL_BLUE = Color(0xFF2F6FB8)
private val TRAIL_BLUE = Color(0xFF7896C2)

/**
 * Default skin — Romanian-style math notebook ("caiet de matematică"). Cream
 * paper, blue square grid anchored to the maze cells, red left margin, walls
 * drawn as hand-wobbled pen strokes overdrawing the grid lines.
 */
val MathTextbookSkin = object : Skin {
    override val id = "math-textbook"
    override val name = "Math textbook"
    override val palette = SkinPalette(
        ink = INK,
        paper = PAPER,
        accent = INK_SOFT,
        character = DOT_RED,
        start = INK_SOFT,
        goal = GOAL_BLUE,
        trail = TRAIL_BLUE,
    )
    override val hudBackground = Color(0xD9F7F3E8)

    override fun drawBackground(
        scope: DrawScope,
        canvasSize: Size,
        cell: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        with(scope) {
            drawRect(PAPER, Offset.Zero, canvasSize)
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
            drawLine(
                PAPER_MARGIN.copy(alpha = 0.7f),
                Offset(marginX, 0f),
                Offset(marginX, canvasSize.height),
                strokeWidth = 1.2f,
            )
        }
    }

    /**
     * Web parity: see `web/src/skins/math-textbook.ts`'s `penStroke`. Steps
     * along the segment with perpendicular jitter, endpoint overshoot, a tiny
     * variable line width, and an occasional ink pool at the start point.
     */
    override fun drawWall(
        scope: DrawScope,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        rand: () -> Float,
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len == 0f) return
        val nx = -dy / len
        val ny = dx / len
        val steps = max(6, (len / 6f).toInt())

        val overshoot = 1.5f + rand() * 2.5f
        val sx = x1 - dx / len * overshoot
        val sy = y1 - dy / len * overshoot
        val ex = x2 + dx / len * overshoot
        val ey = y2 + dy / len * overshoot

        val lineWidth = 2.2f + rand() * 0.6f

        val path = Path().apply {
            moveTo(sx, sy)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val px = sx + (ex - sx) * t
                val py = sy + (ey - sy) * t
                val wobble = (rand() - 0.5f) * 1.6f
                lineTo(px + nx * wobble, py + ny * wobble)
            }
        }
        with(scope) {
            drawPath(
                path,
                INK,
                style = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            if (rand() < 0.35f) {
                drawCircle(INK, radius = 1.5f + rand(), center = Offset(sx, sy))
            }
        }
    }

    override val character = ShapeRef(
        name = "dot",
        style = ShapeStyle(fill = DOT_RED, stroke = INK_SOFT, strokeWidth = 1.3f),
        sizeFactor = 0.44f,
    )
    override val start = ShapeRef(
        name = "ring",
        style = ShapeStyle(stroke = INK_SOFT, strokeWidth = 1.4f),
        sizeFactor = 0.36f,
    )
    override val goal = ShapeRef(
        name = "ring",
        style = ShapeStyle(stroke = GOAL_BLUE, strokeWidth = 2f, innerFill = GOAL_BLUE),
        sizeFactor = 0.56f,
    )
    override val trail = TrailStyle(
        color = TRAIL_BLUE,
        width = 4f,
        style = TrailStyle.Style.Solid,
        alpha = 0.55f,
    )
}

const val DEFAULT_SKIN_ID = "math-textbook"

private val REGISTRY: Map<String, Skin> = listOf(
    MathTextbookSkin,
    GeometryDashClassicSkin,
    GeometryDashSubZeroSkin,
    GeometryDashMeltdownSkin,
    GalaxySkin,
).associateBy { it.id }

fun listSkins(): List<Skin> = REGISTRY.values.toList()

fun getSkin(id: String): Skin = REGISTRY[id] ?: MathTextbookSkin
