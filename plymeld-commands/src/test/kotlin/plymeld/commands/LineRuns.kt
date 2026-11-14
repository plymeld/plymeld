package plymeld.commands

/**
 * The printed lines of a g-code, read back from the file alone (the tests of the caps by length, ShortWallsTest and
 * SteadyShareTest): a line is a run of moves that push plastic one after the other, from where pushing starts to where it
 * stops (a move with no plastic, a retraction, a tool or feature change ends it). Straight moves only: the tests turn arcs
 * off, and an arc is refused rather than misread.
 */
object LineRuns {
    /**
     * One printed line: its feature, the layer it is on (counted from 1), the tool, its length seen from above (mm), the
     * feed (mm/s) of each of its moves, and the lowest acceleration in force while it is laid (mm/s², NaN = none written).
     */
    class Run(val feature: String, val layer: Int, val tool: Int, val lengthMm: Double, val feeds: List<Double>, val accel: Double) {
        val fastest get() = feeds.max()
    }

    fun runs(g: List<String>): List<Run> {
        val out = ArrayList<Run>()
        var x = Double.NaN; var y = Double.NaN; var f = 0.0; var a = Double.NaN; var feature = ""; var layer = 0; var tool = 0
        var len = 0.0; var feeds = ArrayList<Double>(); var lowest = Double.NaN
        fun end() {
            if (feeds.isNotEmpty()) out += Run(feature, layer, tool, len, feeds, lowest)
            len = 0.0; feeds = ArrayList(); lowest = Double.NaN
        }
        for (raw in g) {
            val l = raw.trim()
            when {
                l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER") -> { end(); layer++ }
                l.startsWith(";TYPE:") || l.startsWith("; FEATURE:") -> { val nf = l.substringAfter(':').trim(); if (nf != feature) end(); feature = nf }
                l.startsWith("SET_VELOCITY_LIMIT") && "ACCEL=" in l -> { a = l.substringAfter("ACCEL=").substringBefore(' ').toDouble(); if (feeds.isNotEmpty()) lowest = minOf(lowest.takeIf { !it.isNaN() } ?: a, a) }
                l.startsWith("M204 S") -> { a = l.removePrefix("M204 S").substringBefore(' ').toDouble(); if (feeds.isNotEmpty()) lowest = minOf(lowest.takeIf { !it.isNaN() } ?: a, a) }
                Regex("^T\\d+$").matches(l.substringBefore(';').trim()) -> { end(); tool = l.substringBefore(';').trim().substring(1).toInt() }
                l.startsWith("G2 ") || l.startsWith("G3 ") -> error("arcs are off in these tests: $l")
                l.startsWith("G1 ") -> {
                    val w = l.substringBefore(';').trim().split(' ').drop(1).filter { it.length > 1 }.associate { it[0] to it.substring(1).toDouble() }
                    w['F']?.let { f = it / 60 }
                    val nx = w['X'] ?: x; val ny = w['Y'] ?: y
                    val e = w['E']
                    val moves = !x.isNaN() && (nx != x || ny != y)
                    when {
                        moves && e != null && e > 0 -> {
                            if (feeds.isEmpty()) lowest = a
                            len += Math.hypot(nx - x, ny - y); feeds += f
                        }
                        moves || (e != null && e < 0) || (w['Z'] != null && e == null) -> end()
                    }
                    x = nx; y = ny
                }
            }
        }
        end()
        return out
    }
}
