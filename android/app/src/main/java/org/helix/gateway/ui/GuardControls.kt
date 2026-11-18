package org.helix.gateway.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.helix.gateway.R
import org.helix.gateway.data.Skin
import org.helix.gateway.data.SkinPreferences

/**
 * The three rows the routing pages are assembled from.
 *
 * They exist because the same settings used to be a Material dialog: spinners, radio buttons and
 * switch widgets, each with its own insets and its own idea of a palette. These rows carry their own
 * painting instead, so a skin only has to be understood once — in [GuardMarks] — and every option on
 * both pages follows it.
 *
 * State is pushed in from the activity after each configuration change; a row never stores anything
 * the gateway does not already have.
 */

/** One selectable line: title, one-line explanation, ring-and-dot mark. */
class ChoiceRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val skin = SkinPreferences(context).skin
    private val title = TextView(context)
    private val hint = TextView(context)
    private val mark = View(context)

    /** Whether this row is the chosen one; painted immediately, never persisted here. */
    var isChosen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            paint()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = (64 * density).toInt()
        // Horizontal padding keeps the text clear of the chosen-row fill instead of sitting on its edge.
        setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())

        val column = LinearLayout(context).apply { orientation = VERTICAL }
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        title.apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
        }
        column.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        hint.apply {
            textSize = 11.5f
            setLineSpacing(0f, 1.2f)
        }
        column.addView(
            hint,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (3 * density).toInt()
            }
        )

        addView(
            mark,
            LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                marginStart = (12 * density).toInt()
            }
        )

        val texts = readStrings(context, attrs, R.styleable.ChoiceRowView)
        title.text = texts.getOrElse(0) { "" }
        hint.text = texts.getOrElse(1) { "" }
        paint()
    }

    private fun paint() {
        val accent = attrColor(context, android.R.attr.colorAccent)
        val idle = attrColor(context, R.attr.agwChevron)
        val sketch = skin == Skin.SKETCH
        val fill = when {
            !isChosen -> Color.TRANSPARENT
            sketch -> SKETCH_CHIP
            skin == Skin.SILK -> SILK_ROW_ON
            else -> tint(accent, ROW_TINT_ALPHA)
        }
        val stroke = if (isChosen && sketch) SketchSpec.INK else Color.TRANSPARENT
        val base = outlined(fill, stroke, ROW_RADIUS_DP, if (isChosen && sketch) 1.4f else 0f, density)
        background = RippleDrawable(ColorStateList.valueOf(ripple(skin, accent)), base, null)

        mark.background = ChoiceMarkDrawable(
            density = density,
            chosen = isChosen,
            accent = accent,
            idle = idle,
            strokeDp = if (sketch) 2.2f else 1.6f
        )
        title.setTextColor(if (sketch) SketchSpec.TEXT else attrColor(context, android.R.attr.textColorPrimary))
        hint.setTextColor(if (sketch) SketchSpec.TEXT_SUB else attrColor(context, android.R.attr.textColorSecondary))
        contentDescription = describe(title.text, hint.text, if (isChosen) "，已选中" else null)
    }

    private companion object {
        const val ROW_RADIUS_DP = 10f
        const val ROW_TINT_ALPHA = 0x24
    }
}

/** One switch line: title, one-line explanation, the skin's own 52x27 capsule. */
class ToggleRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val skin = SkinPreferences(context).skin
    private val title = TextView(context)
    private val hint = TextView(context)
    private val capsule = View(context)

    /** Whether the option is on. The activity owns the toggle; tapping the row flips it there. */
    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            paint()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = (58 * density).toInt()
        setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())

        val column = LinearLayout(context).apply { orientation = VERTICAL }
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        title.apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
        }
        column.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        hint.apply {
            textSize = 11.5f
            setLineSpacing(0f, 1.2f)
        }
        column.addView(
            hint,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (3 * density).toInt()
            }
        )

        addView(
            capsule,
            LayoutParams(
                (SkinSwitch.WIDTH_DP * density).toInt(),
                (SkinSwitch.HEIGHT_DP * density).toInt()
            ).apply { marginStart = (14 * density).toInt() }
        )

        val texts = readStrings(context, attrs, R.styleable.ToggleRowView)
        title.text = texts.getOrElse(0) { "" }
        hint.text = texts.getOrElse(1) { "" }
        paint()
    }

    private fun paint() {
        val accent = attrColor(context, android.R.attr.colorAccent)
        background = RippleDrawable(
            ColorStateList.valueOf(ripple(skin, accent)),
            ColorDrawable(Color.TRANSPARENT),
            null
        )
        capsule.background = SkinSwitch.create(context, skin, isChecked)
        title.setTextColor(if (skin == Skin.SKETCH) SketchSpec.TEXT else attrColor(context, android.R.attr.textColorPrimary))
        hint.setTextColor(if (skin == Skin.SKETCH) SketchSpec.TEXT_SUB else attrColor(context, android.R.attr.textColorSecondary))
        contentDescription = describe(title.text, hint.text, if (isChecked) "，已开启" else "，已关闭")
    }
}

/**
 * One labelled two-way choice: a caption above a track split into two cells.
 *
 * The two cells replace a spinner — a phone has room for both options side by side, and a dropdown
 * that shows one of them at a time hides the alternative the user is choosing between.
 */
class SegmentRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val skin = SkinPreferences(context).skin
    private val caption = TextView(context)
    private val track = LinearLayout(context)
    private val first = TextView(context)
    private val second = TextView(context)

    /** Index of the active cell; written from the state, written back by a tap. */
    var value: Int = 0
        set(newValue) {
            if (field == newValue) return
            field = newValue
            paint()
        }

    /** Invoked after a tap has already moved the cell, so the tap never waits for a round trip. */
    var onChoice: ((Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())

        caption.textSize = 13f
        caption.setTypeface(caption.typeface, Typeface.BOLD)
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        track.orientation = HORIZONTAL
        track.setPadding((3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt())
        addView(
            track,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (9 * density).toInt()
            }
        )

        for (cell in listOf(first, second)) {
            cell.gravity = Gravity.CENTER
            cell.textSize = 13f
            cell.setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            cell.isClickable = true
            track.addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        first.setOnClickListener { choose(0) }
        second.setOnClickListener { choose(1) }

        val texts = readStrings(context, attrs, R.styleable.SegmentRowView)
        caption.text = texts.getOrElse(0) { "" }
        first.text = texts.getOrElse(1) { "" }
        second.text = texts.getOrElse(2) { "" }
        paint()
    }

    private fun choose(index: Int) {
        if (index == value) return
        value = index
        onChoice?.invoke(index)
    }

    private fun paint() {
        val accent = attrColor(context, android.R.attr.colorAccent)
        val sketch = skin == Skin.SKETCH
        val fill = if (sketch) SketchSpec.PAPER else attrColor(context, R.attr.agwLogBg)
        track.background = outlined(
            fill = fill,
            stroke = attrColor(context, R.attr.agwFieldStroke),
            radiusDp = 10f,
            strokeDp = if (sketch) 1.4f else 1f,
            density = density
        )
        listOf(first, second).forEachIndexed { index, cell ->
            val chosen = index == value
            cell.background = when {
                !chosen -> ColorDrawable(Color.TRANSPARENT)
                sketch -> outlined(SKETCH_CHIP, SketchSpec.INK, 7f, 1.4f, density)
                skin == Skin.SILK -> outlined(SILK_ROW_ON, Color.TRANSPARENT, 7f, 0f, density)
                else -> outlined(tint(accent, SEGMENT_TINT_ALPHA), Color.TRANSPARENT, 7f, 0f, density)
            }
            cell.setTextColor(
                when {
                    sketch -> SketchSpec.TEXT
                    chosen -> accent
                    else -> attrColor(context, android.R.attr.textColorSecondary)
                }
            )
            cell.isSelected = chosen
        }
        caption.setTextColor(if (sketch) SketchSpec.TEXT else attrColor(context, android.R.attr.textColorPrimary))
    }

    private companion object {
        const val SEGMENT_TINT_ALPHA = 0x2E
    }
}

// ---- shared painting -------------------------------------------------------

/** Silk glass at 20%: the same pill the active provider capsule uses. */
internal const val SILK_ROW_ON = 0x3396AFE1


/** Paper chip, matching the 使用中 badge on the sketch skin. */
internal const val SKETCH_CHIP = 0xFFDFF0CD.toInt()

/** Press feedback tinted with the sketch ink instead of the skin accent. */
private const val SKETCH_RIPPLE = 0x333E4450

/** White-on-glass press feedback for the silk. */
private const val GLASS_RIPPLE = 0x33FFFFFF

private fun ripple(skin: Skin, accent: Int): Int = when (skin) {
    Skin.SILK -> GLASS_RIPPLE
    Skin.SKETCH -> SKETCH_RIPPLE
    else -> tint(accent, 0x33)
}

/** Title, hint and the current state, in the order a screen reader should say them. */
private fun describe(title: CharSequence, hint: CharSequence, state: String?): String = buildString {
    append(title)
    if (hint.isNotEmpty()) append("，").append(hint)
    if (state != null) append(state)
}

/**
 * Reads a row's declared strings in styleable order.
 *
 * `obtainStyledAttributes(attrs, intArrayOf(...))` indexes by position in that array, which is what
 * lets one helper serve all three rows without three copies of the recycle dance.
 */
private fun readStrings(context: Context, attrs: AttributeSet?, styleable: IntArray): List<String> {
    if (attrs == null) return emptyList()
    val values = context.obtainStyledAttributes(attrs, styleable)
    return try {
        styleable.indices.map { index -> values.getString(index).orEmpty() }
    } finally {
        values.recycle()
    }
}
