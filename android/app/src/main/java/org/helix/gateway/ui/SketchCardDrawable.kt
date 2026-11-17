package org.helix.gateway.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator

/**
 * Card surface for the sketch skin: paper with a hand-drawn outline.
 *
 * The body stays flat. In the reference the page carries the texture and the crayon belongs to
 * buttons, so hatching a card would read as a second material rather than as the same sheet.
 */
class SketchCardDrawable(private val density: Float) : Drawable() {

    private val paper = PaperSurfaceDrawable(density, SketchSpec.PAPER)
    private val painter = SketchStrokePainter(density)
    private val rect = RectF()

    /** Share of the outline drawn so far; 1 once the pen has been round the whole card. */
    private var reveal = 1f
    private var revealAnimator: ValueAnimator? = null

    /** Gives the card a lift off the page; the page is darker, so the card needs a soft edge. */
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SketchSpec.PAPER
    }

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.isEmpty) return
        val inset = INSET_DP * density
        rect.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset
        )
        paper.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        painter.build(rect, SketchSpec.CARD_RADIUS_DP * density, SketchSpec.INK)
    }

    /**
     * Draws the outline on instead of popping it in.
     *
     * Driven from the host rather than from [onBoundsChange]: bounds are settled during layout, which
     * on a cold start is long before the window is on screen, so an animation started there finishes
     * where nobody can see it.
     */
    fun restartReveal() {
        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = REVEAL_MS
            startDelay = REVEAL_DELAY_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || rect.isEmpty) return
        val radius = SketchSpec.CARD_RADIUS_DP * density
        canvas.drawRoundRect(rect, radius, radius, body)
        canvas.save()
        canvas.clipPath(painter.clipPath())
        paper.draw(canvas)
        canvas.restore()
        painter.draw(canvas, reveal)
    }

    override fun setAlpha(alpha: Int) {
        paper.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paper.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Lets a ripple clone this drawable instead of logging a LayerDrawable ownership warning. */
    override fun getConstantState(): Drawable.ConstantState = object : Drawable.ConstantState() {
        override fun newDrawable(): Drawable = SketchCardDrawable(density)

        override fun getChangingConfigurations(): Int = 0
    }

    private companion object {
        const val INSET_DP = 1.2f
        const val REVEAL_MS = 460L
        const val REVEAL_DELAY_MS = 0L
    }
}
