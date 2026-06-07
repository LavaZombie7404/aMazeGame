package com.lavazombie.amazegame.ui.shapes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Port of `web/src/shapes/types.ts` + `web/src/shapes/primitives.ts` to
 * Compose. Each shape draws itself centred on `center` within a `size`×`size`
 * bounding box. The skin provides the colors via [ShapeStyle].
 */
data class ShapeStyle(
    val fill: Color? = null,
    val stroke: Color? = null,
    val strokeWidth: Float = 1.3f,
    /** Inner-fill on top of the stroked outline. Used by `ring`. */
    val innerFill: Color? = null,
)

/** Reference from a skin (or a player override) into the shape registry. */
data class ShapeRef(
    val name: String,
    val style: ShapeStyle = ShapeStyle(),
    /** Fraction of the cell size to use as the drawing bounding box. */
    val sizeFactor: Float = 0.45f,
)

interface Shape {
    val name: String
    fun draw(scope: DrawScope, center: Offset, size: Float, style: ShapeStyle)
}

private fun DrawScope.fillIf(style: ShapeStyle, path: Path) {
    if (style.fill != null) drawPath(path, style.fill)
}

private fun DrawScope.strokeIf(style: ShapeStyle, path: Path) {
    if (style.stroke != null) {
        drawPath(
            path,
            style.stroke,
            style = Stroke(
                width = style.strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

object DotShape : Shape {
    override val name = "dot"
    override fun draw(scope: DrawScope, center: Offset, size: Float, style: ShapeStyle) {
        with(scope) {
            val r = size / 2f
            if (style.fill != null) drawCircle(style.fill, r, center)
            if (style.stroke != null) {
                drawCircle(style.stroke, r, center, style = Stroke(width = style.strokeWidth))
            }
        }
    }
}

object RingShape : Shape {
    override val name = "ring"
    override fun draw(scope: DrawScope, center: Offset, size: Float, style: ShapeStyle) {
        with(scope) {
            val r = size / 2f
            if (style.stroke != null) {
                drawCircle(style.stroke, r, center, style = Stroke(width = style.strokeWidth))
            }
            if (style.innerFill != null) {
                drawCircle(style.innerFill.copy(alpha = 0.6f), r * 0.55f, center)
            }
        }
    }
}

object SquareShape : Shape {
    override val name = "square"
    override fun draw(scope: DrawScope, center: Offset, size: Float, style: ShapeStyle) {
        with(scope) {
            val topLeft = Offset(center.x - size / 2f, center.y - size / 2f)
            val s = Size(size, size)
            if (style.fill != null) drawRect(style.fill, topLeft, s)
            if (style.stroke != null) {
                drawRect(style.stroke, topLeft, s, style = Stroke(width = style.strokeWidth))
            }
        }
    }
}

private fun polygonPath(center: Offset, vertices: List<Offset>): Path {
    val p = Path()
    vertices.forEachIndexed { i, v ->
        val x = center.x + v.x
        val y = center.y + v.y
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

object DiamondShape : Shape {
    override val name = "diamond"
    override fun draw(scope: DrawScope, center: Offset, size: Float, style: ShapeStyle) {
        val h = size / 2f
        val p = polygonPath(
            center,
            listOf(Offset(0f, -h), Offset(h, 0f), Offset(0f, h), Offset(-h, 0f)),
        )
        with(scope) {
            fillIf(style, p)
            strokeIf(style, p)
        }
    }
}

object TriangleShape : Shape {
    override val name = "triangle"
    override fun draw(scope: DrawScope, center: Offset, size: Float, style: ShapeStyle) {
        val h = size / 2f
        val p = polygonPath(
            center,
            listOf(
                Offset(0f, -h),
                Offset(h * 0.95f, h * 0.75f),
                Offset(-h * 0.95f, h * 0.75f),
            ),
        )
        with(scope) {
            fillIf(style, p)
            strokeIf(style, p)
        }
    }
}

object Star5Shape : Shape {
    override val name = "star-5"
    override fun draw(scope: DrawScope, center: Offset, size: Float, style: ShapeStyle) {
        val outer = size / 2f
        val inner = outer * 0.45f
        val verts = (0 until 10).map { i ->
            val r = if (i % 2 == 0) outer else inner
            val a = i * PI / 5 - PI / 2
            Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat())
        }
        val p = polygonPath(center, verts)
        with(scope) {
            fillIf(style, p)
            strokeIf(style, p)
        }
    }
}

object FlagShape : Shape {
    override val name = "flag"
    override fun draw(scope: DrawScope, center: Offset, size: Float, style: ShapeStyle) {
        val h = size / 2f
        with(scope) {
            // Pole
            if (style.stroke != null) {
                drawLine(
                    style.stroke,
                    Offset(center.x - h * 0.45f, center.y - h),
                    Offset(center.x - h * 0.45f, center.y + h),
                    strokeWidth = style.strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
        val flag = polygonPath(
            center,
            listOf(
                Offset(-h * 0.45f, -h),
                Offset(h * 0.7f, -h * 0.45f),
                Offset(-h * 0.45f, h * 0.1f),
            ),
        )
        with(scope) {
            fillIf(style, flag)
            strokeIf(style, flag)
        }
    }
}

object ArrowShape : Shape {
    override val name = "arrow"
    override fun draw(scope: DrawScope, center: Offset, size: Float, style: ShapeStyle) {
        val h = size / 2f
        val p = polygonPath(
            center,
            listOf(
                Offset(-h, -h * 0.45f),
                Offset(h * 0.25f, -h * 0.45f),
                Offset(h * 0.25f, -h),
                Offset(h, 0f),
                Offset(h * 0.25f, h),
                Offset(h * 0.25f, h * 0.45f),
                Offset(-h, h * 0.45f),
            ),
        )
        with(scope) {
            fillIf(style, p)
            strokeIf(style, p)
        }
    }
}

private val REGISTRY: Map<String, Shape> = listOf(
    DotShape, RingShape, SquareShape, DiamondShape,
    TriangleShape, Star5Shape, FlagShape, ArrowShape,
).associateBy { it.name }

fun listShapeNames(): List<String> = REGISTRY.keys.sorted()

fun getShape(name: String): Shape = REGISTRY[name] ?: DotShape

fun DrawScope.drawShape(ref: ShapeRef, center: Offset, cellSize: Float) {
    val size = cellSize * ref.sizeFactor
    getShape(ref.name).draw(this, center, size, ref.style)
}
