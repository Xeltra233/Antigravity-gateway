package org.helix.gateway.ui

/**
 * Single source of truth for the sketch skin.
 *
 * Every number here is read off the reference page rather than tuned by eye, so the outline, the
 * paper and the crayon all come from one palette. Changing the look means changing this file, not
 * hunting for literals in five drawables.
 */
object SketchSpec {

    // ---- Paper ------------------------------------------------------------------------------

    /** Card stock; the page behind it is darker so the cards sit up. */
    const val PAPER = 0xFFF6EFE2.toInt()

    const val PAGE = 0xFFE4DCCB.toInt()

    /** Splash tones: alternate warm dark and warm light radial gradients over the paper. */
    const val PAPER_MOTTLE_DARK = 0x1ADED0B7
    const val PAPER_MOTTLE_LIGHT = 0x22FFFBF4

    /** Corner shading, a wide inset shadow around the page. */
    const val PAPER_VIGNETTE = 0x3AC4B294.toInt()

    /** Fibres: mostly warm grey, a third of them bright. */
    const val FIBRE_DARK = 0xC6B79E
    const val FIBRE_LIGHT = 0xFFFFFCF5.toInt()

    // ---- Ink --------------------------------------------------------------------------------

    /** Main stroke. */
    const val INK = 0xFF393531.toInt()

    /** Second pass: lighter, offset outward. */
    const val INK_SUB = 0xFF7C756C.toInt()

    /** Third pass: thin dashed fly-line. */
    const val INK_FLY = 0xFF968F85.toInt()

    const val INK_SUB_ALPHA = 199
    const val INK_FLY_ALPHA = 140

    // ---- Type -------------------------------------------------------------------------------

    const val TEXT = 0xFF3A3632.toInt()
    const val TEXT_SUB = 0xFF6E685F.toInt()
    const val TEXT_SECTION = 0xFF6B655C.toInt()

    /** Ink bleed under glyphs. */
    const val TEXT_BLEED = 0x2E3A3632

    // ---- Crayon -----------------------------------------------------------------------------

    /** Barely-there blue wash; the paper must still show through. */
    const val PENCIL_TINT = 0x2480A2D4.toInt()

    /** Crayon core tones. */
    val PENCIL_BLUES = intArrayOf(
        0xFF628BC7.toInt(),
        0xFF4F79B8.toInt(),
        0xFF759CD2.toInt(),
        0xFF5A84C2.toInt(),
        0xFF446DA8.toInt(),
        0xFF83A7D8.toInt()
    )

    const val PENCIL_GREEN = 0xFF88B646.toInt()

    /** Paper showing through the crayon. */
    const val PENCIL_VOID = 0xFFF6EFE2.toInt()

    /**
     * Primary button. `docs/sketch-preview.html` paints it as a light blue slab (`#9cbce7`) hatched
     * with white, not as a pigmented crayon block: that is what keeps the ink label readable.
     */
    const val BUTTON_FILL = 0xFFA3C3E9.toInt()
    val BUTTON_HATCH = intArrayOf(
        0x47FFFFFF,
        0x3AFFFFFF,
        0x2EFFFFFF
    )

    /** Heavy crayon pressure. */
    const val PENCIL_PRESS = 0x3A609E

    /** Colour pooling along the edge of a hand-coloured area. */
    const val PENCIL_RIM = 0x47486EAA.toInt()

    // ---- Outline weights (dp) ---------------------------------------------------------------

    const val CARD_MAIN_DP = 2.00f
    const val CARD_SUB_DP = 1.15f
    const val CARD_FLY_DP = 0.78f

    const val BUTTON_MAIN_DP = 2.35f
    const val BUTTON_SUB_DP = 1.35f
    const val BUTTON_FLY_DP = 0.78f

    // ---- Outline placement (dp) -------------------------------------------------------------

    /** The three passes are inset differently, so they run parallel instead of stacking. */
    const val INSET_MAIN_DP = 1.2f
    const val INSET_SUB_DP = 2.5f
    const val INSET_FLY_DP = 0.5f

    const val JITTER_MAIN_DP = 0.55f
    const val JITTER_SUB_DP = 0.95f
    const val JITTER_FLY_DP = 1.15f

    /** Where each straight side bows; wider than the jitter, so the bow is visible. */
    const val SIDE_BOW_DP = 4f
    const val SIDE_BOW_CROSS = 0.8f

    /**
     * Dash pattern for the fly-line. The segments differ in length on purpose: an even pattern
     * would put every gap in the same place and the eye catches the period immediately.
     */
    val FLY_DASH = floatArrayOf(18f, 6f, 35f, 9f)

    // ---- Geometry ---------------------------------------------------------------------------

    const val CARD_RADIUS_DP = 12f
    const val BUTTON_RADIUS_DP = 12f
    const val MODAL_RADIUS_DP = 14f

    /** Crayon hatch angle. */
    const val HATCH_DEGREES = -32.5f

    // ---- Interaction ------------------------------------------------------------------------

    const val ROW_PRESS = 0x17827EC2
    const val SCRIM = 0x522E2922

    /**
     * The generator used by the reference page. Reusing the same constants keeps a rebuild
     * reproducible: the same card always gets the same wobble instead of shimmering between frames.
     */
    class Lcg(seed: Long) {
        private var state = seed and 0xFFFFFFFFL

        fun next(): Float {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return state.toFloat() / 4294967296f
        }

        fun range(min: Float, max: Float): Float = min + next() * (max - min)
    }
}
