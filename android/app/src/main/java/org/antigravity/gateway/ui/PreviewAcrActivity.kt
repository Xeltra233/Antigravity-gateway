package org.antigravity.gateway.ui

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import org.antigravity.gateway.R

/**
 * Static rendering harness for [R.layout.preview_acr].
 *
 * It exists so the layout can be pixel-compared against `docs/app-preview.html` in the acr theme
 * before any of it is wired to the gateway. Nothing here reads or writes configuration; the tab
 * buttons only swap which of the three pre-built sections is visible.
 */
class PreviewAcrActivity : AppCompatActivity() {

    private lateinit var backdrop: SilkRibbonView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sampling mode is chosen before the GL surface exists, because the shader is linked in
        // onSurfaceCreated. 0 = panel resolution, 1 = offscreen 420dp buffer, 2/3 = 2x2 / 3x3 weave.
        when (intent.getIntExtra("pvMode", 0)) {
            1 -> { SilkRibbonView.previewDownscale = 0.39f; SilkRibbonView.previewFabricTaps = 1 }
            2 -> { SilkRibbonView.previewDownscale = 1f; SilkRibbonView.previewFabricTaps = 2 }
            3 -> { SilkRibbonView.previewDownscale = 1f; SilkRibbonView.previewFabricTaps = 3 }
            else -> { SilkRibbonView.previewDownscale = 1f; SilkRibbonView.previewFabricTaps = 1 }
        }
        setContentView(R.layout.preview_acr)

        backdrop = findViewById(R.id.pvBackdrop)
        applyShellWidth()
        pages = listOf(
            findViewById(R.id.pvPage0),
            findViewById(R.id.pvPage1),
            findViewById(R.id.pvPage2),
        )

        // The three tab columns are plain containers in the static layout, so selection is driven by
        // index here rather than by any view-model state.
        val tabBar = findViewById<android.view.ViewGroup>(R.id.pvTabs)
        tabs = (0 until tabBar.childCount).map { tabBar.getChildAt(it) }
        tabs.forEachIndexed { index, tab ->
            tab.isClickable = true
            tab.setOnClickListener { selectPage(index) }
        }
        selectPage(0)
    }

    /**
     * Caps the content column at [R.dimen.content_max_width] but never below the screen width, so
     * phones stay full-bleed and only tablets leave side margins. The old fixed 420dp strip left
     * 150dp empty on each side of a 720dp screen.
     */
    private fun applyShellWidth() {
        val shell = findViewById<View>(R.id.pvShell)
        val screen = resources.displayMetrics.widthPixels
        val cap = resources.getDimensionPixelSize(R.dimen.content_max_width)
        val width = minOf(screen, cap)
        if (width >= screen) return
        shell.layoutParams = shell.layoutParams.apply { this.width = width }
    }

    private fun selectPage(index: Int) {
        pages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        // .tab.on uses --tab-on (#eef2fa) and the rest use --tab-off (#7286a6); the static layout
        // ships with tab 0 selected, so the tint has to follow the page here.
        val on = ContextCompat.getColor(this, R.color.pv_tab_on)
        val off = ContextCompat.getColor(this, R.color.pv_tab_off)
        tabs.forEachIndexed { i, tab ->
            val color = if (i == index) on else off
            tintTab(tab, color)
        }
    }

    private fun tintTab(tab: View, color: Int) {
        val group = tab as? android.view.ViewGroup ?: return
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is android.widget.ImageView -> child.imageTintList = android.content.res.ColorStateList.valueOf(color)
                is android.widget.TextView -> child.setTextColor(color)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        backdrop.startBackdrop()
    }

    override fun onPause() {
        backdrop.stopBackdrop()
        super.onPause()
    }
}
