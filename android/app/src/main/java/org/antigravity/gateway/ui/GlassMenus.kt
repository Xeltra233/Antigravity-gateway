package org.antigravity.gateway.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.antigravity.gateway.R
import kotlin.math.roundToInt

/**
 * The anchored menu behind a provider capsule: long press the pill, or tap its more button.
 *
 * A `PopupMenu` would bring the platform panel, which reads as a foreign slab on the ribbon and on
 * the paper skin. This sheet is drawn from the same palette as the dialogs instead, so the menu
 * belongs to whichever skin is active.
 */
object GlassMenus {

    /** .menu{min-width:136px;padding:6px;border-radius:11px} on the 430dp board. */
    private const val WIDTH_DP = 152

    data class Item(
        val label: String,
        val enabled: Boolean = true,
        val destructive: Boolean = false,
        val onSelect: () -> Unit
    )

    fun show(anchor: View, items: List<Item>) {
        val activity = anchor.context as? AppCompatActivity ?: return
        val palette = GlassDialogs.paletteOf(activity)
        val density = activity.resources.displayMetrics.density

        // The menu is a sibling of the dialog plate, not a copy of it: 11dp corners (10 on paper)
        // keep it tighter than the dialog without leaving the skin.
        val radiusDp = if (org.antigravity.gateway.data.SkinPreferences(activity).skin ==
            org.antigravity.gateway.data.Skin.SKETCH
        ) 10f else 11f

        val panel = LayoutInflater.from(activity).inflate(R.layout.menu_glass_popup, null)
        panel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(palette.fill)
            if (palette.strokeWidthDp > 0f) {
                setStroke((palette.strokeWidthDp * density).roundToInt().coerceAtLeast(1), palette.stroke)
            }
        }

        val popup = PopupWindow(
            panel,
            (WIDTH_DP * density).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 14f * density
        popup.isOutsideTouchable = true

        val rows = panel.findViewById<LinearLayout>(R.id.menuGlassRows)
        items.forEach { item ->
            val row = TextView(activity).apply {
                text = item.label
                textSize = 14.5f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (10 * density).roundToInt(),
                    (9 * density).roundToInt(),
                    (10 * density).roundToInt(),
                    (9 * density).roundToInt()
                )
                setTextColor(
                    when {
                        !item.enabled -> palette.secondary
                        item.destructive -> palette.error
                        else -> palette.onSurface
                    }
                )
                isEnabled = item.enabled
                isClickable = true
                setOnClickListener {
                    popup.dismiss()
                    item.onSelect()
                }
            }
            rows.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Anchored under the pill: the panel keeps a small gap so it reads as attached, not merged.
        popup.showAsDropDown(anchor, 0, (-6 * density).roundToInt(), Gravity.END)
    }
}
