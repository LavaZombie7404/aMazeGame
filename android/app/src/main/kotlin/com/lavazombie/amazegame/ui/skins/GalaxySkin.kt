package com.lavazombie.amazegame.ui.skins

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.lavazombie.amazegame.ui.shapes.ShapeRef
import com.lavazombie.amazegame.ui.shapes.ShapeStyle
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Galaxy skin — the Milky Way as seen from Earth, edge-on. Static port of
 * `web/src/skins/galaxy.ts` (no star twinkle yet because the native Skin
 * interface doesn't thread `tMs` through). Deterministic star field cached
 * by canvas-size signature so we don't regenerate ~500 stars per frame.
 */

private val BG_TOP = Color(0xFF040716)
private val BG_BOTTOM = Color(0xFF01020A)
private val WALL = Color(0xFF9ED9FF)
private val PLAYER = Color(0xFFFFE066)
private val START_PORTAL = Color(0xFF5CC8FF)
private val GOAL_STAR = Color(0xFFFFD23F)
private val TRAIL = Color(0xFF5CC8FF)
private val NEBULA_CORE = Color(0x38FFE9B0)
private val NEBULA_MID = Color(0x1AB482DC)
private val NEBULA_EDGE = Color(0x005A3CC8)
private val STAR_BRIGHT = Color(0xFFFFF5D0)
private val GALAXY_ANGLE = -0.45f // ~26° upward

private class Star(val x: Float, val y: Float, val r: Float, val alpha: Float)

private class StarField(
    val signature: String,
    val stars: List<Star>,
    val bright: List<Star>,
)

private fun mulberry32(seed: Int): () -> Float {
    var s = seed
    return {
        s += 0x6D2B79F5.toInt()
        var t = s
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
        val u = (t xor (t ushr 14)).toLong() and 0xFFFFFFFFL
        u.toFloat() / 4_294_967_296f
    }
}

private fun buildStarField(width: Float, height: Float): StarField {
    val rng = mulberry32(0x0C0DECAF.toInt())
    val cx = width / 2f
    val cy = height / 2f
    val cosA = cos(GALAXY_ANGLE)
    val sinA = sin(GALAXY_ANGLE)
    val longAxis = max(width, height) * 0.85f
    val shortAxis = height * 0.28f

    val starCount = max(120, ((width * height) / 1400f).toInt())
    val stars = ArrayList<Star>(starCount)
    for (i in 0 until starCount) {
        val x: Float
        val y: Float
        if (rng() < 0.55f) {
            val t = (rng() - 0.5f) * 1.2f
            val off = (rng() - 0.5f) * shortAxis * 1.8f
            x = cx + t * longAxis * cosA - off * sinA
            y = cy + t * longAxis * sinA + off * cosA
        } else {
            x = rng() * width
            y = rng() * height
        }
        val r = rng() * 0.9f + 0.25f
        val a = rng() * 0.65f + 0.35f
        stars.add(Star(x, y, r, a))
    }

    val bright = ArrayList<Star>(10)
    for (i in 0 until 10) {
        val t = (rng() - 0.5f) * 0.85f
        val off = (rng() - 0.5f) * shortAxis * 0.45f
        val x = cx + t * longAxis * cosA - off * sinA
        val y = cy + t * longAxis * sinA + off * cosA
        val r = 1.4f + rng() * 1.4f
        bright.add(Star(x, y, r, 1f))
    }

    return StarField("${width.toInt()}x${height.toInt()}", stars, bright)
}

val GalaxySkin = object : Skin {
    override val id = "galaxy"
    override val name = "Galaxy"
    override val palette = SkinPalette(
        ink = WALL,
        paper = BG_TOP,
        accent = PLAYER,
        character = PLAYER,
        start = START_PORTAL,
        goal = GOAL_STAR,
        trail = TRAIL,
    )
    override val hudBackground = Color(0xE0040716)

    private var cached: StarField? = null

    override fun drawBackground(
        scope: DrawScope, canvasSize: Size, cell: Float, offsetX: Float, offsetY: Float,
    ) {
        with(scope) {
            // Vertical deep-space gradient.
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(BG_TOP, BG_BOTTOM),
                    startY = 0f,
                    endY = canvasSize.height,
                ),
                Offset.Zero,
                canvasSize,
            )

            // Milky-Way band — rotated elliptical radial gradient.
            val cx = canvasSize.width / 2f
            val cy = canvasSize.height / 2f
            val longAxis = max(canvasSize.width, canvasSize.height) * 0.85f
            val shortAxis = canvasSize.height * 0.28f
            translate(cx, cy) {
                rotate(degrees = GALAXY_ANGLE * 180f / Math.PI.toFloat(), pivot = Offset.Zero) {
                    scale(longAxis / shortAxis, 1f, pivot = Offset.Zero) {
                        drawCircle(
                            Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to NEBULA_CORE,
                                    0.4f to NEBULA_MID,
                                    1f to NEBULA_EDGE,
                                ),
                                center = Offset.Zero,
                                radius = shortAxis,
                            ),
                            radius = shortAxis,
                            center = Offset.Zero,
                        )
                    }
                }
            }

            // Stars (cached).
            val sig = "${canvasSize.width.toInt()}x${canvasSize.height.toInt()}"
            val field = cached?.takeIf { it.signature == sig }
                ?: buildStarField(canvasSize.width, canvasSize.height).also { cached = it }
            for (s in field.stars) {
                if (s.x < 0f || s.x > canvasSize.width || s.y < 0f || s.y > canvasSize.height) continue
                drawCircle(
                    Color.White.copy(alpha = s.alpha),
                    radius = s.r,
                    center = Offset(s.x, s.y),
                )
            }
            for (b in field.bright) {
                if (b.x < 0f || b.x > canvasSize.width || b.y < 0f || b.y > canvasSize.height) continue
                drawCircle(STAR_BRIGHT, radius = b.r, center = Offset(b.x, b.y))
            }
        }
    }

    override fun drawWall(
        scope: DrawScope,
        x1: Float, y1: Float, x2: Float, y2: Float,
        rand: () -> Float,
    ) {
        // Drain one rand() for parity with the maze RNG-seeded path.
        rand()
        with(scope) {
            val a = Offset(x1, y1)
            val b = Offset(x2, y2)
            drawLine(WALL.copy(alpha = 0.15f), a, b, strokeWidth = 8f, cap = StrokeCap.Round)
            drawLine(WALL.copy(alpha = 0.5f), a, b, strokeWidth = 3.6f, cap = StrokeCap.Round)
            drawLine(WALL, a, b, strokeWidth = 2f, cap = StrokeCap.Round)
        }
    }

    override val character = ShapeRef(
        name = "dot",
        style = ShapeStyle(fill = PLAYER, stroke = PLAYER, strokeWidth = 1.6f),
        sizeFactor = 0.46f,
    )
    override val start = ShapeRef(
        name = "ring",
        style = ShapeStyle(stroke = START_PORTAL, strokeWidth = 2.4f, innerFill = START_PORTAL),
        sizeFactor = 0.44f,
    )
    override val goal = ShapeRef(
        name = "star-5",
        style = ShapeStyle(fill = GOAL_STAR, stroke = GOAL_STAR, strokeWidth = 1.4f),
        sizeFactor = 0.6f,
    )
    override val trail = TrailStyle(
        color = TRAIL,
        width = 4f,
        style = TrailStyle.Style.Solid,
        alpha = 0.55f,
    )
}
