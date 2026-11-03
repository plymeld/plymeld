package plymeld.check

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Sample toolpaths for the print techniques' documentation (CONTRIBUTING.md, Engineering rules: "Every print technique's
 * documentation includes a sample toolpath"): panels of g-code drawn side by side by the same code at the same scale, a
 * technique beside the default on the same model, each panel labeled with the settings it was sliced with. Everything
 * is read from the g-code text ([read]), nothing from plymeld's own model.
 *
 * Views ([View]): from above (paths as solid strokes, X across and Y up); from the side (each move drawn as its bead: as
 * wide along the view as it is long plus its declared width, as tall as its declared height under the nozzle, so beads
 * that touch in the print join into one solid line; the moves projected, or cut to a slab across the line of sight, in
 * any direction round Z); and unrolled round a vertical axis (the angle as arc length at the moves' mean radius across,
 * Z up: a round wall laid flat). A [Window] zooms on the region that differs. Colors ([Coloring]): by feature (the
 * colors of [GcodeRender]), print order, part fan, nozzle temperature, declared line height, plastic per mm, or
 * alternate layers. Extrusion only: travels are left out.
 *
 * How the pictures look (sizes, colors, the gradient, strokes drawn [TOP_FILL] of their width and beads [SIDE_FILL] of
 * their height so neighbors read apart, both said under each picture's title) is plymeld's choice, none of
 * it a print's figure.
 */
object TechniqueRender {
    /** One extruding move as the g-code wrote it: mm, its feature, its layer (counted from the file's first layer change). */
    class Move(
        val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double,
        /** Filament (mm) the move feeds. */ val e: Double,
        val feature: String, val layer: Int, val layerZ: Double,
        /** The line width and height the g-code declares (`;WIDTH:` / `;HEIGHT:`), NaN where it declares none. */
        val width: Double, val height: Double,
        /** The part fan in force (0..1, from M106 S / 255) and the nozzle temperature last set (M104 / M109, C; NaN before any). */
        val fan: Double, val temp: Double,
        /** The object it belongs to (`; object start <name>`), null outside one. */ val obj: String?,
        /** Its place among the file's extruding moves. */ val index: Int,
        /** Its feedrate (mm/s). */ val speed: Double,
    ) {
        val length: Double get() = hypot(x1 - x0, y1 - y0)
        /** Filament per mm of path. */
        val ePerMm: Double get() = if (length > 0) e / length else 0.0
    }

    /**
     * A file's extruding moves and its layers' Z (as `;Z:` / `; Z_HEIGHT:` states them), in print order; [pausedBefore]: the
     * index of the first extruding move after each pause command (M0, M1, M25, M600, M601, PAUSE).
     */
    class Toolpath(val moves: List<Move>, val layerZs: List<Double>, val text: String, val pausedBefore: List<Int> = emptyList(),
                   /** The filament diameter the file states (`; filament_diameter:`), NaN where it states none. */ val filamentDiameter: Double = Double.NaN) {
        /**
         * The height of [m]'s bead from its plastic per mm and width (the bead model: a cross-section of t (w - t (1 - pi / 4)),
         * a rectangle with rounded sides), for a bead whose thickness the file does not declare (a hidden core's, laid once
         * for several layers); its declared height where the file states no filament diameter.
         */
        fun beadHeight(m: Move): Double {
            if (filamentDiameter.isNaN() || m.width.isNaN()) return m.height
            val a = m.ePerMm * Math.PI * filamentDiameter * filamentDiameter / 4; val k = 1 - Math.PI / 4; val w = m.width
            val disc = w * w - 4 * k * a
            return if (disc < 0) a / w else (w - kotlin.math.sqrt(disc)) / (2 * k)
        }
        fun layer(i: Int): List<Move> = moves.filter { it.layer == i }
        /** The layer whose stated Z is nearest [z]. */
        fun nearestLayer(z: Double): Int = layerZs.indices.minBy { abs(layerZs[it] - z) }
        /** Total XY length (mm) of the moves of [feature] that pass [sel]. */
        fun length(feature: String, sel: (Move) -> Boolean = { true }): Double = moves.filter { it.feature == feature && sel(it) }.sumOf { it.length }
        val features: Set<String> get() = moves.mapTo(LinkedHashSet()) { it.feature }
    }

    /** Reads [gcode] (absolute XYZ, E absolute or relative). Arcs (G2 / G3) are refused: slice the samples with arcs off. */
    fun read(gcode: String): Toolpath {
        val out = ArrayList<Move>(); val zs = ArrayList<Double>()
        var x = 0.0; var y = 0.0; var z = 0.0; var e = 0.0; var relE = false; var f = 0.0
        var feature = ""; var layer = -1; var layerZ = 0.0; var width = Double.NaN; var height = Double.NaN
        var fan = 0.0; var temp = Double.NaN; var obj: String? = null; var pendingLayer = false; var diameter = Double.NaN
        val pauses = ArrayList<Int>()
        fun num(parts: List<String>, c: Char): Double? = parts.drop(1).firstOrNull { it.isNotEmpty() && it[0].uppercaseChar() == c }?.substring(1)?.toDoubleOrNull()
        for (raw in gcode.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line[0] == ';') {
                when {
                    line.startsWith(";LAYER_CHANGE") || line.startsWith("; CHANGE_LAYER") || line.startsWith(";CHANGE_LAYER") -> { layer++; pendingLayer = true }
                    line.startsWith(";Z:") || line.startsWith("; Z_HEIGHT:") -> {
                        layerZ = line.substringAfter(':').trim().toDouble()
                        if (pendingLayer) { zs += layerZ; pendingLayer = false }
                    }
                    line.startsWith(";TYPE:") || line.startsWith("; FEATURE:") -> feature = line.substringAfter(':').trim()
                    line.startsWith(";WIDTH:") || line.startsWith("; LINE_WIDTH:") -> width = line.substringAfter(':').trim().toDoubleOrNull() ?: Double.NaN
                    line.startsWith(";HEIGHT:") || line.startsWith("; LAYER_HEIGHT:") -> height = line.substringAfter(':').trim().toDoubleOrNull() ?: Double.NaN
                    line.startsWith("; object start ") -> obj = line.removePrefix("; object start ").trim()
                    line.startsWith("; object end") -> obj = null
                    line.startsWith("; filament_diameter:") -> diameter = line.substringAfter(':').trim().split(',')[0].trim().toDoubleOrNull() ?: Double.NaN
                }
                continue
            }
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) continue
            val parts = code.split(' ', '\t').filter { it.isNotEmpty() }
            when (parts[0].uppercase()) {
                "G0", "G1" -> {
                    val nx = num(parts, 'X') ?: x; val ny = num(parts, 'Y') ?: y; val nz = num(parts, 'Z') ?: z
                    num(parts, 'F')?.let { f = it / 60.0 }
                    val ne = num(parts, 'E')
                    val de = when { ne == null -> 0.0; relE -> ne; else -> ne - e }
                    if (ne != null) e = if (relE) e + ne else ne
                    // extrusion before the file's first layer change (a prime line) is layer -1: kept, a test filters it
                    if (de > 0 && (nx != x || ny != y)) out += Move(x, y, z, nx, ny, nz, de, feature, layer, layerZ, width, height, fan, temp, obj, out.size, f)
                    x = nx; y = ny; z = nz
                }
                "G2", "G3" -> error("an arc (${parts[0]}) in the sample's g-code: slice the samples with firmware.arcs = false")
                "G91" -> error("relative XYZ positioning (G91) is not supported")
                "G92" -> num(parts, 'E')?.let { e = it }
                "M82" -> relE = false
                "M83" -> relE = true
                "M0", "M1", "M25", "M600", "M601", "PAUSE" -> pauses += out.size
                "M106" -> fan = (num(parts, 'S') ?: 255.0) / 255.0
                "M107" -> fan = 0.0
                "M104", "M109" -> num(parts, 'S')?.let { temp = it }
            }
        }
        return Toolpath(out, zs, gcode, pauses, diameter)
    }

    /** How the moves are projected. */
    enum class View(val words: String) {
        TOP("from above"), SIDE("from the side, Z up"), UNROLLED("unrolled round the axis, arc length across, Z up"),
    }

    /**
     * What a move's color says: its feature, its place in print order, the part fan, the nozzle temperature, its declared
     * height, its plastic per mm, or its layer (two colors, layer by layer, so each layer reads as a line of its own).
     */
    enum class Coloring { FEATURE, ORDER, FAN, TEMPERATURE, HEIGHT, FLOW, LAYER }

    /** A point to mark (mm), drawn as a ring with [text] beside it. */
    class Mark(val x: Double, val y: Double, val z: Double, val text: String = "")

    /**
     * The region drawn (mm): [across] in the view's horizontal coordinate (X from above; along the view's direction from
     * the side; arc length unrolled), [up] in its vertical one (Y from above; true Z, before stretching, otherwise).
     */
    class Window(val across: ClosedFloatingPointRange<Double>, val up: ClosedFloatingPointRange<Double>)

    /**
     * One panel: the moves of [path] that pass [select], drawn as [coloring] says, under [label] (its settings and what to
     * read from it, a line each), with [marks] over them. [highlight]: when given, only the moves it passes are colored
     * (and counted in print order), the rest drawn light gray as context. [slab]: in a side view, the band across the line
     * of sight the moves are cut to (a section: each move drawn only where it lies in the band); null, the moves projected.
     * [notes]: words written at the panel's right edge at a height (true Z), e.g. a layer's thickness. [widths]: features
     * drawn from above at this width (mm) instead of their own, said in the panel's label by the test.
     */
    class Panel(val label: List<String>, val path: Toolpath, val select: (Move) -> Boolean,
                val coloring: Coloring = Coloring.FEATURE, val marks: List<Mark> = emptyList(),
                val highlight: ((Move) -> Boolean)? = null, val slab: ClosedFloatingPointRange<Double>? = null,
                val notes: List<Pair<Double, String>> = emptyList(), val widths: Map<String, Double> = emptyMap(),
                /** Each bead's height from its plastic per mm ([Toolpath.beadHeight]) instead of the height the file declares. */ val plasticHeight: Boolean = false) {
        fun heightOf(m: Move): Double = if (plasticHeight) path.beadHeight(m) else m.height
        val moves: List<Move> by lazy { path.moves.filter(select) }
    }

    /** The part of [m] whose coordinate [s] (a function of x, y, linear) lies in [slab], as a move of its own; null when none does. */
    private fun clip(m: Move, slab: ClosedFloatingPointRange<Double>, s: (Double, Double) -> Double): Move? {
        val a = s(m.x0, m.y0); val b = s(m.x1, m.y1)
        var t0 = 0.0; var t1 = 1.0
        if (abs(a - b) < 1e-12) { if (a !in slab) return null } else {
            val ta = (slab.start - a) / (b - a); val tb = (slab.endInclusive - a) / (b - a)
            t0 = max(0.0, min(ta, tb)); t1 = min(1.0, max(ta, tb))
            if (t0 > t1) return null
        }
        fun at(p: Double, q: Double, t: Double) = p + (q - p) * t
        return Move(at(m.x0, m.x1, t0), at(m.y0, m.y1, t0), at(m.z0, m.z1, t0), at(m.x0, m.x1, t1), at(m.y0, m.y1, t1), at(m.z0, m.z1, t1),
            m.e * (t1 - t0), m.feature, m.layer, m.layerZ, m.width, m.height, m.fan, m.temp, m.obj, m.index, m.speed)
    }

    // ---- plymeld's choice: how the picture looks
    private const val PANEL_PX = 560
    private const val MAX_PANEL_H = 480
    private const val MIN_PANEL_W = 400
    private const val GAP = 16
    private const val LINE_H = 17
    private const val NOTE_W = 70
    /** The strip under each panel's moves that holds its scale bar. */
    private const val BAR_H = 22
    /** From above, a path is drawn this share of its width (unless the render says otherwise), so neighbors read apart. */
    const val TOP_FILL = 0.8
    /** From the side, a bead is drawn this share of its height, so layers read apart. */
    const val SIDE_FILL = 0.85
    private val BACKGROUND = Color(0xF3, 0xF3, 0xF3)
    /** The moves a panel does not highlight. */
    private val CONTEXT = Color(0xD2, 0xD2, 0xD2)
    private val LAYER_COLORS = listOf(Color(0x2C, 0x5A, 0xA0), Color(0xE0, 0x8A, 0x1E))
    private val EXTRA = mapOf(
        "Wave overhang" to Color(0x1C, 0x9C, 0xD8), "Bridge tack" to Color(0xD6, 0x4F, 0x8F), "Prime tower" to Color(0x80, 0x80, 0x80),
        "Custom" to Color(0xAA, 0xAA, 0xAA))
    private val FALLBACK = listOf(Color(0x6A, 0x3D, 0x9A), Color(0xB1, 0x59, 0x28), Color(0x33, 0xA0, 0x2C), Color(0xFB, 0x9A, 0x99))
    /** A gradient, first to last (dark blue, teal, yellow-green, orange, red). */
    private val RAMP = listOf(Color(0x2B, 0x3A, 0x9F), Color(0x1F, 0x9E, 0x89), Color(0xB5, 0xDE, 0x2B), Color(0xF5, 0x8C, 0x1E), Color(0xC8, 0x1E, 0x1E))

    private fun ramp(t: Double): Color {
        val s = t.coerceIn(0.0, 1.0) * (RAMP.size - 1)
        val i = min(floor(s).toInt(), RAMP.size - 2); val u = s - i
        val a = RAMP[i]; val b = RAMP[i + 1]
        return Color((a.red + (b.red - a.red) * u).toInt(), (a.green + (b.green - a.green) * u).toInt(), (a.blue + (b.blue - a.blue) * u).toInt())
    }

    private fun featureColor(f: String, seen: MutableMap<String, Color>): Color =
        GcodeRender.colors[f] ?: EXTRA[f] ?: seen.getOrPut(f) { FALLBACK[seen.size % FALLBACK.size] }

    /** A round length near a fifth of [span]: 1, 2, or 5 times a power of ten. */
    private fun niceBar(span: Double): Double {
        val raw = span / 5; val p = 10.0.pow(floor(log10(raw)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.last { it <= max(raw, p) }
    }

    private fun fmt(v: Double): String = if (abs(v - Math.round(v)) < 1e-9) Math.round(v).toString() else "%.3g".format(java.util.Locale.ROOT, v)

    /**
     * Draws [panels] side by side under [title], all in [view] and at one scale, into [out] (PNG). [zScale] stretches Z in
     * the side and unrolled views; [along] is the side view's direction (radians from +X: what runs across the picture);
     * [center] is the axis (x, y) the unrolled view turns round, and [facing] the angle (radians, from +X) it puts in the
     * middle (the cut is opposite); [window] the region drawn (else all the panels' moves); [lineShare] the share of its
     * width a path is drawn from above. Returns the image's size.
     */
    fun render(title: String, view: View, panels: List<Panel>, out: Path, zScale: Double = 1.0, along: Double = 0.0,
               center: Pair<Double, Double>? = null, facing: Double = 0.0, window: Window? = null, lineShare: Double = TOP_FILL): Pair<Int, Int> {
        require(panels.isNotEmpty()) { "no panels" }
        require(view != View.UNROLLED || center != null) { "the unrolled view needs its axis" }
        val side = view != View.TOP
        val ca = cos(along); val sa = sin(along)
        // each panel's moves as drawn: cut to its slab in a side view (the slab across the view's direction)
        val drawn = panels.map { p -> val s = p.slab; if (s != null && view == View.SIDE) p.moves.mapNotNull { clip(it, s) { x, y -> -x * sa + y * ca } } else p.moves }
        for ((k, p) in panels.withIndex()) require(drawn[k].isNotEmpty()) { "panel \"${p.label.firstOrNull()}\" draws no extruding move" }
        fun lit(p: Panel, m: Move) = p.highlight?.invoke(m) ?: true
        val (cx, cy) = center ?: (0.0 to 0.0)
        val radius = if (view == View.UNROLLED) drawn.flatten().map { hypot((it.x0 + it.x1) / 2 - cx, (it.y0 + it.y1) / 2 - cy) }.average() else 1.0
        fun angle(x: Double, y: Double): Double {
            val a = atan2(y - cy, x - cx) - facing
            return atan2(sin(a), cos(a))
        }
        fun u(x: Double, y: Double): Double = when (view) { View.TOP -> x; View.SIDE -> x * ca + y * sa; View.UNROLLED -> angle(x, y) * radius }
        fun v(y: Double, z: Double): Double = if (side) z * zScale else y
        // the shared box: the window, or every move drawn
        var u0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE; var v0 = Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
        if (window != null) {
            u0 = window.across.start; u1 = window.across.endInclusive
            v0 = if (side) window.up.start * zScale else window.up.start; v1 = if (side) window.up.endInclusive * zScale else window.up.endInclusive
        } else {
            for (ms in drawn) for (m in ms) for ((x, y, z) in listOf(Triple(m.x0, m.y0, m.z0), Triple(m.x1, m.y1, m.z1))) {
                val a = u(x, y); val b = v(y, z); u0 = min(u0, a); u1 = max(u1, a); v0 = min(v0, b); v1 = max(v1, b)
            }
            val padU = 0.06 * max(u1 - u0, 1.0); val padV = 0.08 * max(v1 - v0, 1.0)
            u0 -= padU; u1 += padU; v0 -= padV; v1 += padV
        }
        val scale = min(PANEL_PX / (u1 - u0), MAX_PANEL_H / (v1 - v0))
        val notesW = if (panels.any { it.notes.isNotEmpty() }) NOTE_W else 0
        val gw = ceil((u1 - u0) * scale).toInt(); val ph = ceil((v1 - v0) * scale).toInt()
        val pw = max(gw + notesW, MIN_PANEL_W)
        val header = panels.maxOf { it.label.size } * LINE_H + 10
        val titleH = 30 + LINE_H
        val legendH = 30
        val titleFont = Font(Font.SANS_SERIF, Font.BOLD, 16)
        val titleText = "$title  (${view.words})"
        val subtitle = if (side) "each bead drawn at its declared width and ${(SIDE_FILL * 100).toInt()} % of its height" + (if (panels.any { it.plasticHeight }) " (its height from its plastic per mm and width)" else "") +
            (if (zScale != 1.0) "; Z stretched ${fmt(zScale)} x" else "") +
            (if (view == View.SIDE && abs(along) > 1e-9) "; looking across a line at ${fmt(Math.toDegrees(along))} deg to X" else "")
            else "each path drawn ${(lineShare * 100).toInt()} % of its declared width, so neighbors read apart"
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
        val titleW = probe.getFontMetrics(titleFont).stringWidth(titleText); probe.dispose()
        val width = max(panels.size * pw + (panels.size + 1) * GAP, titleW + 2 * GAP)
        val height = titleH + header + ph + BAR_H + legendH + GAP
        // the color scales
        val seen = LinkedHashMap<String, Color>()
        val legend = LinkedHashMap<String, Color>()
        for ((k, p) in panels.withIndex()) if (p.coloring == Coloring.FEATURE) for (m in drawn[k]) if (lit(p, m)) legend.getOrPut(m.feature) { featureColor(m.feature, seen) }
        val grayed = panels.withIndex().any { (k, p) -> drawn[k].any { !lit(p, it) } }
        fun range(c: Coloring, of: (Move) -> Double): Pair<Double, Double> {
            val xs = panels.withIndex().filter { it.value.coloring == c }.flatMap { (k, p) -> drawn[k].filter { lit(p, it) } }.map(of).filter { !it.isNaN() }
            return (xs.minOrNull() ?: 0.0) to (xs.maxOrNull() ?: 1.0)
        }
        val (tLo, tHi) = range(Coloring.TEMPERATURE) { it.temp }
        val hs = panels.withIndex().filter { it.value.coloring == Coloring.HEIGHT }.flatMap { (k, p) -> drawn[k].filter { lit(p, it) }.map { p.heightOf(it) } }.filter { !it.isNaN() }
        val hLo = hs.minOrNull() ?: 0.0; val hHi = hs.maxOrNull() ?: 1.0
        val (fLo, fHi) = range(Coloring.FLOW) { it.ePerMm }
        fun spread(x: Double, lo: Double, hi: Double) = if (hi > lo) (x - lo) / (hi - lo) else 0.5
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = BACKGROUND; g.fillRect(0, 0, width, height)
        g.color = Color.BLACK; g.font = titleFont
        g.drawString(titleText, GAP, 21)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13); g.drawString(subtitle, GAP, 21 + LINE_H)
        for ((k, p) in panels.withIndex()) {
            val ox = GAP + k * (pw + GAP); val oy = titleH + header
            g.color = Color.WHITE; g.fillRect(ox, oy, pw, ph + BAR_H)
            g.color = Color(0xC8, 0xC8, 0xC8); g.drawRect(ox, oy, pw, ph + BAR_H)
            g.color = Color.BLACK
            for ((i, s) in p.label.withIndex()) {
                g.font = Font(Font.SANS_SERIF, if (i == 0) Font.BOLD else Font.PLAIN, if (i == 0) 14 else 12)
                g.drawString(s, ox + 2, titleH + 14 + i * LINE_H)
            }
            val gx = ox + (pw - notesW - gw) / 2
            fun sx(a: Double) = gx + (a - u0) * scale
            fun sy(b: Double) = oy + (v1 - b) * scale
            val oldClip = g.clip
            g.clip = Rectangle(gx, oy, gw, ph)
            // the context (gray) first, then the highlighted moves over it, in print order
            val (on, offs) = drawn[k].partition { lit(p, it) }
            val n = on.size
            for ((i, m) in (offs.map { -1 to it } + on.withIndex().map { it.index to it.value })) {
                if (view == View.UNROLLED && abs(angle(m.x1, m.y1) - angle(m.x0, m.y0)) > Math.PI) continue   // across the cut
                g.color = if (i < 0) CONTEXT else when (p.coloring) {
                    Coloring.FEATURE -> featureColor(m.feature, seen)
                    Coloring.ORDER -> ramp(if (n <= 1) 0.0 else i.toDouble() / (n - 1))
                    Coloring.FAN -> ramp(m.fan)
                    Coloring.TEMPERATURE -> ramp(spread(m.temp, tLo, tHi))
                    Coloring.HEIGHT -> ramp(spread(p.heightOf(m), hLo, hHi))
                    Coloring.FLOW -> ramp(spread(m.ePerMm, fLo, fHi))
                    Coloring.LAYER -> LAYER_COLORS[Math.floorMod(m.layer, LAYER_COLORS.size)]
                }
                val w = p.widths[m.feature] ?: if (m.width.isNaN()) 0.4 else m.width
                val a0 = u(m.x0, m.y0); val a1 = u(m.x1, m.y1)
                if (!side) {
                    val share = if (m.feature in p.widths) 1.0 else lineShare
                    g.stroke = BasicStroke(max(1f, (w * share * scale).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(Line2D.Double(sx(a0), sy(v(m.y0, m.z0)), sx(a1), sy(v(m.y1, m.z1))))
                    continue
                }
                // the bead: from the nozzle's path down its height, reaching half its width past each end along the view
                val h = p.heightOf(m).let { if (it.isNaN()) 0.2 else it } * SIDE_FILL * zScale
                val dir = if (a1 >= a0) 1.0 else -1.0; val e = w / 2
                val ua = a0 - dir * e; val ub = a1 + dir * e
                val za = v(0.0, m.z0); val zb = v(0.0, m.z1)
                val bead = Path2D.Double()
                bead.moveTo(sx(ua), sy(za)); bead.lineTo(sx(ub), sy(zb)); bead.lineTo(sx(ub), sy(zb - h)); bead.lineTo(sx(ua), sy(za - h)); bead.closePath()
                g.fill(bead)
            }
            g.stroke = BasicStroke(2f); g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            for (mk in p.marks) {
                val px = sx(u(mk.x, mk.y)); val py = sy(v(mk.y, mk.z))
                g.color = Color.BLACK; g.draw(Ellipse2D.Double(px - 6, py - 6, 12.0, 12.0))
                if (mk.text.isNotEmpty()) g.drawString(mk.text, (px + 8).toFloat(), (py - 7).toFloat())
            }
            g.clip = oldClip
            // notes at the right edge
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11); g.color = Color.BLACK
            for ((z, s) in p.notes) { val py = sy(v(0.0, z)); if (py in oy.toDouble()..(oy + ph).toDouble()) g.drawString(s, (gx + gw + 4).toFloat(), (py + 4).toFloat()) }
            // the scale bar (true mm across; up, Z as stretched)
            val bar = niceBar(u1 - u0)
            val bx = gx + 10.0; val by = oy + ph + BAR_H - 8.0
            g.color = Color.BLACK; g.stroke = BasicStroke(2f); g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            g.draw(Line2D.Double(bx, by, bx + bar * scale, by))
            g.drawString("${fmt(bar)} mm", (bx + bar * scale + 4).toFloat(), (by + 4).toFloat())
            if (side && zScale != 1.0) {
                val zb = niceBar((v1 - v0) / zScale)
                g.draw(Line2D.Double(bx, by, bx, by - zb * zScale * scale))
                g.drawString("${fmt(zb)} mm in Z", (bx + 4).toFloat(), (by - zb * zScale * scale + 4).toFloat())
            }
        }
        // the legend: the features drawn, then a gradient's ends
        val ly = height - legendH + 6
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        var lx = GAP
        for ((f, c) in legend) {
            g.color = c; g.fillRect(lx, ly, 12, 12); g.color = Color.BLACK; g.drawString(f, lx + 16, ly + 11)
            lx += 30 + g.fontMetrics.stringWidth(f)
        }
        if (grayed) {
            val s = "other lines"
            g.color = CONTEXT; g.fillRect(lx, ly, 12, 12); g.color = Color.BLACK; g.drawString(s, lx + 16, ly + 11)
            lx += 30 + g.fontMetrics.stringWidth(s)
        }
        if (panels.any { it.coloring == Coloring.LAYER }) {
            for ((i, c) in LAYER_COLORS.withIndex()) { g.color = c; g.fillRect(lx + 14 * i, ly, 12, 12) }
            val s = "alternate layers"; g.color = Color.BLACK; g.drawString(s, lx + 14 * LAYER_COLORS.size + 4, ly + 11)
            lx += 14 * LAYER_COLORS.size + 20 + g.fontMetrics.stringWidth(s)
        }
        val gradient = panels.map { it.coloring }.firstOrNull { it != Coloring.FEATURE && it != Coloring.LAYER }
        if (gradient != null) {
            val (lo, hi) = when (gradient) {
                Coloring.ORDER -> "printed first" to "printed last"
                Coloring.FAN -> "part fan 0 %" to "part fan 100 %"
                Coloring.TEMPERATURE -> "${fmt(tLo)} C" to "${fmt(tHi)} C"
                Coloring.HEIGHT -> "${fmt(hLo)} mm high" to "${fmt(hHi)} mm high"
                Coloring.FLOW -> "${"%.4f".format(java.util.Locale.ROOT, fLo)} mm of filament per mm" to "%.4f".format(java.util.Locale.ROOT, fHi)
                Coloring.FEATURE, Coloring.LAYER -> "" to ""
            }
            g.color = Color.BLACK; g.drawString(lo, lx, ly + 11); lx += g.fontMetrics.stringWidth(lo) + 6
            for (i in 0 until 120) { g.color = ramp(i / 119.0); g.fillRect(lx + i, ly, 1, 12) }
            lx += 126; g.color = Color.BLACK; g.drawString(hi, lx, ly + 11)
        }
        g.dispose()
        out.parent?.let { Files.createDirectories(it) }
        ImageIO.write(img, "png", out.toFile())
        return width to height
    }
}
