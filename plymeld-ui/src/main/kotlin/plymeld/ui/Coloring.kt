package plymeld.ui

import java.util.Locale

/** What the toolpaths are colored by (the preview's color schemes). */
enum class ColorBy(val label: String, val unit: String) {
    FEATURE("Feature", ""), TOOL("Tool", ""), SPEED("Speed", "mm/s"), WIDTH("Line width", "mm"), HEIGHT("Layer height", "mm"), FAN("Fan", "%"),
}

/**
 * A color scheme's key: named swatches (feature, tool) or a ramp from [lo] to [hi] (the rest). [unknown]: segments the
 * file gives no value for (drawn gray), so a missing width or height is never shown as a value.
 */
class Legend(val by: ColorBy, val swatches: List<Pair<String, Int>> = emptyList(), val lo: Double = 0.0, val hi: Double = 0.0, val unknown: Int = 0) {
    val isRamp: Boolean get() = swatches.isEmpty() && by != ColorBy.FEATURE && by != ColorBy.TOOL
    fun format(v: Double): String = when (by) {
        ColorBy.SPEED, ColorBy.FAN -> "%.0f".format(Locale.ROOT, v)
        else -> "%.2f".format(Locale.ROOT, v)
    }
}

object Coloring {
    /** Missing values (no `;WIDTH:` in the file, say). */
    const val GRAY = 0x9AA0A6

    /** Each segment's color (RGB) under [by], and the legend. */
    fun colors(p: Toolpaths, by: ColorBy): Pair<IntArray, Legend> {
        val n = p.segments
        return when (by) {
            ColorBy.FEATURE -> IntArray(n) { Palette.rgb(p.features[p.feature[it].toInt()]) } to
                Legend(by, p.features.indices.filter { i -> p.features[i].isNotEmpty() && p.filamentByFeature[i] > 0 }.map { p.features[it] to Palette.rgb(p.features[it]) })
            ColorBy.TOOL -> IntArray(n) { TOOLS[(p.tool[it].toInt() and 0xFF) % TOOLS.size] } to
                // the file's T number counts from 0; people count tools from 1
                Legend(by, p.tools.map { "Tool ${it + 1} (T$it)" to TOOLS[it % TOOLS.size] })
            else -> {
                val v = FloatArray(n) { s -> when (by) {
                    ColorBy.SPEED -> p.speed[s]; ColorBy.WIDTH -> p.width[s]; ColorBy.HEIGHT -> p.height[s]
                    else -> (p.fan[s].toInt() and 0xFF) * 100f / 255f } }
                var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var unknown = 0
                for (x in v) if (x.isNaN()) unknown++ else { if (x < lo) lo = x; if (x > hi) hi = x }
                if (lo > hi) { lo = 0f; hi = 0f }
                val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
                IntArray(n) { if (v[it].isNaN()) GRAY else ramp(((v[it] - lo) / span).toDouble()) } to Legend(by, lo = lo.toDouble(), hi = hi.toDouble(), unknown = unknown)
            }
        }
    }

    /**
     * Low to high: blue, cyan, green, yellow, red. plymeld's choice, the familiar "cold to hot" of slicer
     * previews; to be checked against a color-vision-deficiency palette later.
     */
    fun ramp(t: Double): Int {
        val stops = intArrayOf(0x2B59C3, 0x1FB5C4, 0x3CB44B, 0xF2C12E, 0xE03B2F)
        val x = t.coerceIn(0.0, 1.0) * (stops.size - 1)
        val i = minOf(stops.size - 2, x.toInt()); val f = x - i
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(stops[i], s) + (ch(stops[i + 1], s) - ch(stops[i], s)) * f).toInt()
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Tools in order: distinct hues. plymeld's choice; a filament's own color, when a config gives it, should win. */
    private val TOOLS = intArrayOf(0xE8743B, 0x2E86DE, 0x20BF6B, 0xA55EEA, 0xF7B731, 0xEB3B5A, 0x0FB9B1, 0x778CA3)
}
