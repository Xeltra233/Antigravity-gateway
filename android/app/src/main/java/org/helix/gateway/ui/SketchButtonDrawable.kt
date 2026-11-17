package org.helix.gateway.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crayon-filled button.
 *
 * The fill is not a solid colour: it is a set of diagonal strokes laid down side by side, the way a
 * pencil fills an area when you follow the shape rather than colouring inside it. Three things make
 * that read as crayon instead of as a printed stripe:
 *
 *  - every few strokes the spacing widens, so the hatching comes in clusters instead of a comb;
 *  - each stroke is really two or three slightly diverging fibres;
 *  - the strokes are punched through by paper-coloured specks, which is what the paper's tooth does
 *    to a soft pencil.
 *
 * The blue underneath is very faint on purpose. A solid pale blue would hide the page, and the page
 * showing through the gaps is most of the effect.
 */
class SketchButtonDrawable(private val density: Float) : Drawable() {

    private val outline = SketchStrokePainter(
        density,
        SketchSpec.BUTTON_MAIN_DP,
        SketchSpec.BUTTON_SUB_DP,
        SketchSpec.BUTTON_FLY_DP
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private val angle = SketchSpec.HATCH_DEGREES * Math.PI.toFloat() / 180f

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.isEmpty) return
        val inset = INSET_DP * density
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        clip.reset()
        clip.addRoundRect(rect, SketchSpec.BUTTON_RADIUS_DP * density, SketchSpec.BUTTON_RADIUS_DP * density, Path.Direction.CW)
        outline.build(rect, SketchSpec.BUTTON_RADIUS_DP * density, SketchSpec.INK)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val generator = SketchSpec.Lcg(SEED)

        canvas.save()
        canvas.clipPath(clip)

        fillPaint.color = SketchSpec.BUTTON_FILL
        canvas.drawRect(rect, fillPaint)

        drawHatch(canvas, generator)
        drawTooth(canvas, generator)

        canvas.restore()

        drawRim(canvas)
        outline.draw(canvas)
    }

    /** Diagonal strokes, clustered. */
    private fun drawHatch(canvas: Canvas, generator: SketchSpec.Lcg) {
        val cosA = cos(angle)
        val sinA = sin(angle)
        val height = rect.height()
        val width = rect.width()

        var cursor = -height * 1.4f
        var stroke = 0
        while (cursor < width + height * 1.4f) {
            stroke++
            // Every seventh stroke leaves a wider gap; the reference page does this to keep the
            // hatching from looking machine-ruled.
            cursor += if (stroke % 7 == 0) generator.range(2.4f, 4.1f) * density
            else generator.range(1.05f, 2.15f) * density

            val startX = rect.left + cursor
            val startY = rect.bottom + 8f * density
            val length = height / abs(sinA) + 28f * density
            val endX = startX + length * cosA
            val endY = startY + length * sinA

            val fibres = if (generator.next() > 0.35f) 2 else 3
            for (index in 0 until fibres) {
                paint.color = SketchSpec.BUTTON_HATCH[(generator.next() * SketchSpec.BUTTON_HATCH.size).toInt()
                    .coerceIn(0, SketchSpec.BUTTON_HATCH.size - 1)]
                // The per-colour alpha carries the weight; the jitter only varies within it, so a
                // light hatch stays light instead of being overridden to a fixed 40-80%.
                paint.alpha = (android.graphics.Color.alpha(paint.color) * generator.range(0.55f, 1.0f)).toInt()
                paint.strokeWidth = generator.range(0.75f, 1.75f) * density

                val offsetX = generator.range(-0.9f, 0.9f) * density
                val offsetY = generator.range(-0.9f, 0.9f) * density
                val midX = (startX + endX) * 0.5f + generator.range(-2.2f, 2.2f) * density
                val midY = (startY + endY) * 0.5f + generator.range(-1.8f, 1.8f) * density

                canvas.drawPath(
                    Path().apply {
                        moveTo(startX + offsetX, startY + offsetY)
                        quadTo(midX, midY, endX + generator.range(-1.2f, 1.2f) * density, endY + generator.range(-1.2f, 1.2f) * density)
                    },
                    paint
                )
            }
        }
    }

    /**
     * Paper tooth cutting through the crayon: many tiny flecks, mostly the page colour, a quarter of
     * them heavier pigment where the pencil dug in.
     */
    private fun drawTooth(canvas: Canvas, generator: SketchSpec.Lcg) {
        val areaDp = (rect.width() / density) * (rect.height() / density)
        val flecks = (areaDp / 33f).toInt().coerceIn(80, 1500)
        val fleckPaint = Paint()
        for (index in 0 until flecks) {
            val x = rect.left + generator.range(0f, rect.width())
            val y = rect.top + generator.range(0f, rect.height())
            val w = generator.range(0.6f, 1.8f) * density
            val h = generator.range(0.45f, 1.25f) * density
            val void = generator.next() > 0.25f
            fleckPaint.color = if (void) SketchSpec.PENCIL_VOID else SketchSpec.PENCIL_PRESS
            fleckPaint.alpha = (generator.range(if (void) 0.22f else 0.16f, if (void) 0.56f else 0.40f) * 255).toInt()

            canvas.save()
            canvas.translate(x, y)
            canvas.rotate((SketchSpec.HATCH_DEGREES + generator.range(-0.2f, 0.2f) * 57.3f))
            canvas.drawRect(0f, 0f, w, h, fleckPaint)
            canvas.restore()
        }
    }

    /** Colour collects where a hand stops at the boundary. */
    private fun drawRim(canvas: Canvas) {
        paint.color = SketchSpec.PENCIL_RIM
        paint.alpha = 255
        paint.strokeWidth = 2.5f * density
        canvas.drawRoundRect(rect, SketchSpec.BUTTON_RADIUS_DP * density, SketchSpec.BUTTON_RADIUS_DP * density, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Lets a ripple clone this drawable instead of logging a LayerDrawable ownership warning. */
    override fun getConstantState(): Drawable.ConstantState = object : Drawable.ConstantState() {
        override fun newDrawable(): Drawable = SketchButtonDrawable(density)

        override fun getChangingConfigurations(): Int = 0
    }

    private companion object {
        const val INSET_DP = 1.4f
        const val SEED = 73421L
    }
}
