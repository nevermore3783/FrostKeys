// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import helium314.keyboard.latin.LiquidGlass.Param
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws the liquid glass edge treatment over the keyboard frame.
 *
 * The layers are painted from the inside out, the way the light would reach the eye through a
 * real slab: the tint sits deepest, then the band where the edge of the slab bends the content
 * behind it, then the dark line where the slab meets what is under it, and finally the specular
 * highlight riding on the very rim. A broad sheen across the face is available on top of that.
 *
 * The highlight follows Apple's rim light model: its brightness at any point of the outline comes
 * from the angle between the surface normal there and one fixed light direction, so the shine
 * peaks where the glass faces the light and falls off around the curve. Approximating the normal
 * by the direction from the middle of the keyboard outward is what makes the shine travel along
 * the straight top edge as well, rather than only lighting up the corners.
 */
class LiquidGlassOverlay {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)

    private var cachedShader: SweepGradient? = null
    private var cachedKey: String? = null

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        cornerRadiusPx: Float,
        density: Float,
        profile: LiquidGlass.Profile
    ) {
        if (width <= 0 || height <= 0) return

        val rimWidth = max(1f, profile[Param.RIM_THICKNESS] / 10f * density)
        val tintAlpha = profile.fraction(Param.TINT_OPACITY)
        val lens = profile.fraction(Param.LENS_INTENSITY)
        val shadow = profile.fraction(Param.EDGE_SHADOW)
        val rim = profile.fraction(Param.RIM_INTENSITY)
        val sheen = profile.fraction(Param.SHEEN_INTENSITY)
        if (tintAlpha <= 0f && lens <= 0f && shadow <= 0f && rim <= 0f && sheen <= 0f) return

        val saveCount = canvas.save()
        buildPath(width, height, cornerRadiusPx, 0f)
        canvas.clipPath(path)

        if (tintAlpha > 0f) drawTint(canvas, width, height, profile, tintAlpha)
        if (sheen > 0f) drawSheen(canvas, width, height, profile, sheen)
        if (lens > 0f) drawLensBand(canvas, width, height, cornerRadiusPx, density, profile, lens, rimWidth)
        if (shadow > 0f) drawEdgeShadow(canvas, width, height, cornerRadiusPx, profile, shadow, rimWidth)
        if (rim > 0f) drawRim(canvas, width, height, cornerRadiusPx, profile, rim, rimWidth)

        canvas.restoreToCount(saveCount)
    }

    /**
     * Rounds the top corners like the keyboard frame does. Insetting shrinks the radius by the
     * same amount, which keeps every layer concentric with the frame instead of letting the inner
     * curves drift towards a circle.
     */
    private fun buildPath(width: Int, height: Int, cornerRadiusPx: Float, inset: Float) {
        val radius = max(0f, cornerRadiusPx - inset)
        rect.set(inset, inset, width - inset, height + radius)
        radii[0] = radius; radii[1] = radius   // top left
        radii[2] = radius; radii[3] = radius   // top right
        radii[4] = 0f; radii[5] = 0f           // bottom right
        radii[6] = 0f; radii[7] = 0f           // bottom left
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
    }

    private fun resetPaint(profile: LiquidGlass.Profile, additive: Boolean) {
        paint.reset()
        paint.isAntiAlias = true
        paint.shader = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.blendMode = if (!additive) BlendMode.SRC_OVER else when (profile[Param.BLEND_MODE]) {
                LiquidGlass.BLEND_PLUS -> BlendMode.PLUS
                LiquidGlass.BLEND_SCREEN -> BlendMode.SCREEN
                else -> BlendMode.SRC_OVER
            }
        }
    }

    private fun drawTint(canvas: Canvas, width: Int, height: Int, profile: LiquidGlass.Profile, alpha: Float) {
        resetPaint(profile, false)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(profile[Param.TINT_COLOR], alpha * 0.5f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /**
     * A broad band of light across the face of the slab, the reflection of whatever is lighting
     * the room rather than of the edge itself.
     */
    private fun drawSheen(canvas: Canvas, width: Int, height: Int, profile: LiquidGlass.Profile, intensity: Float) {
        val bandHeight = height * profile.fraction(Param.SHEEN_HEIGHT)
        if (bandHeight <= 0f) return
        val angle = Math.toRadians(profile[Param.SHEEN_ANGLE].toDouble())
        val dx = sin(angle).toFloat() * width * 0.5f
        val color = withAlpha(profile[Param.RIM_COLOR], intensity * 0.55f)
        resetPaint(profile, true)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            width * 0.5f - dx, 0f, width * 0.5f + dx, bandHeight,
            intArrayOf(color, withAlpha(profile[Param.RIM_COLOR], intensity * 0.18f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), bandHeight, paint)
        paint.shader = null
    }

    /**
     * The band just inside the outline where a real slab compresses and brightens what is behind
     * it. Drawn as a wide, soft stroke hugging the edge, which is what lensing looks like once
     * the motion is taken out of it.
     */
    private fun drawLensBand(
        canvas: Canvas, width: Int, height: Int, cornerRadiusPx: Float, density: Float,
        profile: LiquidGlass.Profile, intensity: Float, rimWidth: Float
    ) {
        val depth = max(1f, profile.fraction(Param.LENS_DEPTH) * 24f * density)
        val inset = rimWidth + depth * 0.5f
        buildPath(width, height, cornerRadiusPx, inset)
        resetPaint(profile, true)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = depth
        // three passes of decreasing strength stand in for a blur, which a hardware canvas
        // cannot give us cheaply
        var alpha = intensity * 0.30f
        var w = depth
        repeat(3) {
            paint.strokeWidth = w
            paint.color = withAlpha(profile[Param.RIM_COLOR], alpha)
            canvas.drawPath(path, paint)
            alpha *= 0.55f
            w *= 0.55f
        }
    }

    /** The contact line, where the slab sits on top of what is behind it. */
    private fun drawEdgeShadow(
        canvas: Canvas, width: Int, height: Int, cornerRadiusPx: Float,
        profile: LiquidGlass.Profile, intensity: Float, rimWidth: Float
    ) {
        val inset = rimWidth * 1.5f
        buildPath(width, height, cornerRadiusPx, inset)
        resetPaint(profile, false)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rimWidth
        paint.color = withAlpha(Color.BLACK, intensity * 0.5f)
        canvas.drawPath(path, paint)
    }

    /** The specular highlight riding on the rim itself. */
    private fun drawRim(
        canvas: Canvas, width: Int, height: Int, cornerRadiusPx: Float,
        profile: LiquidGlass.Profile, intensity: Float, rimWidth: Float
    ) {
        buildPath(width, height, cornerRadiusPx, rimWidth * 0.5f)
        val shader = rimShader(width, height, profile)
        resetPaint(profile, true)
        paint.style = Paint.Style.STROKE
        paint.shader = shader

        val softness = profile.fraction(Param.RIM_SOFTNESS)
        // the core stroke plus two wider, weaker ones, so raising softness spreads the shine out
        // into a glow instead of leaving a hard line
        val passes = if (softness <= 0f) 1 else 3
        var alpha = intensity
        var w = rimWidth
        repeat(passes) { index ->
            paint.strokeWidth = w
            paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
            if (index == 0) {
                alpha *= 0.45f * softness
                w = rimWidth * (1f + softness * 3f)
            } else {
                alpha *= 0.5f
                w *= 1.9f
            }
        }
        paint.shader = null
    }

    private fun rimShader(width: Int, height: Int, profile: LiquidGlass.Profile): SweepGradient {
        val key = "$width:$height:${profile[Param.LIGHT_ANGLE]}:${profile[Param.RIM_SPREAD]}:" +
                "${profile[Param.COUNTER_RIM]}:${profile[Param.SIDE_FALLOFF]}:${profile[Param.RIM_COLOR]}"
        cachedShader?.let { if (key == cachedKey) return it }

        // 0 degrees means the light stands straight above the keyboard, and screen coordinates
        // put "up" at -90 degrees
        val lightAngle = Math.toRadians(-90.0 + profile[Param.LIGHT_ANGLE])
        // a tight spread concentrates the shine into a glint, a wide one wraps it around the frame
        val exponent = 16f - 14.6f * profile.fraction(Param.RIM_SPREAD)
        val counter = profile.fraction(Param.COUNTER_RIM)
        val falloff = profile.fraction(Param.SIDE_FALLOFF)
        val color = profile[Param.RIM_COLOR]

        val steps = 64
        val colors = IntArray(steps + 1)
        val positions = FloatArray(steps + 1)
        for (i in 0..steps) {
            val fraction = i.toFloat() / steps
            // SweepGradient starts at 3 o'clock and runs clockwise, which is the same direction
            // atan2 grows in on a y-down canvas
            val angle = fraction * 2.0 * Math.PI
            val delta = angleDifference(angle, lightAngle)
            val facing = max(0.0, cos(delta)).pow(exponent.toDouble()).toFloat()
            val behind = max(0.0, cos(delta - Math.PI)).pow((exponent * 1.6f).toDouble()).toFloat()
            // the keyboard is cut off at the bottom of the screen, so the shine is allowed to
            // fade out on the way down the sides
            val downward = ((sin(angle) + 1.0) / 2.0).toFloat()
            val vertical = 1f - (1f - falloff) * downward
            val strength = min(1f, (facing + behind * counter) * vertical)
            colors[i] = withAlpha(color, strength)
            positions[i] = fraction
        }
        val shader = SweepGradient(width * 0.5f, height * 0.5f, colors, positions)
        cachedShader = shader
        cachedKey = key
        return shader
    }

    private fun angleDifference(a: Double, b: Double): Double {
        var d = a - b
        while (d > Math.PI) d -= 2.0 * Math.PI
        while (d < -Math.PI) d += 2.0 * Math.PI
        return abs(d)
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
