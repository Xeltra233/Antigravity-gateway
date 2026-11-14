package org.antigravity.gateway.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The paper the sketch skin is drawn on.
 *
 * Baked once per size into a bitmap covering the whole view — never tiled. A repeating tile only
 * works when every seam lines up, and any residue of mismatch shows up as a regular lattice of faint
 * lines across the page; the reference page gets away with a 260px tile because it packs ninety soft
 * splashes and a hundred and forty fibres into it, which hides the repeat. Baking the full page
 * removes the question entirely.
 *
 * Built in three layers, matching the reference: soft mottling for the pulp, per-pixel speckle whose
 * green and blue channels move less than red (so the noise reads warm rather than grey), then short
 * bowing fibres on top, and finally a wide corner vignette.
 */
class PaperSurfaceDrawable(
    private val density: Float,
    private val fillColor: Int = SketchSpec.PAPER
) : Drawable() {

    private val fillPaint = Paint().apply { color = fillColor }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var grain: Bitmap? = null
    private var builtFor = 0

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.isEmpty) return
        val key = bounds.width() * 7919 + bounds.height()
        if (key == builtFor) return
        builtFor = key
        grain?.recycle()
        grain = buildPaper(bounds.width(), bounds.height(), key.toLong())
    }

    private fun buildPaper(width: Int, height: Int, seed: Long): Bitmap {
        val w = max(1, width)
        val h = max(1, height)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val generator = SketchSpec.Lcg(seed)

        canvas.drawColor(fillColor)

        // 1) Soft pulp mottling. Radial gradients, roughly one per 750 square dp, alternating warm
        //    dark and warm light, which is what keeps the page from looking like a flat swatch.
        val area = (w / density) * (h / density)
        val splashes = (area / 900f).toInt().coerceIn(24, 800)
        val mottle = Paint(Paint.ANTI_ALIAS_FLAG)
        for (index in 0 until splashes) {
            val cx = generator.range(0f, w.toFloat())
            val cy = generator.range(0f, h.toFloat())
            val radius = generator.range(12f, 42f) * density
            val dark = generator.next() > 0.45f
            mottle.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(if (dark) SketchSpec.PAPER_MOTTLE_DARK else SketchSpec.PAPER_MOTTLE_LIGHT, 0x00F6EFE2),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, mottle)
        }
        mottle.shader = null

        // 2) Per-pixel speckle. Red moves furthest and blue least, so the grain sits warm on the page
        //    instead of cooling it toward grey.
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val shift = (generator.next() - 0.5f) * SPECKLE
            val r = clamp(((pixel shr 16) and 0xFF) + shift)
            val g = clamp(((pixel shr 8) and 0xFF) + shift * 0.95f)
            val b = clamp((pixel and 0xFF) + shift * 0.85f)
            pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        // 3) Fibres, drawn after the speckle so they stay crisp. Mostly warm grey, a third of them
        //    bright, each a shallow bow rather than a straight scratch.
        val fibrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        val fibres = (area / 480f).toInt().coerceIn(60, 1600)
        for (index in 0 until fibres) {
            val x = generator.range(0f, w.toFloat())
            val y = generator.range(0f, h.toFloat())
            val length = generator.range(4f, 15f) * density
            val angle = generator.range(0f, (Math.PI * 2).toFloat())
            val bright = generator.next() <= 0.35f
            fibrePaint.color = if (bright) {
                (SketchSpec.FIBRE_LIGHT and 0x00FFFFFF) or (generator.range(0.25f, 0.45f) * 255).toInt().shl(24)
            } else {
                (SketchSpec.FIBRE_DARK and 0x00FFFFFF) or (generator.range(0.18f, 0.38f) * 255).toInt().shl(24)
            }
            fibrePaint.strokeWidth = generator.range(0.4f, 1.0f) * density
            val dx = kotlin.math.cos(angle) * length
            val dy = kotlin.math.sin(angle) * length
            val bowX = -dy * 0.12f
            val bowY = dx * 0.12f
            // A shallow quadratic bow is the difference between a fibre and a dash.
            val path = Path()
            path.moveTo(x, y)
            path.quadTo(x + dx * 0.5f + bowX, y + dy * 0.5f + bowY, x + dx, y + dy)
            canvas.drawPath(path, fibrePaint)
        }

        // 4) Corner vignette: a wide, warm inset shadow. Drawn last so it sits over the grain.
        val vignette = Paint(Paint.ANTI_ALIAS_FLAG)
        vignette.shader = RadialGradient(
            w / 2f, h / 2f, sqrt((w * w + h * h).toFloat()) * 0.52f,
            intArrayOf(0x00C4B294, SketchSpec.PAPER_VIGNETTE),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignette)

        // The reference keeps the whole texture layer at 92% opacity over the page colour; without
        // this the mottling reads as blotches instead of as paper.
        canvas.drawColor(0x14F6EFE2)

        return bitmap
    }

    override fun draw(composite: Canvas) {
        if (bounds.isEmpty) return
        val bitmap = grain
        if (bitmap != null) {
            composite.drawBitmap(bitmap, null, RectF(bounds), bitmapPaint)
        } else {
            composite.drawRect(bounds, fillPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        bitmapPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        bitmapPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun clamp(value: Float): Int = when {
        value < 0f -> 0
        value > 255f -> 255
        else -> value.toInt()
    }

    private fun clamp(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }

    private companion object {
        /** Speckle amplitude in levels; the reference page uses 11 peak-to-peak. */
        const val SPECKLE = 11f
    }
}
