package org.helix.gateway

import android.os.Looper
import android.view.View
import android.widget.TextView
import org.helix.gateway.ui.ChoiceRowView
import org.helix.gateway.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The wiring between the routing page and the two pages behind it, driven through the activity.
 *
 * `RoutingPagesTest` checks that the layout holds two separate pages; this checks that a tap
 * actually reaches the page it belongs to and that tapping a row repaints the entry it came from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class RoutingWiringTest {

    private fun launch(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    @Test
    fun `the level entry opens the guard page only`() {
        val activity = launch()

        activity.findViewById<View>(R.id.entryGuardLevel).performClick()
        activity.findViewById<View>(R.id.entryGuardLevel).performClick()
        // The content behind the overlay fades out first, so it only leaves the tree once the
        // transition has run.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.sheetGuardPage).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.sheetReportPage).visibility)
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.layoutContentRoot).visibility)
    }

    @Test
    fun `the report entry opens the report page only`() {
        val activity = launch()

        activity.findViewById<View>(R.id.entryReport).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.sheetReportPage).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.sheetGuardPage).visibility)
    }

    @Test
    fun `a tapped level moves the chooser and the entry above it`() {
        val activity = launch()
        activity.findViewById<View>(R.id.entryGuardLevel).performClick()

        activity.findViewById<View>(R.id.rowLevelWire).performClick()

        assertTrue(activity.findViewById<ChoiceRowView>(R.id.rowLevelWire).isChosen)
        assertFalse(activity.findViewById<ChoiceRowView>(R.id.rowLevelOff).isChosen)
        assertEquals(
            activity.getString(R.string.settings_guard_level_wire),
            activity.findViewById<TextView>(R.id.tvRowGuardLevel).text.toString()
        )
    }

    @Test
    fun `back closes the open page before it leaves the app`() {
        val activity = launch()
        activity.findViewById<View>(R.id.entryReport).performClick()

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        assertEquals(View.GONE, activity.findViewById<View>(R.id.sheetReportPage).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.layoutContentRoot).visibility)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `closing a page mid transition restores what is underneath`() {
        // Cancelling the fade-out must not leave the content behind the overlay at zero alpha:
        // the page would come back blank.
        val activity = launch()
        val root = activity.findViewById<View>(R.id.layoutContentRoot)

        activity.findViewById<View>(R.id.entryGuardLevel).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        activity.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        assertEquals(View.GONE, activity.findViewById<View>(R.id.sheetGuardPage).visibility)
        assertEquals(View.VISIBLE, root.visibility)
        assertEquals(1f, root.alpha, 0.001f)
    }

    @Test
    fun `switching tabs crossfades instead of cutting`() {
        // Both a tab switch and an overlay page replace what is on screen, so both must fade the
        // old page out. A hard cut under the incoming fade is what made the two feel unrelated.
        val activity = launch()

        val incoming = activity.findViewById<View>(R.id.pageRouting)
        val outgoing = activity.findViewById<View>(R.id.pageGateway)

        activity.findViewById<View>(R.id.tabRoute).performClick()

        // Dropped on the first frame means a hard cut; the old page may only leave once its fade
        // has run, which is the same rule the overlay pages follow.
        assertEquals("the outgoing page must not vanish on the first frame", View.VISIBLE, outgoing.visibility)
        assertEquals(View.VISIBLE, incoming.visibility)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(View.GONE, outgoing.visibility)
        assertEquals(1f, outgoing.alpha, 0.001f)
        assertEquals(1f, incoming.alpha, 0.001f)
        assertEquals(0f, incoming.translationX, 0.001f)
    }

    @Test
    fun `every overlay page tints its back arrow with the skin`() {
        // The arrow drawable strokes itself black. On the dark skins an untinted copy is invisible,
        // which is how both new pages ended up with no visible way back.
        val activity = launch()

        for (id in intArrayOf(R.id.btnGuardPageBack, R.id.btnReportPageBack, R.id.btnProviderDetailBack)) {
            val arrow = activity.findViewById<android.widget.ImageButton>(id)
            assertTrue(
                "back arrow ${activity.resources.getResourceEntryName(id)} paints black without the skin tint",
                arrow.imageTintList != null
            )
        }
    }

    @Test
    fun `a fresh install shows the empty provider page and nothing else`() {
        // The provider list belongs to the user, so a clean install has no row at all: the label is
        // what keeps the page from looking broken, and it must survive the empty list.
        val activity = launch()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tvProviderEmpty).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.recyclerProviders).visibility)
        assertEquals("还没有供应商", activity.getString(R.string.provider_empty))
    }
}
