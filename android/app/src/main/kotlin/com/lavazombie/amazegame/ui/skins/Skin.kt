package com.lavazombie.amazegame.ui.skins

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.lavazombie.amazegame.ui.shapes.ShapeRef

/**
 * Port of `web/src/skins/types.ts` to Compose. A skin is pure data plus two
 * pluggable draw functions (background, single wall segment). The character /
 * start / goal markers are picked from the shape library by name, with a
 * skin-specified style + size factor that the player can override.
 */
data class SkinPalette(
    val ink: Color,
    val paper: Color,
    val accent: Color,
    val character: Color,
    val start: Color,
    val goal: Color,
    val trail: Color,
)

data class TrailStyle(
    val color: Color,
    val width: Float,
    val style: Style = Style.Solid,
    val alpha: Float = 1f,
) {
    enum class Style { Solid, Dotted, Dashed }
}

interface Skin {
    val id: String
    val name: String
    val palette: SkinPalette
    val hudBackground: Color

    /** Paint the canvas background (paper + grid + margin for math-textbook). */
    fun drawBackground(scope: DrawScope, canvasSize: Size, cell: Float, offsetX: Float, offsetY: Float)

    /**
     * Draw a single wall segment. The renderer calls this once per wall, with
     * a deterministic RNG seeded from the maze so the wobble is stable across
     * frames. The RNG is exposed as a `() -> Float` returning `[0,1)`.
     */
    fun drawWall(
        scope: DrawScope,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        rand: () -> Float,
    )

    /** Default shape refs — overridable by the player at runtime. */
    val character: ShapeRef
    val start: ShapeRef
    val goal: ShapeRef
    val trail: TrailStyle
}
