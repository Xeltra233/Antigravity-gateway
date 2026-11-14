package org.antigravity.gateway.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Frosted panel for the silk skin.
 *
 * The web build gets its depth from `backdrop-filter` plus a stack of inset lines; neither is
 * available as a single Android primitive, so the same stack is rebuilt by hand: a tinted body, a
 * diagonal glass wash, one vertical highlight that fades out at 36% of the panel height, a brighter
 * top border, and the inset hairlines that give the edge its thickness.
 */
class GlassSurfaceDrawable(
    private val density: Float,
    private val radiusDp: Float,
    private val variant: Variant
) : Drawable() {

    enum class Variant { CARD, TABS, FIELD, PRIMARY, MENU }

    private val rect = RectF()
    private val path = Path()
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    // Stroked, not filled: the blur only has to survive outside the panel, and a filled shape would
    // show through the translucent glass wash.
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SOLID
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private var alphaScale = 255

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
        val radius = radiusDp * density
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        body.color = bodyTint()
        glass.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            washColors(), washStops(), Shader.TileMode.CLAMP
        )
        gloss.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.top + rect.height() * GLOSS_SPAN,
            GLOSS, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        border.color = edgeColor()
        shadow.setShadowLayer(
            SHADOW_RADIUS_DP * density, 0f, SHADOW_DY_DP * density,
            if (variant == Variant.PRIMARY) SHADOW_BLUE else SHADOW
        )

    }

    override fun draw(canvas: Canvas) {
        val radius = radiusDp * density

        if (variant == Variant.CARD || variant == Variant.MENU || variant == Variant.PRIMARY) {
            canvas.drawRoundRect(rect, radius, radius, shadow)
        }
        if (ColorTint.isVisible(body.color)) {
            canvas.drawRoundRect(rect, radius, radius, body)
        }
        canvas.drawRoundRect(rect, radius, radius, glass)
        if (variant == Variant.CARD) {
            canvas.drawRoundRect(rect, radius, radius, gloss)
        }
        canvas.drawPath(path, border)
        drawInsets(canvas, radius)
    }

    /** The inset hairlines: bright on top, a hair of shade at the bottom, faint on the left. */
    private fun drawInsets(canvas: Canvas, radius: Float) {
        val top = insetTop()
        if (top != 0) {
            hairline.color = top
            canvas.drawLine(rect.left + radius, rect.top + density, rect.right - radius, rect.top + density, hairline)
        }
        val bottom = insetBottom()
        if (bottom != 0) {
            hairline.color = bottom
            canvas.drawLine(
                rect.left + radius, rect.bottom - density,
                rect.right - radius, rect.bottom - density, hairline
            )
        }
        val side = insetSide()
        if (side != 0) {
            hairline.color = side
            canvas.drawLine(rect.left + density, rect.top + radius, rect.left + density, rect.bottom - radius, hairline)
        }
    }

    /**
     * Tinted body. The web build derives this from a saturated backdrop blur; that filter has no
     * Android equivalent per-panel, so the tint is carried directly and only the wash on top is
     * translucent. Tuning it towards a fixed blue keeps the panel readable over both the lit and the
     * shaded stretches of the ribbon.
     */
    private fun bodyTint(): Int = when (variant) {
        Variant.CARD -> 0xB22E4488.toInt()
        Variant.TABS -> 0x9E161E34.toInt()
        Variant.FIELD -> 0xAA4E7ECC.toInt()
        Variant.PRIMARY -> 0xD196B4FF.toInt()
        Variant.MENU -> 0xE61A243E.toInt()
    }

    private fun washColors(): IntArray = when (variant) {
        Variant.CARD -> intArrayOf(0x12FFFFFF, 0x07FFFFFF, 0x0E96AFE1)
        Variant.TABS -> intArrayOf(0x9E161E34.toInt(), 0xBE060A14.toInt(), 0xC7060A14.toInt())
        Variant.FIELD -> intArrayOf(0x13FFFFFF, 0x0AFFFFFF, 0x05FFFFFF)
        Variant.PRIMARY -> intArrayOf(0x33FFFFFF, 0x14FFFFFF, 0x0AFFFFFF)
        Variant.MENU -> intArrayOf(0xE61A243E.toInt(), 0xEE0E1626.toInt(), 0xF0080C18.toInt())
    }

    private fun washStops(): FloatArray = when (variant) {
        Variant.CARD -> floatArrayOf(0f, 0.44f, 1f)
        else -> floatArrayOf(0f, 0.55f, 1f)
    }

    private fun edgeColor(): Int = when (variant) {
        Variant.CARD -> 0x2BFFFFFF
        Variant.TABS -> 0x26FFFFFF
        Variant.FIELD -> 0x27FFFFFF
        Variant.PRIMARY -> 0x70AAC8FF
        Variant.MENU -> 0x2BFFFFFF
    }

    private fun insetTop(): Int = when (variant) {
        Variant.CARD -> 0x4DFFFFFF
        Variant.TABS -> 0x21FFFFFF
        Variant.FIELD -> 0x2BFFFFFF
        Variant.PRIMARY -> 0x6BFFFFFF
        Variant.MENU -> 0x2BFFFFFF
    }

    private fun insetBottom(): Int = when (variant) {
        Variant.CARD -> 0x0BFFFFFF
        Variant.TABS -> 0x00000000
        Variant.FIELD -> 0x33000000
        Variant.PRIMARY -> 0x29000000
        Variant.MENU -> 0x1F000000
    }

    private fun insetSide(): Int = when (variant) {
        Variant.CARD -> 0x0EFFFFFF
        Variant.TABS -> 0x00000000
        Variant.FIELD -> 0x00000000
        Variant.PRIMARY -> 0x00000000
        Variant.MENU -> 0x00000000
    }

    override fun setAlpha(alpha: Int) {
        alphaScale = alpha.coerceIn(0, 255)
        body.alpha = alphaScale
        glass.alpha = alphaScale
        gloss.alpha = alphaScale
        border.alpha = alphaScale
        hairline.alpha = alphaScale
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        body.colorFilter = colorFilter
        glass.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable, still required by the platform signature.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private object ColorTint {
        fun isVisible(color: Int): Boolean = (color ushr 24) and 0xFF != 0
    }

    private companion object {
        const val GLOSS_SPAN = 0.36f
        const val GLOSS = 0x22FFFFFF

        const val SHADOW_RADIUS_DP = 18f
        const val SHADOW_DY_DP = 7f
        const val SHADOW = 0x7F000000
        const val SHADOW_BLUE = 0x4D3264EB

        /** Opaque stand-in for the panel body: only its shadow survives under the real fill. */
        const val SOLID = 0xFF0A1220.toInt()
    }
}
