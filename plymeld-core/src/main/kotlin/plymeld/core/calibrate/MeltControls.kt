package plymeld.core.calibrate

/**
 * Edits that make the melt plate's controls (`plymeld calibrate melt` and CalibrationPlateTest) able to fail. The
 * writer lifts over every strip already laid before it travels (KlipperWriter's clearance), so a plate written
 * without its own lifts ("meltnolift") or with its strips thickest first ("meltthickfirst") is lifted by the writer anyway,
 * and the check rightly finds nothing wrong: the control is not caught. The CLI kept the old controls and refused every
 * melt plate for a while; one edit for both now.
 */
object MeltControls {
    /**
     * [text] with the writer's lift into every melt strip taken out: each Z-only move after a strip's cell marker and before
     * its first sideways move. The travel there then runs at the height of the strip it leaves, as the writer did before it
     * cleared what is printed: what the "meltnolift" and "meltthickfirst" controls need to be caught.
     */
    fun withoutStripLifts(text: String): String {
        val out = ArrayList<String>()
        var dropping = false
        for (l in text.lines()) {
            if (STRIP.matches(l)) { dropping = true; out += l; continue }
            if (dropping && l.startsWith("G1 X")) dropping = false
            if (dropping && Z_ONLY.matches(l)) continue
            out += l
        }
        return out.joinToString("\n")
    }

    private val STRIP = Regex("; CELL: melt \\d\\.(fast|mid|slow)\\.\\d")
    private val Z_ONLY = Regex("G1 Z[0-9.]+( F[0-9.]+)?")
}
