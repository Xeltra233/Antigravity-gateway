package org.helix.gateway.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.min

/**
 * Hand-drawn outline for the sketch skin — three separate passes, not one stroke drawn thicker.
 *
 * Each pass has its own inset, its own jitter and its own seed, so the three lines run *parallel*
 * rather than stacked: the secondary line drifts outside the main one near the corners and crosses
 * it along the sides, which is what a pencil does when it retraces a rectangle without measuring.
 * Every straight side gets a control point at its midpoint, so the sides bow instead of being
 * perfectly straight.
 *
 * The third pass is a dashed fly-line. The dash segments are deliberately unequal (18, 6, 35, 9):
 * an even pattern would land every gap at the same offset on every card, and the eye reads that
 * period as a texture bug rather than as a drawn line.
 */
class SketchStrokePainter(
    private val density: Float,
    private val mainWidthDp: Float = SketchSpec.CARD_MAIN_DP,
    private val subWidthDp: Float = SketchSpec.CARD_SUB_DP,
    private val flyWidthDp: Float = SketchSpec.CARD_FLY_DP
) {

    private val clip = Path()
    private val sub = Path()
    private val fly = Path()
    private val main = Path()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val flyDash: DashPathEffect =
        DashPathEffect(SketchSpec.FLY_DASH.map { it * density }.toFloatArray(), 0f)

    /**
     * Punches paper grain through the stroke. A pencil line is not a solid ribbon: the tooth of the
     * paper lifts pigment in thousands of tiny places, and without that the outline reads as vector.
     */
    private val punch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        alpha = GRAIN_DEPTH
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private val layerBounds = RectF()

    fun build(rect: RectF, radius: Float, ink: Int) {
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)

        // One generator for the whole outline: the passes must stay related, otherwise each one
        // wanders independently and the result reads as three unrelated rectangles.
        val generator = SketchSpec.Lcg(SEED)

        sub.reset()
        trace(sub, rect, radius, SketchSpec.JITTER_SUB_DP, SketchSpec.INSET_SUB_DP, generator)

        fly.reset()
        trace(fly, rect, radius, SketchSpec.JITTER_FLY_DP, SketchSpec.INSET_FLY_DP, generator)

        main.reset()
        trace(main, rect, radius, SketchSpec.JITTER_MAIN_DP, SketchSpec.INSET_MAIN_DP, generator)

        paint.color = ink
    }

    fun clipPath(): Path = clip

    /**
     * @param reveal 0..1 share of the outline that has been drawn so far. The three passes are cut
     * together, so the pen appears to travel once around the card rather than each pass arriving on
     * its own schedule.
     */
    fun draw(canvas: Canvas, reveal: Float = 1f) {
        if (main.isEmpty) return

        if (reveal < 0.999f) {
            drawPartial(canvas, reveal.coerceIn(0f, 1f))
            return
        }

        // Three passes are laid down inside one layer so a single grain mask can lift pigment out of
        // all of them at once; masking each pass separately would make the passes disagree wherever
        // they overlap.
        main.computeBounds(layerBounds, true)
        layerBounds.inset(-4f * density, -4f * density)
        canvas.saveLayer(layerBounds, null)
        paint.alpha = 255

        paint.color = SketchSpec.INK_SUB
        paint.alpha = SketchSpec.INK_SUB_ALPHA
        paint.strokeWidth = subWidthDp * density
        paint.pathEffect = null
        canvas.drawPath(sub, paint)

        paint.color = SketchSpec.INK_FLY
        paint.alpha = SketchSpec.INK_FLY_ALPHA
        paint.strokeWidth = flyWidthDp * density
        paint.pathEffect = flyDash
        canvas.drawPath(fly, paint)

        paint.color = SketchSpec.INK
        paint.alpha = 255
        paint.strokeWidth = mainWidthDp * density
        paint.pathEffect = null
        canvas.drawPath(main, paint)

        punchGrain(canvas)
        canvas.restore()
    }

    /** Lays down the same three passes, each trimmed to the travelled share of its length. */
    private fun drawPartial(canvas: Canvas, reveal: Float) {
        val subVisible = trimmed(sub, reveal)
        val flyVisible = trimmed(fly, reveal)
        val mainVisible = trimmed(main, reveal)

        paint.alpha = 255
        paint.pathEffect = null

        paint.color = SketchSpec.INK_SUB
        paint.alpha = SketchSpec.INK_SUB_ALPHA
        paint.strokeWidth = subWidthDp * density
        canvas.drawPath(subVisible, paint)

        paint.color = SketchSpec.INK_FLY
        paint.alpha = SketchSpec.INK_FLY_ALPHA
        paint.strokeWidth = flyWidthDp * density
        paint.pathEffect = flyDash
        canvas.drawPath(flyVisible, paint)

        paint.color = SketchSpec.INK
        paint.alpha = 255
        paint.strokeWidth = mainWidthDp * density
        paint.pathEffect = null
        canvas.drawPath(mainVisible, paint)
    }

    private fun trimmed(source: Path, reveal: Float): Path {
        val out = Path()
        if (source.isEmpty) return out
        val measure = PathMeasure(source, false)
        val total = measure.length
        if (total <= 0f) return out
        val length = total * reveal
        if (length <= 0f) return out
        // A round card has several contour segments; walk them in order so the pen keeps moving in
        // one direction instead of restarting at each corner.
        var start = 0f
        var remaining = length
        val piece = Path()
        do {
            val segmentLength = measure.length
            if (segmentLength > 0f) {
                val take = minOf(remaining, segmentLength)
                piece.reset()
                if (measure.getSegment(0f, take, piece, true)) out.addPath(piece)
                remaining -= take
            }
            if (remaining <= 0f) break
        } while (measure.nextContour())
        return out
    }

    /**
     * Lifts pigment out of the stroke where the paper's tooth would have caught it.
     *
     * Two passes: one walks the outline itself, because a pencil loses contact along the edge far
     * more often than in the middle, and that is what stops the line reading as a vector stroke; the
     * other scatters lightly inside the bounds so the body of the line is not a flat fill either.
     */
    private fun punchGrain(canvas: Canvas) {
        val generator = SketchSpec.Lcg(SEED + 17)

        val measure = PathMeasure(main, false)
        val length = measure.length
        if (length > 0f) {
            val point = FloatArray(2)
            val tangent = FloatArray(2)
            val half = mainWidthDp * density * 0.5f
            // Uneven spacing on purpose: an even walk puts a hole at the same pitch along every
            // side, and that pitch is exactly what reads as a printed perforation.
            var travelled = 0f
            while (travelled < length) {
                measure.getPosTan(travelled, point, tangent)
                // The bite goes on the edge, never through the middle: a pencil loses the paper at
                // the border of the stroke, and cutting the centre would thin the line instead of
                // roughening it.
                val side = if (generator.range(0f, 1f) > 0.5f) 1f else -1f
                val offset = half * generator.range(0.45f, 1.05f) * side
                val nx = -tangent[1]
                val ny = tangent[0]
                // Squared random: mostly shallow bites, a few deep ones.
                val depth = generator.range(0f, 1f).let { it * it }
                punch.alpha = (GRAIN_DEPTH * (0.12f + 0.88f * depth)).toInt()
                canvas.drawCircle(
                    point[0] + nx * offset, point[1] + ny * offset,
                    generator.range(0.22f, 0.62f) * density, punch
                )
                travelled += generator.range(0.35f, 2.2f) * density
            }
        }

        val area = layerBounds.width() * layerBounds.height()
        val holes = (area / GRAIN_AREA).toInt().coerceIn(40, 600)
        punch.alpha = (GRAIN_DEPTH * BODY_DEPTH).toInt()
        for (index in 0 until holes) {
            val x = generator.range(layerBounds.left, layerBounds.right)
            val y = generator.range(layerBounds.top, layerBounds.bottom)
            canvas.drawCircle(x, y, generator.range(0.24f, 0.8f) * density, punch)
        }
    }

    /** Rounded rectangle with jittered corners and a bowed control point on each straight side. */
    private fun trace(target: Path, rect: RectF, radius: Float, jitterDp: Float, insetDp: Float, generator: SketchSpec.Lcg) {
        val jitter = jitterDp * density
        val inset = insetDp * density
        val bow = SketchSpec.SIDE_BOW_DP * density
        val cross = jitter * SketchSpec.SIDE_BOW_CROSS

        val x0 = rect.left + inset + generator.range(-jitter, jitter)
        val y0 = rect.top + inset + generator.range(-jitter, jitter)
        val x1 = rect.right - inset + generator.range(-jitter, jitter)
        val y1 = rect.bottom - inset + generator.range(-jitter, jitter)
        if (x1 - x0 < 2f || y1 - y0 < 2f) return

        val corner = min(radius, min((x1 - x0) / 2f, (y1 - y0) / 2f))

        val topMidX = (x0 + x1) * 0.5f + generator.range(-bow, bow)
        val topMidY = y0 + generator.range(-cross, cross)
        val bottomMidX = (x0 + x1) * 0.5f + generator.range(-bow, bow)
        val bottomMidY = y1 + generator.range(-cross, cross)
        val leftMidX = x0 + generator.range(-cross, cross)
        val leftMidY = (y0 + y1) * 0.5f + generator.range(-bow * 0.75f, bow * 0.75f)
        val rightMidX = x1 + generator.range(-cross, cross)
        val rightMidY = (y0 + y1) * 0.5f + generator.range(-bow * 0.75f, bow * 0.75f)

        target.moveTo(x0 + corner, y0)
        target.quadTo(topMidX, topMidY, x1 - corner, y0)
        target.quadTo(x1, y0, x1, y0 + corner)
        target.quadTo(rightMidX, rightMidY, x1, y1 - corner)
        target.quadTo(x1, y1, x1 - corner, y1)
        target.quadTo(bottomMidX, bottomMidY, x0 + corner, y1)
        target.quadTo(x0, y1, x0, y1 - corner)
        target.quadTo(leftMidX, leftMidY, x0, y0 + corner)
        target.quadTo(x0, y0, x0 + corner, y0)
        target.close()
    }

    private companion object {
        /** Fixed so a re-draw never reshuffles the outline. */
        const val SEED = 20261115L

        /** How much pigment the grain lifts out, out of 255. */
        const val GRAIN_DEPTH = 110

        /** One grain hole per this many square px of stroke bounds. */
        const val GRAIN_AREA = 220f

        /** Spacing along the outline between two edge grain samples, in dp. */
        const val EDGE_STEP_DP = 1.1f

        /** How hard the interior scatter bites, relative to [GRAIN_DEPTH]. */
        const val BODY_DEPTH = 0.55f
    }
}
