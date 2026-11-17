package org.helix.gateway.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.helix.gateway.R
import org.helix.gateway.data.Skin
import org.helix.gateway.data.SkinPreferences
import kotlin.math.roundToInt

/**
 * The one dialog surface the app uses for text entry and confirmation.
 *
 * Material's `AlertDialog` brings a fixed panel, a floating label and a pair of text buttons whose
 * insets do not match the rest of the shell, which is why the stock dialog read as a plastic plate
 * dropped on the ribbon. This panel is drawn from the same theme attributes as the cards, so the
 * dialog is part of the skin rather than something that sits on top of it.
 *
 * Each skin keeps its own material:
 *  - silk: a 96% glass plate over the ribbon, hairline highlight border
 *  - minimal light / minimal dark: an opaque card with the skin border
 *  - sketch: paper with the ink outline the rest of that skin draws by hand
 */
object GlassDialogs {

    private const val MAX_WIDTH_DP = 420

    /**
     * Single-field editor. [validate] returns a message to keep the dialog open, or null to commit.
     */
    fun input(
        activity: AppCompatActivity,
        title: String,
        label: String? = null,
        value: String = "",
        hint: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        confirmLabel: String = "确定",
        validate: ((String) -> String?)? = null,
        onCommit: (String) -> Unit
    ) {
        val palette = Palette.of(activity)
        val panel = inflatePanel(activity, palette)
        val titleView = panel.findViewById<TextView>(R.id.tvGlassTitle)
        val labelView = panel.findViewById<TextView>(R.id.tvGlassLabel)
        val field = panel.findViewById<EditText>(R.id.etGlassValue)
        val rule = panel.findViewById<View>(R.id.viewGlassRule)
        val hintView = panel.findViewById<TextView>(R.id.tvGlassHint)
        val cancel = panel.findViewById<TextView>(R.id.btnGlassCancel)
        val confirm = panel.findViewById<TextView>(R.id.btnGlassConfirm)

        titleView.setTextColor(palette.onSurface)
        titleView.text = title

        if (label.isNullOrBlank()) {
            labelView.visibility = View.GONE
        } else {
            labelView.visibility = View.VISIBLE
            labelView.setTextColor(palette.secondary)
            labelView.text = label
        }

        field.visibility = View.VISIBLE
        field.setTextColor(palette.onSurface)
        field.setHintTextColor(palette.secondary)
        field.hint = hint
        field.inputType = inputType
        field.setText(value)
        field.setSelection(field.text.length)
        field.setImeOptions(EditorInfo.IME_ACTION_DONE)
        field.setOnFocusChangeListener { _, hasFocus -> paintRule(rule, palette, hasFocus) }
        paintRule(rule, palette, false)

        cancel.setTextColor(palette.secondary)
        cancel.text = "取消"
        confirm.setTextColor(palette.accent)
        confirm.text = confirmLabel

        val dialog = createDialog(activity, panel, palette)

        fun commit() {
            val text = field.text?.toString()?.trim().orEmpty()
            val error = validate?.invoke(text)
            if (error != null) {
                hintView.visibility = View.VISIBLE
                hintView.setTextColor(palette.error)
                hintView.text = error
                return
            }
            dismissAnimated(dialog, panel)
            onCommit(text)
        }

        cancel.setOnClickListener { dismissAnimated(dialog, panel) }
        confirm.setOnClickListener { commit() }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit()
                true
            } else {
                false
            }
        }

        show(dialog, panel, palette, focus = field)
    }

    /** Title plus body, used for the key read-out, the run log and destructive confirmations. */
    fun message(
        activity: AppCompatActivity,
        title: String,
        message: String,
        confirmLabel: String = "确定",
        cancelLabel: String? = null,
        destructive: Boolean = false,
        onConfirm: (() -> Unit)? = null
    ) {
        val palette = Palette.of(activity)
        val panel = inflatePanel(activity, palette)
        val titleView = panel.findViewById<TextView>(R.id.tvGlassTitle)
        val bodyView = panel.findViewById<TextView>(R.id.tvGlassMessage)
        val labelView = panel.findViewById<TextView>(R.id.tvGlassLabel)
        val field = panel.findViewById<EditText>(R.id.etGlassValue)
        val rule = panel.findViewById<View>(R.id.viewGlassRule)
        val hintView = panel.findViewById<TextView>(R.id.tvGlassHint)
        val cancel = panel.findViewById<TextView>(R.id.btnGlassCancel)
        val confirm = panel.findViewById<TextView>(R.id.btnGlassConfirm)

        titleView.setTextColor(palette.onSurface)
        titleView.text = title

        bodyView.visibility = View.VISIBLE
        bodyView.setTextColor(palette.secondary)
        bodyView.text = message

        labelView.visibility = View.GONE
        field.visibility = View.GONE
        rule.visibility = View.GONE
        hintView.visibility = View.GONE

        val dialog = createDialog(activity, panel, palette)

        confirm.text = confirmLabel
        confirm.setTextColor(if (destructive) palette.error else palette.accent)
        confirm.setOnClickListener {
            dismissAnimated(dialog, panel)
            onConfirm?.invoke()
        }

        if (cancelLabel == null) {
            cancel.visibility = View.GONE
        } else {
            cancel.visibility = View.VISIBLE
            cancel.setTextColor(palette.secondary)
            cancel.text = cancelLabel
            cancel.setOnClickListener { dismissAnimated(dialog, panel) }
        }

        show(dialog, panel, palette, focus = null)
    }

    /** Shared skin material, so the anchored menus match the dialogs. */
    internal fun paletteOf(activity: AppCompatActivity): Palette = Palette.of(activity)

    // ---------------------------------------------------------------------------------------------

    private fun inflatePanel(activity: AppCompatActivity, palette: Palette): View {
        val panel = LayoutInflater.from(activity).inflate(R.layout.dialog_glass_input, null)
        val density = activity.resources.displayMetrics.density
        panel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = palette.radiusDp * density
            setColor(palette.fill)
            if (palette.strokeWidthDp > 0f) {
                setStroke((palette.strokeWidthDp * density).roundToInt().coerceAtLeast(1), palette.stroke)
            }
        }
        return panel
    }

    private fun createDialog(activity: AppCompatActivity, panel: View, palette: Palette): Dialog {
        val dialog = Dialog(activity)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(palette.dim)
            setGravity(Gravity.CENTER)
            val screenWidth = activity.resources.displayMetrics.widthPixels
            val maxWidth = (MAX_WIDTH_DP * activity.resources.displayMetrics.density).roundToInt()
            setLayout(
                minOf((screenWidth * 0.88f).roundToInt(), maxWidth),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        return dialog
    }

    private fun show(dialog: Dialog, panel: View, palette: Palette, focus: EditText?) {
        dialog.setOnShowListener {
            panel.alpha = 0f
            panel.scaleX = 0.97f
            panel.scaleY = 0.97f
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
        }
        dialog.window?.setSoftInputMode(
            if (focus != null) {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }
        )
        dialog.show()
        // Re-applying the dim keeps the scrim consistent across skins; the window default is 0.5.
        dialog.window?.setDimAmount(palette.dim)
        if (focus != null) {
            // The dialog window only takes focus once it is on screen, so the field asks for it
            // after show() rather than inside onShow.
            focus.post {
                focus.requestFocus()
                focus.setSelection(focus.text?.length ?: 0)
                val ime = focus.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ime?.showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun dismissAnimated(dialog: Dialog, panel: View) {
        panel.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(110)
            .withEndAction { if (dialog.isShowing) dialog.dismiss() }
            .start()
    }

    private fun paintRule(rule: View, palette: Palette, focused: Boolean) {
        val density = rule.resources.displayMetrics.density
        rule.background = GradientDrawable().apply {
            setColor(if (focused) palette.accent else palette.divider)
            cornerRadius = density
        }
    }

    private fun attrColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return Color.GRAY
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    /** Surface colours pulled from the active theme, plus the per-skin plate parameters. */
    internal class Palette(
        val fill: Int,
        val stroke: Int,
        val strokeWidthDp: Float,
        val radiusDp: Float,
        val dim: Float,
        val onSurface: Int,
        val secondary: Int,
        val accent: Int,
        val divider: Int,
        val error: Int
    ) {
        companion object {
            fun of(activity: AppCompatActivity): Palette {
                val skin = SkinPreferences(activity).skin
                val onSurface = attrColor(activity, android.R.attr.textColorPrimary)
                val secondary = attrColor(activity, android.R.attr.textColorSecondary)
                val accent = attrColor(activity, android.R.attr.colorAccent)
                val divider = attrColor(activity, R.attr.agwDivider)
                val border = attrColor(activity, R.attr.agwCardBorder)
                val error = attrColor(activity, com.google.android.material.R.attr.colorError)
                return when (skin) {
                    // Glass: enough body to read text over the moving ribbon, still translucent.
                    Skin.SILK -> Palette(
                        fill = 0xF2141B29.toInt(),
                        stroke = 0x21FFFFFF,
                        strokeWidthDp = 1f,
                        radiusDp = 18f,
                        dim = 0.42f,
                        onSurface = onSurface,
                        secondary = secondary,
                        accent = accent,
                        divider = divider,
                        error = error
                    )
                    Skin.SKETCH -> Palette(
                        fill = 0xFFF7F1DE.toInt(),
                        stroke = onSurface,
                        strokeWidthDp = 2.4f,
                        radiusDp = 12f,
                        dim = 0.30f,
                        onSurface = onSurface,
                        secondary = secondary,
                        accent = accent,
                        divider = divider,
                        error = error
                    )
                    Skin.MIN_DARK -> Palette(
                        fill = 0xFF17191E.toInt(),
                        stroke = border,
                        strokeWidthDp = 1f,
                        radiusDp = 18f,
                        dim = 0.45f,
                        onSurface = onSurface,
                        secondary = secondary,
                        accent = accent,
                        divider = divider,
                        error = error
                    )
                    Skin.MIN_LIGHT -> Palette(
                        fill = 0xFFFBFBFC.toInt(),
                        stroke = border,
                        strokeWidthDp = 1f,
                        radiusDp = 18f,
                        dim = 0.32f,
                        onSurface = onSurface,
                        secondary = secondary,
                        accent = accent,
                        divider = divider,
                        error = error
                    )
                }
            }
        }
    }
}
