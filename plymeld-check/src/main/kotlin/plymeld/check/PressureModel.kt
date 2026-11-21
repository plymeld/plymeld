package plymeld.check

/**
 * The virtual printer's extruder under pressure, for reading a pressure-advance plate in software before it is printed.
 *
 * The melt behind the nozzle is modelled as a spring: the plastic leaving the nozzle follows what the extruder pushes with a
 * first-order lag whose time constant is the TRUE pressure advance K (seconds), and the firmware's pressure advance runs the
 * extruder ahead by the value SET (its push is the nominal flow plus the set value times the nominal flow's rate of change,
 * Klipper's model). Where the two are equal the plastic laid is exactly what the g-code asks for; where they differ by dK, a
 * step in flow of dQ leaves dK x dQ too little (set short) or too much (set long) just after it, spread over the lag.
 * Klipper's pressure-advance smoothing is not modelled.
 *
 * A file is read as the firmware runs it: each run of printing moves (from a stop to the next stop: a travel, a retraction,
 * a Z move) planned with the acceleration in force, trapezoid by trapezoid, a junction between two moves in line taken at
 * the lower of their speeds and any other at rest (a sharp corner's own speed is not modelled), the value set by the
 * machine's pressure-advance command when the run starts. A run's laid volume is kept along its length in bins.
 *
 * With [lag] off the extruder is ideal: no pressure, so nothing for pressure advance to do, and every run is laid exactly as
 * asked (the control a reading must fail on).
 */
class PressureModel(
    /** The true pressure advance (s): the time constant of the lag. */
    val trueK: Double,
    /** False: the ideal extruder, every run laid as asked. */
    val lag: Boolean = true,
    /** The length of the bins a run's laid volume is kept in (mm). */
    val binMm: Double = 0.1,
) {
    init { require(trueK >= 0) { "the true pressure advance is 0 s or more (got $trueK)" } }

    /** A printing move: from (x0, y0) to (x1, y1) mm at [speed] mm/s, laying [area] mm2 of plastic per mm (as the g-code asks). */
    class Move(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val speed: Double, val area: Double) {
        val length: Double get() = Math.hypot(x1 - x0, y1 - y0)
    }

    /** One run of printing moves from rest to rest, in [cell] (the g-code's `; CELL:` label), at [accel] mm/s2 with [setK] s set. */
    class Run(val cell: String, val moves: List<Move>, val accel: Double, val setK: Double) {
        val length: Double get() = moves.sumOf { it.length }
    }

    /** A run as laid: per bin of [binMm] along it, the plastic asked for ([nominal]) and the plastic laid ([laid]), mm3. */
    class Laid(val run: Run, val binMm: Double, val nominal: DoubleArray, val laid: DoubleArray) {
        /**
         * The plastic laid over [fromMm]..[toMm] along the run against what was asked there: (laid - asked) / asked. Below 0
         * the line is thin there (a groove), above 0 fat (a ridge).
         */
        fun deviation(fromMm: Double, toMm: Double): Double {
            val b0 = Math.max(0, Math.floor(fromMm / binMm).toInt()); val b1 = Math.min(nominal.size, Math.ceil(toMm / binMm).toInt())
            var n = 0.0; var l = 0.0
            for (b in b0 until b1) { n += nominal[b]; l += laid[b] }
            require(n > 0) { "nothing asked for between $fromMm and $toMm mm of the run in '${run.cell}'" }
            return (l - n) / n
        }
    }

    /** [run] laid by this extruder. */
    fun lay(run: Run): Laid {
        val bins = Math.max(1, Math.ceil(run.length / binMm).toInt() + 1)
        val nominal = DoubleArray(bins); val laid = DoubleArray(bins)
        val a = run.accel
        require(a > 0) { "a run's acceleration is above 0 (got $a)" }
        val n = run.moves.size
        // junction speeds: at rest at both ends; between two moves in line the lower speed, else at rest
        val j = DoubleArray(n + 1)
        for (k in 1 until n) {
            val p = run.moves[k - 1]; val q = run.moves[k]
            val cross = (p.x1 - p.x0) * (q.y1 - q.y0) - (p.y1 - p.y0) * (q.x1 - q.x0)
            val dot = (p.x1 - p.x0) * (q.x1 - q.x0) + (p.y1 - p.y0) * (q.y1 - q.y0)
            j[k] = if (Math.abs(cross) <= 1e-9 * p.length * q.length && dot > 0) Math.min(p.speed, q.speed) else 0.0
        }
        for (k in n - 1 downTo 0) j[k] = Math.min(j[k], Math.sqrt(j[k + 1] * j[k + 1] + 2 * a * run.moves[k].length))
        for (k in 0 until n) j[k + 1] = Math.min(j[k + 1], Math.sqrt(j[k] * j[k] + 2 * a * run.moves[k].length))
        var s = 0.0           // mm along the run
        var d = 0.0           // the plastic leaving the nozzle less what is asked (mm3/s)
        var area = 0.0
        val kt = trueK; val ks = run.setK
        fun bin(at: Double) = Math.min(bins - 1, Math.max(0, (at / binMm).toInt()))
        /** A stretch of [t] s starting at speed [v0] with acceleration [g] (mm/s2), laying [area] per mm. */
        fun phase(t: Double, v0: Double, g: Double) {
            if (t <= 0) return
            val steps = Math.max(1, Math.ceil(t / STEP_S).toInt()); val dt = t / steps
            // the asked flow's rate of change in this stretch, and where the lag takes the difference
            val target = (ks - kt) * g * area
            for (i in 0 until steps) {
                val vm = v0 + g * (i + 0.5) * dt
                val ds = vm * dt
                val mid = s + ds / 2
                nominal[bin(mid)] += area * ds
                if (lag) {
                    val d1 = if (kt > 0) target + (d - target) * Math.exp(-dt / kt) else target
                    // the difference over the step, exactly for a stretch of one acceleration (an exponential's mean)
                    val mean = if (kt > 0 && d1 != d) target + (d - target) * kt / dt * (1 - Math.exp(-dt / kt)) else (d + d1) / 2
                    laid[bin(mid)] += area * ds + mean * dt
                    d = d1
                } else laid[bin(mid)] += area * ds
                s += ds
            }
        }
        for ((k, m) in run.moves.withIndex()) {
            val u = j[k]; val w = j[k + 1]
            // a change of area at a junction taken at speed u: the asked flow steps by u x the change, which the set value
            // pushes at once and the lag takes in over time
            if (lag && m.area != area && u > 0) {
                val dq = u * (m.area - area)
                if (kt > 0) d += -dq + ks * dq / kt else laid[bin(s)] += ks * dq
            }
            area = m.area
            val c = Math.min(m.speed, Math.sqrt((2 * a * m.length + u * u + w * w) / 2))
            val up = (c * c - u * u) / (2 * a); val down = (c * c - w * w) / (2 * a)
            val cruise = Math.max(0.0, m.length - up - down)
            phase((c - u) / a, u, a)
            if (cruise > 0) phase(cruise / c, c, 0.0)
            phase((c - w) / a, c, -a)
        }
        // stopped: what is left of the difference leaves the nozzle where the run ended
        if (lag && kt > 0) laid[bin(s)] += d * kt
        return Laid(run, binMm, nominal, laid)
    }

    companion object {
        /** The longest time step (s) a stretch is laid in. */
        const val STEP_S = 1e-4

        /**
         * A step wall's two readings by a model of true value [trueK] with [setK] set: the plastic over [readMm] after the
         * speed-up mark (the middle's start) and after the slow-down mark (where the head starts to slow before the tail),
         * each against what was asked ([Laid.deviation]). The wall: [leadMm] at [slow] mm/s, [fastMm] at [fast], [tailMm] at
         * [slow], at [accel] mm/s², laying [area] mm² per mm.
         */
        fun stepWall(trueK: Double, setK: Double, slow: Double, fast: Double, accel: Double, leadMm: Double, fastMm: Double, tailMm: Double, area: Double, readMm: Double): Pair<Double, Double> {
            val y1 = leadMm; val y2 = leadMm + fastMm; val y3 = y2 + tailMm
            val run = Run("wall", listOf(Move(0.0, 0.0, 0.0, y1, slow, area), Move(0.0, y1, 0.0, y2, fast, area), Move(0.0, y2, 0.0, y3, slow, area)), accel, setK)
            val laid = PressureModel(trueK).lay(run)
            val down = y2 - (fast * fast - slow * slow) / (2 * accel)
            return laid.deviation(y1, y1 + readMm) to laid.deviation(down, down + readMm)
        }

        /**
         * The largest true pressure advance (s, at most [maxK]) for which such a wall's slow-down mark reads the opposite of its
         * speed-up mark, at a set value half and one and a half times it: where the pressure settles within the middle. Above
         * it the two marks may read alike (the speed-up mark still flips at the true value).
         */
        /**
         * The stretches of a step wall (mm: its lead-in, middle, and tail) long enough for the pressure to settle within them up
         * to a true value of [maxK] s ([settlesUpTo] reaches it): the shortest lead-in [leadMinMm] and middle [fastMinMm]
         * grown together, in the same proportion, a whole millimeter at a time; the tail [tailMm] as it is. Null when even
         * [limitMm] of lead-in and middle together does not reach it.
         */
        fun stretchesFor(slow: Double, fast: Double, accel: Double, area: Double, readMm: Double, maxK: Double,
                         leadMinMm: Double, fastMinMm: Double, tailMm: Double, limitMm: Double): Triple<Double, Double, Double>? {
            fun at(scale: Double) = Triple(Math.ceil(leadMinMm * scale), Math.ceil(fastMinMm * scale), tailMm)
            fun settles(t: Triple<Double, Double, Double>) = opposite(maxK, slow, fast, accel, t.first, t.second, t.third, area, readMm)
            if (settles(at(1.0))) return at(1.0)
            val maxScale = limitMm / (leadMinMm + fastMinMm)
            if (maxScale <= 1 || !settles(at(maxScale))) return null
            var lo = 1.0; var hi = maxScale
            repeat(30) { val m = (lo + hi) / 2; if (settles(at(m))) hi = m else lo = m }
            return at(hi)
        }

        /** Whether a step wall's slow-down mark reads opposite to its speed-up mark at a true value of [k], set half and one and a half times it. */
        fun opposite(k: Double, slow: Double, fast: Double, accel: Double, leadMm: Double, fastMm: Double, tailMm: Double, area: Double, readMm: Double): Boolean =
            listOf(0.5 * k, 1.5 * k).all { ks -> val (u, d) = stepWall(k, ks, slow, fast, accel, leadMm, fastMm, tailMm, area, readMm); u * d < 0 }

        fun settlesUpTo(slow: Double, fast: Double, accel: Double, leadMm: Double, fastMm: Double, tailMm: Double, area: Double, readMm: Double, maxK: Double): Double {
            fun ok(k: Double) = opposite(k, slow, fast, accel, leadMm, fastMm, tailMm, area, readMm)
            if (ok(maxK)) return maxK
            if (!ok(1e-4)) return 0.0
            var lo = 1e-4; var hi = maxK
            repeat(30) { val m = Math.sqrt(lo * hi); if (ok(m)) lo = m else hi = m }
            return lo
        }

        /**
         * The runs of printing moves in [gcode] inside cells [cells] picks (their `; CELL: <label>`), with the pressure advance
         * the machine's own command ([command], its value group in [unit], "s" or "ms") last set before each, and the
         * acceleration last set (M204 S, SET_VELOCITY_LIMIT ACCEL=); filament [filamentDiameterMm] thick. A run ends at any move that
         * does not print (a travel, a retraction or its restore, a Z move) and at a cell's end.
         */
        fun runs(gcode: String, command: Regex, unit: String, filamentDiameterMm: Double, cells: (String) -> Boolean): List<Run> {
            val filArea = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
            val out = ArrayList<Run>()
            var x = Double.NaN; var y = Double.NaN; var f = 0.0; var accel = Double.NaN; var pa = 0.0
            var cell: String? = null
            var moves = ArrayList<Move>(); var runAccel = Double.NaN; var runK = 0.0
            fun close() { if (moves.isNotEmpty()) { out += Run(cell ?: "", moves, runAccel, runK); moves = ArrayList() } }
            for (raw in gcode.lineSequence()) {
                val l = raw.trim()
                if (l.startsWith("; CELL: ")) { close(); cell = l.removePrefix("; CELL: ").trim(); continue }
                if (l == "; CELL END") { close(); cell = null; continue }
                val c = l.substringBefore(';').trim()
                if (c.isEmpty()) continue
                command.matchEntire(c)?.let { m -> close(); pa = m.groupValues[1].toDouble().let { if (unit == "ms") it / 1000 else it } }
                val w = c.split(Regex("\\s+"))
                fun num(ch: Char) = w.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
                when (w[0]) {
                    "M204" -> num('S')?.let { accel = it }
                    "SET_VELOCITY_LIMIT" -> w.firstOrNull { it.startsWith("ACCEL=") }?.removePrefix("ACCEL=")?.toDoubleOrNull()?.let { accel = it }
                    "G0", "G1" -> {
                        num('F')?.let { f = it / 60 }
                        val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0
                        val xy = !x.isNaN() && (nx != x || ny != y)
                        val printing = xy && e > 0 && num('Z') == null && cell != null && cells(cell)
                        if (printing) {
                            if (moves.isEmpty()) { runAccel = accel; runK = pa }
                            val len = Math.hypot(nx - x, ny - y)
                            moves += Move(x, y, nx, ny, f, e * filArea / len)
                        } else if (xy || e != 0.0 || num('Z') != null) close()
                        x = nx; y = ny
                    }
                    else -> {}
                }
            }
            close()
            return out
        }
    }
}
