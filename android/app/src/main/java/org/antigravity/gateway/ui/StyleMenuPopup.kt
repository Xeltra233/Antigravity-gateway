package org.antigravity.gateway.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import org.antigravity.gateway.R
import org.antigravity.gateway.data.Skin

/**
 * Skin picker that hangs below the toggle. It scales down from the top-right corner instead of
 * appearing instantly, and it stays detached from the toggle so the open toggle reads as a
 * separate control state rather than a connected arrow.
 */
class StyleMenuPopup(
    private val anchor: View,
    private val current: Skin,
    private val onPick: (Skin) -> Unit
) {

    private val context: Context = anchor.context
    private val popup: PopupWindow
    private val content: LinearLayout
    private var dismissListener: (() -> Unit)? = null

    init {
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(attrColor(MaterialR.attr.colorSurface, Color.WHITE))
                setStroke(dp(1.2f).toInt(), attrColor(R.attr.agwCardBorder, Color.LTGRAY))
            }
            elevation = dp(14f)
        }

        Skin.entries.forEach { skin -> content.addView(buildRow(skin)) }

        popup = PopupWindow(
            content,
            dp(SIZE_DP).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(14f)
            setOnDismissListener { dismissListener?.invoke() }
        }
    }

    fun show() {
        if (popup.isShowing) return
        // Showing inside the anchor's own touch pass lets the trailing ACTION_UP land outside the
        // window and dismiss it immediately, so the drop is posted to the next frame.
        anchor.post {
            if (!anchor.isAttachedToWindow) return@post
            val width = dp(SIZE_DP).toInt()
            val offsetX = anchor.width - width
            popup.showAsDropDown(anchor, offsetX, dp(13f).toInt())
            animateIn(width)
        }
    }

    private fun animateIn(width: Int) {
        content.pivotX = width.toFloat()
        content.pivotY = 0f
        content.scaleY = 0f
        content.scaleX = 0.9f
        content.alpha = 0f
        content.animate()
            .scaleY(1f)
            .scaleX(1f)
            .alpha(1f)
            .setDuration(EXPAND_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
            .start()
    }

    fun dismiss() {
        popup.dismiss()
    }

    /** Notifies the toggle so it can roll back to the collapsed dots. */
    fun setOnDismissListener(listener: () -> Unit) {
        dismissListener = listener
    }

    val isShowing: Boolean get() = popup.isShowing

    private fun buildRow(skin: Skin): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f).toInt(), dp(9f).toInt(), dp(10f).toInt(), dp(9f).toInt())
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(9f)
                setColor(Color.TRANSPARENT)
            }
            setOnClickListener {
                onPick(skin)
                dismiss()
            }
        }

        val chip = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(7f)
                setColor(skin.chipColor)
                setStroke(dp(1f).toInt(), skin.chipBorder)
            }
        }
        row.addView(chip, LinearLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt()))

        val label = TextView(context).apply {
            text = context.getString(skin.labelRes)
            setTextColor(attrColor(MaterialR.attr.colorOnSurface, Color.BLACK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setPadding(dp(10f).toInt(), 0, 0, 0)
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val tick = TextView(context).apply {
            text = "✓"
            setTextColor(attrColor(MaterialR.attr.colorAccent, Color.GRAY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = if (skin == current) View.VISIBLE else View.INVISIBLE
        }
        row.addView(tick)

        return row
    }

    private fun attrColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    private companion object {
        const val SIZE_DP = 186f
        const val EXPAND_MS = 340L
    }
}
