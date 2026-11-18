package org.helix.gateway

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.helix.gateway.data.AntiRouteLevel
import org.helix.gateway.data.ModelReportMode
import org.helix.gateway.data.Skin
import org.helix.gateway.data.SkinPreferences
import org.helix.gateway.ui.ChoiceRowView
import org.helix.gateway.ui.SegmentRowView
import org.helix.gateway.ui.SketchCardDrawable
import org.helix.gateway.ui.ToggleRowView
import org.helix.gateway.ui.levelMeter
import org.helix.gateway.ui.reportGlyph
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Draws the routing page and the two pages behind it on all four skins into `build/visual/`.
 *
 * This exists because no emulator runs here: it turns the layout into actual pixels so the screens
 * can be looked at instead of only reasoned about. The state each screen shows is set by the test,
 * since the activity is what normally pushes it in, and the only assertion is that a screen paints
 * more than a blank rectangle — the pictures themselves are the evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutingPageRenderTest {

    private val outDir = File("build/visual")

    @Test
    fun `the routing entries paint on every skin`() {
        for (skin in Skin.entries) {
            val context = themedContext(skin)
            val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false)
            root.findViewById<View>(R.id.pageGateway).visibility = View.GONE
            root.findViewById<View>(R.id.pageRouting).visibility = View.VISIBLE
            val density = context.resources.displayMetrics.density
            val accent = resolve(context, android.R.attr.colorAccent)
            val rule = resolve(context, R.attr.agwChevron)
            root.findViewById<View>(R.id.glyphGuardLevel).background =
                levelMeter(density, AntiRouteLevel.STANDARD, accent, rule)
            root.findViewById<View>(R.id.glyphReport).background =
                reportGlyph(density, ModelReportMode.BOTH, accent, rule, stroke(skin))
            root.findViewById<TextView>(R.id.tvRowGuardLevel).text =
                context.getString(R.string.settings_guard_level_standard)
            root.findViewById<TextView>(R.id.tvRowReport).text =
                context.getString(R.string.settings_model_report_both)
            paintShellCards(root, skin)

            val bitmap = render(root, outDir.resolve("routing-${skin.name.lowercase()}.png"))
            assertPainted(bitmap, "routing-${skin.name}")
        }
    }

    @Test
    fun `the guard page paints on every skin`() {
        for (skin in Skin.entries) {
            val root = inflate(skin, R.id.sheetGuardPage)
            root.findViewById<ChoiceRowView>(R.id.rowLevelStandard).isChosen = true
            root.findViewById<ToggleRowView>(R.id.swWireEscape).isChecked = true
            root.findViewById<ToggleRowView>(R.id.swScrub).isChecked = true
            root.findViewById<ToggleRowView>(R.id.swDemotePrefill).isChecked = true
            root.findViewById<SegmentRowView>(R.id.segSeparator).value = 1
            root.findViewById<View>(R.id.tvGuardRestartNotice).visibility = View.VISIBLE

            val bitmap = render(root, outDir.resolve("guard-${skin.name.lowercase()}.png"))
            assertPainted(bitmap, "guard-${skin.name}")
        }
    }

    @Test
    fun `the report page paints on every skin`() {
        for (skin in Skin.entries) {
            val root = inflate(skin, R.id.sheetReportPage)
            root.findViewById<ChoiceRowView>(R.id.rowReportBoth).isChosen = true
            root.findViewById<TextView>(R.id.etReportKey).text = "x-model"
            root.findViewById<View>(R.id.tvReportRestartNotice).visibility = View.VISIBLE

            val bitmap = render(root, outDir.resolve("report-${skin.name.lowercase()}.png"))
            assertPainted(bitmap, "report-${skin.name}")
        }
    }

    @Test
    fun `the two entry glyphs paint every state`() {
        for (skin in Skin.entries) {
            val context = themedContext(skin)
            val accent = resolve(context, android.R.attr.colorAccent)
            val rule = resolve(context, R.attr.agwChevron)
            val density = context.resources.displayMetrics.density

            val cell = (44 * density).toInt()
            val bitmap = Bitmap.createBitmap(cell * 6, cell, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (skin == Skin.MIN_LIGHT) 0xFFFFFFFF.toInt() else 0xFF101013.toInt())
            for (level in AntiRouteLevel.entries) {
                val glyph = levelMeter(density, level, accent, rule)
                glyph.setBounds(level.ordinal * cell, 0, level.ordinal * cell + cell, cell)
                glyph.draw(canvas)
            }
            for (mode in listOf(ModelReportMode.OFF, ModelReportMode.BOTH)) {
                val glyph = reportGlyph(density, mode, accent, rule, stroke(skin))
                val left = (4 + mode.ordinal.coerceAtMost(1)) * cell
                glyph.setBounds(left, 0, left + cell, cell)
                glyph.draw(canvas)
            }

            write(bitmap, outDir.resolve("glyphs-${skin.name.lowercase()}.png"))
            assertPainted(bitmap, "glyphs-${skin.name}")
        }
    }

    private fun stroke(skin: Skin): Float = if (skin == Skin.SKETCH) 2.2f else 1.6f

    private fun themedContext(skin: Skin): ContextThemeWrapper {
        SkinPreferences(RuntimeEnvironment.getApplication()).skin = skin
        return ContextThemeWrapper(RuntimeEnvironment.getApplication(), skin.themeRes)
    }

    private fun inflate(skin: Skin, pageId: Int): View {
        val root = LayoutInflater.from(themedContext(skin)).inflate(R.layout.activity_main, null, false)
        root.findViewById<View>(R.id.layoutContentRoot).visibility = View.INVISIBLE
        root.findViewById<View>(pageId).visibility = View.VISIBLE
        paintShellCards(root, skin)
        return root
    }

    /**
     * The one surface the XML cannot paint by itself: the paper card the sketch skin puts behind a
     * `card`-tagged block is applied by `MainActivity` at runtime, so the preview applies it here.
     */
    private fun paintShellCards(root: View, skin: Skin) {
        if (skin != Skin.SKETCH) return
        val density = root.resources.displayMetrics.density
        each(root) { view -> if (view.tag == "card") view.background = SketchCardDrawable(density) }
    }

    private fun each(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) each(view.getChildAt(index), action)
        }
    }

    private fun render(root: View, target: File): Bitmap {
        // The qualifier is w411dp-h891dp-xxhdpi, so draw at the real device size: measuring at half
        // of it would squeeze the rows and make the preview lie about what fits.
        val density = root.resources.displayMetrics.density
        val width = (411 * density).toInt()
        val height = (891 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        write(bitmap, target)
        return bitmap
    }

    private fun write(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun resolve(context: Context, attr: Int): Int {
        val typed = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            typed.getColor(0, 0)
        } finally {
            typed.recycle()
        }
    }

    private fun assertPainted(bitmap: Bitmap, name: String) {
        val colors = HashSet<Int>()
        var y = 0
        while (y < bitmap.height && colors.size <= 40) {
            var x = 0
            while (x < bitmap.width && colors.size <= 40) {
                colors.add(bitmap.getPixel(x, y))
                x += 5
            }
            y += 5
        }
        assertTrue("$name painted only ${colors.size} distinct colours", colors.size > 40)
    }
}
