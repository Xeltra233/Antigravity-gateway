package org.helix.gateway

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.helix.gateway.ui.ChoiceRowView
import org.helix.gateway.ui.SegmentRowView
import org.helix.gateway.ui.ToggleRowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The routing page and the two pages behind it.
 *
 * What is checked here is the layout itself: that the two entries lead to two different pages, that
 * each page owns its own rows, and that the rows are the hand-painted ones rather than the Material
 * widgets the old settings dialog used. The tile-to-page wiring lives in `MainActivity` and needs a
 * device to exercise, which this environment cannot provide.
 */
@RunWith(RobolectricTestRunner::class)
class RoutingPagesTest {

    private fun inflate(): View {
        val themed = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_HelixGateway)
        return LayoutInflater.from(themed).inflate(R.layout.activity_main, null, false)
    }

    @Test
    fun `the two entries open separate full-page overlays`() {
        val root = inflate()
        val guardPage = root.findViewById<View>(R.id.sheetGuardPage)
        val reportPage = root.findViewById<View>(R.id.sheetReportPage)

        assertNotNull(guardPage)
        assertNotNull(reportPage)
        assertNotSame(guardPage, reportPage)
        assertEquals(View.GONE, guardPage.visibility)
        assertEquals(View.GONE, reportPage.visibility)

        assertEquals("card", root.findViewById<View>(R.id.entryGuardLevel).tag)
        assertEquals("card", root.findViewById<View>(R.id.entryReport).tag)
    }

    @Test
    fun `the guard page owns the level detail and scope rows`() {
        val root = inflate()

        listOf(R.id.rowLevelOff, R.id.rowLevelWire, R.id.rowLevelStandard, R.id.rowLevelDeep)
            .forEach { assertTrue("$it is not a ChoiceRowView", root.findViewById<View>(it) is ChoiceRowView) }
        listOf(R.id.swWireEscape, R.id.swScrub, R.id.swSplit, R.id.swDemotePrefill)
            .forEach { assertTrue("$it is not a ToggleRowView", root.findViewById<View>(it) is ToggleRowView) }
        listOf(R.id.segScrubScope, R.id.segSeparator)
            .forEach { assertTrue("$it is not a SegmentRowView", root.findViewById<View>(it) is SegmentRowView) }
    }

    @Test
    fun `the report page owns its own rows and nothing of the guard page`() {
        val root = inflate()

        listOf(R.id.rowReportOff, R.id.rowReportHeaders, R.id.rowReportBody, R.id.rowReportBoth)
            .forEach { assertTrue("$it is not a ChoiceRowView", root.findViewById<View>(it) is ChoiceRowView) }
        assertNotNull(root.findViewById<View>(R.id.etReportKey))

        val reportPage = root.findViewById<View>(R.id.sheetReportPage)
        assertEquals(0, countWithin(reportPage) { it is ToggleRowView })
        assertEquals(0, countWithin(reportPage) { it is SegmentRowView })
    }

    @Test
    fun `neither page falls back to a Material widget`() {
        val root = inflate()

        for (pageId in listOf(R.id.sheetGuardPage, R.id.sheetReportPage)) {
            val page = root.findViewById<View>(pageId)
            val foreign = countWithin(page) { view ->
                view.javaClass.name.startsWith("com.google.android.material") ||
                    view.javaClass.name.endsWith("SwitchCompat")
            }
            assertEquals("$pageId still hosts a Material widget", 0, foreign)
        }
    }

    @Test
    fun `every row reports the tap it received`() {
        val root = inflate()

        val levelRow = root.findViewById<ChoiceRowView>(R.id.rowLevelDeep)
        var levelTaps = 0
        levelRow.setOnClickListener { levelTaps++ }
        levelRow.performClick()
        assertEquals(1, levelTaps)
        levelRow.isChosen = true
        assertTrue(levelRow.isChosen)

        val toggle = root.findViewById<ToggleRowView>(R.id.swSplit)
        var toggleTaps = 0
        toggle.setOnClickListener { toggleTaps++ }
        toggle.performClick()
        assertEquals(1, toggleTaps)

        // The segment's cells are private; the track is the row's second child and each half is a
        // cell, so the second cell is the second way to answer the caption.
        val segment = root.findViewById<SegmentRowView>(R.id.segSeparator)
        var chosen = -1
        segment.onChoice = { chosen = it }
        (segment.getChildAt(1) as ViewGroup).getChildAt(1).performClick()
        assertEquals(1, chosen)
        assertEquals(1, segment.value)
    }

    private fun countWithin(root: View?, predicate: (View) -> Boolean): Int {
        if (root == null) return 0
        var count = if (predicate(root)) 1 else 0
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                count += countWithin(root.getChildAt(index), predicate)
            }
        }
        return count
    }
}
