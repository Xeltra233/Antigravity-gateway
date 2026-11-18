package org.helix.gateway.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat

import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import androidx.core.content.ContextCompat
import org.helix.gateway.R
import org.helix.gateway.data.AntiRouteLevel
import org.helix.gateway.data.ModelReportMode
import org.helix.gateway.data.Skin

/**
 * Hand-drawn marks for the routing pages.
 *
 * The guard settings used to be a Material dialog whose spinners and switches came from the theme,
 * so on paper and on the silk they read as a system form dropped on top of the skin. Everything in
 * this file is drawn from the skin's own accent and rule colours instead, which is what puts the
 * two pages in the same surface family as the rest of the shell.
 */

/** Four bars, lit up to the current guard level: nothing for 关闭, all four for 拆分. */
class LevelMeterDrawable(
    private val density: Float,
    private val lit: Int,
    private val accent: Int,
    private val idle: Int
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return
        val gap = GAP_DP * density
        val barWidth = (width - gap * (SHARES.size - 1)) / SHARES.size
        if (barWidth <= 0f) return
        val radius = 1.4f * density
        paint.style = Paint.Style.FILL
        SHARES.forEachIndexed { index, share ->
            val left = bounds.left + index * (barWidth + gap)
            val bottom = bounds.bottom.toFloat()
            bar.set(left, bottom - height * share, left + barWidth, bottom)
            paint.color = if (index < lit) accent else idle
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** Heights as a share of the view: a signal ladder rather than four equal bars. */
        val SHARES = floatArrayOf(0.40f, 0.58f, 0.78f, 1f)
        const val GAP_DP = 3.2f
    }
}

/** A sheet of lines with an arrow leaving it: the resolved model going back to the client. */
class ReportGlyphDrawable(
    private val density: Float,
    private val active: Boolean,
    private val accent: Int,
    private val idle: Int,
    private val strokeDp: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val sheet = RectF()
    private val head = Path()

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return

        val color = if (active) accent else idle
        val stroke = strokeDp * density
        paint.color = color
        paint.strokeWidth = stroke

        val arrowStart = bounds.left + width * (1f - ARROW_SHARE)
        val inset = stroke / 2f
        sheet.set(
            bounds.left + inset,
            bounds.top + inset,
            arrowStart - GAP_DP * density,
            bounds.bottom - inset
        )
        if (sheet.width() <= 0f) return
        canvas.drawRoundRect(sheet, CORNER_DP * density, CORNER_DP * density, paint)

        // Two lines of "text" inside the sheet; without them the box reads as a checkbox.
        paint.strokeWidth = stroke * 0.85f
        val lineInset = sheet.width() * 0.22f
        val firstLine = sheet.top + sheet.height() * 0.34f
        val secondLine = sheet.top + sheet.height() * 0.64f
        canvas.drawLine(sheet.left + lineInset, firstLine, sheet.right - lineInset, firstLine, paint)
        canvas.drawLine(sheet.left + lineInset, secondLine, sheet.right - lineInset * 1.7f, secondLine, paint)

        // The arrow: shaft plus a chevron head, both in the same pen.
        paint.strokeWidth = stroke * 1.1f
        val middle = sheet.centerY()
        val tipX = bounds.right - stroke
        canvas.drawLine(sheet.right + GAP_DP * density, middle, tipX, middle, paint)
        val flare = sheet.height() * 0.22f
        val neck = tipX - flare * 1.6f
        head.reset()
        head.moveTo(neck, middle - flare)
        head.lineTo(tipX, middle)
        head.lineTo(neck, middle + flare)
        canvas.drawPath(head, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val ARROW_SHARE = 0.30f
        const val GAP_DP = 2.4f
        const val CORNER_DP = 2.4f
    }
}

/**
 * Ring plus dot, the selection mark the option rows carry instead of a Material radio button.
 *
 * The dot is drawn only when the row is chosen: an empty ring is the idle state everywhere else in
 * the app, so the chosen row is the one with something in it.
 */
class ChoiceMarkDrawable(
    private val density: Float,
    private val chosen: Boolean,
    private val accent: Int,
    private val idle: Int,
    private val strokeDp: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = RectF()

    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        if (size <= 0f) return
        val stroke = strokeDp * density
        val radius = size / 2f - stroke / 2f
        if (radius <= 0f) return
        val centerX = bounds.left + bounds.width() / 2f
        val centerY = bounds.top + bounds.height() / 2f
        ring.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = if (chosen) accent else idle
        canvas.drawCircle(ring.centerX(), ring.centerY(), radius, paint)

        if (!chosen) return
        paint.style = Paint.Style.FILL
        canvas.drawCircle(ring.centerX(), ring.centerY(), radius * DOT_SHARE, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val DOT_SHARE = 0.46f
    }
}

internal object SkinSwitch {

    fun create(context: Context, skin: Skin, on: Boolean): Drawable {
        if (skin == Skin.SILK) {
            // Fresh instance per call: the cached drawable is shared, and attaching it to a second
            // view raises "already belongs to another owner" for layer-lists without a constant
            // state.
            val id = if (on) R.drawable.pv_switch else R.drawable.pv_switch_off
            return ContextCompat.getDrawable(context, id)?.constantState?.newDrawable(context.resources)?.mutate()
                ?: ColorDrawable(Color.TRANSPARENT)
        }
        val density = context.resources.displayMetrics.density
        val track = if (on) attrColor(context, R.attr.agwSwitchOn) else attrColor(context, R.attr.agwSwitchOff)
        val trackShape = GradientDrawable().apply {
            cornerRadius = TRACK_RADIUS_DP * density
            setColor(track)
            if (skin == Skin.SKETCH) {
                setStroke(
                    (SKETCH_TRACK_STROKE_DP * density).toInt().coerceAtLeast(1),
                    attrColor(context, R.attr.agwFieldStroke)
                )
            }
        }
        val thumbShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (skin == Skin.SKETCH) SketchSpec.INK else Color.WHITE)
        }
        val layers = LayerDrawable(arrayOf(trackShape, thumbShape))
        val pad = (PAD_DP * density).toInt()
        val travel = (WIDTH_DP - THUMB_DP - PAD_DP * 2) * density
        val left = (PAD_DP * density).toInt()
        layers.setLayerInset(1, if (on) left + travel.toInt() else left, pad, if (on) left else left + travel.toInt(), pad)
        return layers
    }

    const val WIDTH_DP = 52f
    const val HEIGHT_DP = 27f

    private const val TRACK_RADIUS_DP = 13.5f
    private const val THUMB_DP = 21f
    private const val PAD_DP = 3f
    private const val SKETCH_TRACK_STROKE_DP = 1.6f
}

/** Rounded fill/stroke used by the rows and the segmented tracks. */
internal fun outlined(fill: Int, stroke: Int, radiusDp: Float, strokeDp: Float, density: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * density
        setColor(fill)
        if (strokeDp > 0f) {
            setStroke((strokeDp * density).toInt().coerceAtLeast(1), stroke)
        }
    }

/** Bars lit on the level meter: the guard adds one stage per level, none when it is off. */
private val AntiRouteLevel.bars: Int
    get() = when (this) {
        AntiRouteLevel.OFF -> 0
        AntiRouteLevel.WIRE -> 1
        AntiRouteLevel.STANDARD -> 2
        AntiRouteLevel.DEEP -> 3
    }

/** The routing entry's guard glyph; the two pages and both entries paint from the same source. */
internal fun levelMeter(density: Float, level: AntiRouteLevel, accent: Int, rule: Int): Drawable =
    LevelMeterDrawable(density, level.bars, accent, rule)

/** The routing entry's reporting glyph: lit as soon as the resolved model is written anywhere. */
internal fun reportGlyph(
    density: Float,
    mode: ModelReportMode,
    accent: Int,
    rule: Int,
    strokeDp: Float
): Drawable = ReportGlyphDrawable(density, mode != ModelReportMode.OFF, accent, rule, strokeDp)
/** Theme attribute lookup for the views that paint themselves. */
internal fun attrColor(context: Context, attr: Int): Int {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(attr, value, true)) return Color.GRAY
    return if (value.resourceId != 0) {
        ContextCompat.getColor(context, value.resourceId)
    } else {
        value.data
    }
}

/** Accent at the given opacity, for the flat tints the rows and segments use. */
internal fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
