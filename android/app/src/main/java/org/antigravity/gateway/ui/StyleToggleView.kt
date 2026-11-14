package org.antigravity.gateway.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import org.antigravity.gateway.R

/**
 * Three-dot skin affordance. Collapsed it reads as a vertical ellipsis; tapping it rolls the
 * outer dots away and stretches the middle one into a single horizontal bar, so the control
 * itself communicates the open state instead of swapping icons.
 */
class StyleToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val dotSize = dp(3.4f).toInt()
    private val dotGap = dp(6f)
    private val stretchFactor = 5.12f
    private val dots = ArrayList<View>(3)

    private val overshoot = android.animation.TimeInterpolator { t ->
        val c = 1.6f
        val u = t - 1f
        1f + (c + 1f) * u * u * u + c * u * u
    }

    var onToggle: ((Boolean) -> Unit)? = null

    var isOpen: Boolean = false
        private set

    init {
        val color = resolveAttrColor(MaterialR.attr.colorOnSurface, 0xFF17181C.toInt())
        repeat(3) {
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                cameraDistance = 12000f
            }
            val lp = LayoutParams(dotSize, dotSize).apply { gravity = Gravity.CENTER }
            addView(dot, lp)
            dots.add(dot)
        }
        applyCollapsed(animate = false)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.style_switch)
        setOnClickListener {
            setOpen(!isOpen)
            onToggle?.invoke(isOpen)
        }
    }

    fun setOpen(open: Boolean, animate: Boolean = true) {
        if (isOpen == open) return
        isOpen = open
        if (open) applyExpanded(animate) else applyCollapsed(animate)
    }

    private fun applyCollapsed(animate: Boolean) {
        val offsets = floatArrayOf(-dotGap, 0f, dotGap)
        dots.forEachIndexed { index, dot ->
            if (animate) {
                dot.animate()
                    .rotationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(offsets[index])
                    .setDuration(ANIM_MS)
                    .setInterpolator(overshoot)
                    .start()
            } else {
                dot.rotationX = 0f
                dot.scaleX = 1f
                dot.scaleY = 1f
                dot.translationY = offsets[index]
            }
        }
    }

    private fun applyExpanded(animate: Boolean) {
        val targets = listOf(
            Target(rotationX = -88f, scaleY = 1f),
            Target(rotationX = 0f, scaleY = 0.59f),
            Target(rotationX = 88f, scaleY = 1f)
        )
        dots.forEachIndexed { index, dot ->
            val target = targets[index]
            if (animate) {
                dot.animate()
                    .rotationX(target.rotationX)
                    .scaleX(stretchFactor)
                    .scaleY(target.scaleY)
                    .translationY(0f)
                    .setDuration(ANIM_MS)
                    .setInterpolator(overshoot)
                    .start()
            } else {
                dot.rotationX = target.rotationX
                dot.scaleX = stretchFactor
                dot.scaleY = target.scaleY
                dot.translationY = 0f
            }
        }
    }

    private data class Target(val rotationX: Float, val scaleY: Float)

    private fun resolveAttrColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val ANIM_MS = 440L
    }
}
