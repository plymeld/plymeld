package plymeld.core.calibrate

import plymeld.geom.Polygon
import plymeld.program.Exact
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.LiftTo
import plymeld.program.Park
import plymeld.program.PrintObject
import plymeld.program.PrintProgram
import plymeld.program.SetFan
import plymeld.program.SetTemp
import plymeld.program.Step

/**
 * The motion plate (as few pieces as can show a range of settings, with a control at
 * the maximum speed and acceleration): the speed and acceleration a visible wall holds without ringing, per direction. It
 * replaces the motion hexagon, which could not tell X from Y and whose sides were read only band by
 * band, never written back.
 *
 * **One piece**: a hollow tower whose footprint is a cross of five equal squares ([MotionGeometry.arm] across), standing on a
 * cross-shaped slab. Its outline has **twelve faces**, each [MotionGeometry.arm] long and each starting at a sharp 90 degree
 * corner (the jolt that starts ringing): six run along X (faces 1, 3, 5, 7, 9, 11, counter-clockwise from the front arm's front
 * face) and six along Y (the even faces). A face along X shows the head's wobble in Y (the ripples are the wall moving in and out,
 * across it), a face along Y the wobble in X: so the two directions are read on their own faces.
 *
 *  - **Speed by face**: six speeds, each on one face along X and one along Y, laid round the tower so that every corner is
 *    reached from a face one speed step away at most ([MotionGeometry.FACE_SPEED]: 1 2 3 4 5 6 6 5 4 3 2 1): the jolt into a
 *    face comes from stopping the face before it, so a fast face never sets off a slow one's ripples.
 *  - **Acceleration by band**: the tower rises in bands ([MotionGeometry.bandHeight] each), one acceleration per band, low to
 *    high upward; both walls of a band at it. A **groove** (the walls set in by half the outer line for [MotionGeometry.grooveLayers]
 *    layers) at the start of every band above the first marks the bands.
 *  - **The control**: the fastest speed is the machine's print speed held to the filament's maximum flow, the top band's
 *    acceleration the machine's print acceleration; so faces 6 and 7 of the top band print at the machine's limits.
 *  - **The marks** (geometry, not text): arm n (1 front, 2 right, 3 back, 4 left, counter-clockwise from above) has n notches
 *    cut through the slab's edge in front of its end face (face 3n - 2), past the first third of that face; the slab's front-left
 *    corner is chamfered too. Every face's speed stands in front of it as a raised label ([PlateMarks], whole mm/s, read from
 *    outside that face, its baseline along it), [MotionGeometry.labelLayers] layers on the slab beside the tower's first layers.
 *
 * **Printed exactly as specified**: the slab's first layer at the first layer's values and width, its layers above at the
 * frame's (the machine's print speed and acceleration, held to the maximum flow); both tower walls, outer and inner, at their
 * face's speed (the inner held to the maximum flow at its own width) and their band's acceleration, so the walls a cell is read
 * on move only as that cell says; inner wall first, its seam behind the outer's, both on face 12 [MotionGeometry.seamAt] of the
 * way along it (no face starts at a seam); the slab's first layer at the filament's first-layer temperature, its layers above
 * heating to its print temperature, which the head waits for, lifted and parked in front of the slab, before the tower's first
 * line, so the whole tower is at that one temperature; the fan off on the first layer, the filament's own above; a parked
 * pause where a layer would take less than the filament's minimum layer time. Lengths µm, speeds mm/s, accelerations mm/s².
 */

/** The motion plate's layout (µm). placeholders, not measured: sizes and spacing only, no test value. */
object MotionGeometry {
    /** An arm's width: every face's length (less half a line at a convex corner). */
    const val arm = 30_000L
    /** A face's speed label to the face, and to the slab's edge. Plymeld's choice, as the flow plate's. */
    const val labelGap = 2_000L
    /**
     * The slab beyond the cross on every side: room for a speed label in front of an end face, [labelGap] from the face and from
     * the slab's edge (a label's glyphs, their strokes' ends reaching past the glyph height by up to a stroke on each side).
     */
    const val margin = 2 * labelGap + PlateMarks.LABEL_H + 2 * PlateMarks.LABEL_STROKE
    /** The speed labels stand this many layers on the slab (the tower's first layers beside them). */
    const val labelLayers = 2
    /** The slab's layers, the first included. */
    const val slabLayers = 3
    /** A band's height (rounded to whole layers). */
    const val bandHeight = 5_000L
    /** The groove at the start of each band above the first: this many layers, the walls set in by half the outer line. */
    const val grooveLayers = 2
    /** The chamfer at the front arm's front-left corner of the slab (the origin mark): the ruler post's. */
    const val chamfer = PlateGeometry.chamfer
    /** The seam (both walls' loops start and end here): on face 12, this share of the way from its start to its end. */
    const val seamAt = 0.8
    /** Faces round the cross. */
    const val faces = 12
    /** The speeds: one on a face along X and one on a face along Y. */
    const val speeds = 6
    /** Face k + 1's speed (0-based index into the speeds): up the speeds and back down, so neighbors are a step apart at most. */
    val FACE_SPEED = intArrayOf(0, 1, 2, 3, 4, 5, 5, 4, 3, 2, 1, 0)
    /** The cross's corners, counter-clockwise from the front arm's front-left (unplaced, in arms; the slab adds [margin]). */
    private val CORNERS = intArrayOf(1, 0, 2, 0, 2, 1, 3, 1, 3, 2, 2, 2, 2, 3, 1, 3, 1, 2, 0, 2, 0, 1, 1, 1)
    /** Corner [k] in arms (x, y). */
    fun corner(k: Int): Pair<Int, Int> = CORNERS[2 * (k % faces)] to CORNERS[2 * (k % faces) + 1]
    /** Whether face [k] (1-based) runs along X (the odd faces) or along Y. */
    fun alongX(k: Int): Boolean = k % 2 == 1
    /**
     * The arm marks, on the base facing up, giving the orientation: arm n (1 the front arm, 2 the right, 3 the back, 4 the left: counter-clockwise from above, like the
     * faces) has n notches cut into the slab's edge in front of its end face, face [endFace]: a [PlateMarks] tally (its
     * notch's width, depth and pitch, as every plate's), the group centered [markAt] from the end face's starting corner: two
     * thirds along it, so the marks stay clear of the first third after the corner, where ringing is read, and leave
     * [margin] - [markDepth] of slab before the face. [markAt] is plymeld's choice, not measured.
     */
    const val markWidth = PlateMarks.TALLY_W
    const val markDepth = PlateMarks.TALLY_IN
    const val markPitch = PlateMarks.TALLY_PITCH
    const val markAt = 20_000L
    /** The arms, counter-clockwise from the front one; arm n has n marks. */
    const val arms = 4
    /** Arm [n]'s (1-based) end face: the front arm's front face is face 1, and every arm is three faces on from the one before. */
    fun endFace(n: Int): Int = 3 * n - 2
    /** Arm [n]'s (1-based) name. */
    fun armName(n: Int): String = listOf("front", "right", "back", "left")[n - 1]
    /** Where along arm [n]'s end face (µm from its starting corner) mark [j] (0-based) of [count] is centered. */
    fun markCenter(j: Int, count: Int): Long = markAt - (count - 1) * markPitch / 2 + j * markPitch

    /** Where face [k] (1-based) is, in words. */
    fun where(k: Int): String = listOf("the front arm's front face", "the front arm's right side", "the right arm's front side", "the right arm's end",
        "the right arm's back side", "the back arm's right side", "the back arm's end", "the back arm's left side", "the left arm's back side",
        "the left arm's end", "the left arm's front side", "the front arm's left side")[k - 1]
}

class MotionSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    /** The six speeds, slow to fast (mm/s): [MotionGeometry.FACE_SPEED] puts them on the faces. The last is the control's. */
    val speeds: DoubleArray,
    /** One acceleration per band, bottom to top, rising (mm/s²). The last is the control's. */
    val accels: DoubleArray,
    /** The layer height (µm) and the slab's first layer's. */
    val layerUm: Long, val firstLayerUm: Long,
    /** The outer wall's line width (µm), the inner wall's, the slab's first layer's. */
    val widthUm: Long, val innerWidthUm: Long, val firstWidthUm: Long,
    val filamentDiameterMm: Double,
    /** The flow ratio every line prints at. */
    val flowRatio: Double,
    /**
     * The tower's nozzle temperature (the slab's layers above the first heat to it), the slab's first layer's, and the part fan
     * from the second layer (the first layer's is off).
     */
    val tempC: Int, val firstTempC: Int, val fanPct: Double,
    /** The slab's first layer's values, its layers above (the frame's). */
    val adhesion: PathValues, val frame: PathValues,
    /** The fastest the inner wall may go (mm/s): the filament's maximum flow at its width. */
    val innerCap: Double,
    /** The tower's retraction (mm) for the travels into its walls. */
    val retractMm: Double,
    /** How far the nozzle rises off the finished plate (µm, the travel lift). */
    val liftUm: Long,
    /** Every layer takes at least this long (s; 0 = no pause): a parked pause makes up the rest. */
    val minLayerTimeS: Double = 0.0,
    /** The travel speed (mm/s), for the pause's estimate. */
    val travelSpeed: Double = 200.0,
    /** Where the slab's bounding box's front-left corner goes on the bed (µm). */
    val originXUm: Long = 0,
    val originYUm: Long = 0,
    /** The machine's bed levelling, run after its start template; null = none. */
    val levelGcode: String? = null,
) {
    init {
        require(speeds.size == MotionGeometry.speeds) { "the motion plate has ${MotionGeometry.speeds} speeds, one per face along X and one along Y (got ${speeds.size})" }
        require(speeds.all { it > 0 } && speeds.toList().zipWithNext().all { (a, b) -> b > a }) { "the speeds rise, slow to fast, all above 0 (got ${speeds.joinToString()})" }
        require(accels.isNotEmpty()) { "the motion plate needs at least one band (acceleration)" }
        require(accels.all { it > 0 } && accels.toList().zipWithNext().all { (a, b) -> b > a }) { "the accelerations rise, bottom to top, all above 0 (got ${accels.joinToString()})" }
        require(layerUm > 0 && firstLayerUm > 0 && widthUm > 0 && innerWidthUm > 0 && firstWidthUm > 0) { "the layer heights and line widths are above 0" }
        require(innerCap > 0) { "the inner wall's flow cap is above 0" }
        require(widthUm < MotionGeometry.arm / 8) { "a ${widthUm / 1000.0} mm wall leaves no room in a ${MotionGeometry.arm / 1000.0} mm arm" }
    }

    private val g = MotionGeometry

    init {
        require(g.labelLayers <= bandLayers) { "the speed labels' ${g.labelLayers} layers are more than a band's $bandLayers" }
        for (k in 1..g.faces) {
            val t = speedText(k)
            require(PlateMarks.labelHeight(t) + 2 * g.labelGap <= g.margin && PlateMarks.labelWidth(t) + 2 * g.labelGap <= g.arm) {
                "face $k's speed label '$t' (mm/s) does not fit in front of it"
            }
        }
    }

    /** Layers per band ([MotionGeometry.bandHeight] in whole layers, at least the groove and two more). */
    val bandLayers: Int get() = maxOf(g.grooveLayers + 2, Math.round(g.bandHeight.toDouble() / layerUm).toInt())
    val bands: Int get() = accels.size
    /** The tower's layers and the plate's (the slab's included). */
    val towerLayers: Int get() = bands * bandLayers
    val layers: Int get() = g.slabLayers + towerLayers
    /** The slab's size (µm): the cross and its margin. */
    val slabSize: Long get() = 3 * g.arm + 2 * g.margin
    /** Layer [layer]'s top (µm): the first layer its own thickness, each layer above [layerUm] more. */
    fun zTop(layer: Int): Long = firstLayerUm + layer * layerUm
    fun heightOf(layer: Int): Long = if (layer == 0) firstLayerUm else layerUm
    val slabTop: Long get() = zTop(g.slabLayers - 1)
    val topZ: Long get() = zTop(layers - 1)
    /** Tower layer [i]'s band (0-based) and whether it is a groove layer (the first [MotionGeometry.grooveLayers] of a band above the first). */
    fun bandOf(i: Int): Int = i / bandLayers
    fun isGroove(i: Int): Boolean = bandOf(i) > 0 && i % bandLayers < g.grooveLayers
    /** Band [b]'s bottom and top (µm, the bottom of its first layer and the top of its last). */
    fun bandFrom(b: Int): Long = slabTop + b * bandLayers * layerUm
    fun bandTo(b: Int): Long = slabTop + (b + 1) * bandLayers * layerUm
    /** The groove's depth (µm): half the outer line. plymeld's choice. */
    val grooveUm: Long get() = widthUm / 2
    /** Center-to-center distance (µm) of the outer and inner walls (the bead model's). */
    val wallSpacingUm: Long get() = Math.round((widthUm + innerWidthUm) / 2.0 - layerUm * (1 - Math.PI / 4))
    /** How far in from the cross's outline a wall's center line lies on tower layer [i]. */
    fun insetUm(i: Int, inner: Boolean): Long = widthUm / 2 + (if (inner) wallSpacingUm else 0L) + (if (isGroove(i)) grooveUm else 0L)

    /** Face [k]'s (1-based) speed: the outer wall's, and the inner's held to its flow cap. */
    fun faceSpeed(k: Int): Double = speeds[g.FACE_SPEED[k - 1]]
    fun innerSpeed(k: Int): Double = Math.min(faceSpeed(k), innerCap)
    /** The faces at speed [j] (0-based): one along X and one along Y. */
    fun facesAt(j: Int): Pair<Int, Int> = (1..g.faces).filter { g.FACE_SPEED[it - 1] == j }.let { fs -> fs.first { g.alongX(it) } to fs.first { !g.alongX(it) } }

    /** The shortest outer face (µm, between two convex corners, off a groove): the length the reach is worked out on. */
    val shortestFaceUm: Long get() = g.arm - widthUm
    /**
     * The fastest a face can get at band [b]'s acceleration (mm/s): speeding up over half the shortest face and slowing down over
     * the other half, from and to a stop, sqrt(a x L). A kinematic bound: the firmware's corner speed lifts it a little, a
     * rule like Klipper's minimum cruise ratio lowers it.
     */
    fun reach(b: Int): Double = Math.sqrt(accels[b] * shortestFaceUm / 1000.0)
    /** What face [k] actually reaches in band [b] (mm/s): its speed, or the reach where that is lower. */
    fun reached(k: Int, b: Int): Double = Math.min(faceSpeed(k), reach(b))

    /** The cross's corner [k] (µm, unplaced: the slab's bounding box from 0,0). */
    fun corner(k: Int): LongArray = g.corner(k).let { (ax, ay) -> longArrayOf(g.margin + ax * g.arm, g.margin + ay * g.arm) }

    /**
     * The cross's outline set in by [d] µm, placed on the bed, counter-clockwise from the front arm's front-left corner: corner k
     * moved by d along both its edges' inward normals (the square offset of an outline whose edges all run along X or Y).
     */
    fun inset(d: Long): LongArray {
        val out = LongArray(2 * g.faces)
        for (k in 0 until g.faces) {
            val p = corner(k); val a = corner(k + g.faces - 1); val b = corner(k + 1)
            val inX = java.lang.Long.signum(p[0] - a[0]); val inY = java.lang.Long.signum(p[1] - a[1])
            val outX = java.lang.Long.signum(b[0] - p[0]); val outY = java.lang.Long.signum(b[1] - p[1])
            // the left normal of a counter-clockwise edge points inside
            out[2 * k] = p[0] + d * (-inY - outY) + originXUm
            out[2 * k + 1] = p[1] + d * (inX + outX) + originYUm
        }
        return out
    }

    /**
     * A wall's loop on tower layer [i] (µm, placed, counter-clockwise), starting and ending at the seam on face 12: the seam,
     * then the corners from the front arm's front-left one round to the front arm's back-left one. Segment 0 is face 12's last
     * part, segment k (1..11) face k, segment 12 face 12's first part.
     */
    fun loop(i: Int, inner: Boolean): LongArray = loopAt(insetUm(i, inner))

    /** [loop] at an inset of [d] µm. */
    fun loopAt(d: Long): LongArray {
        val v = inset(d)
        val n = g.faces
        val sx = v[2 * (n - 1)] + Math.round((v[0] - v[2 * (n - 1)]) * g.seamAt); val sy = v[2 * (n - 1) + 1] + Math.round((v[1] - v[2 * (n - 1) + 1]) * g.seamAt)
        return LongArray(2 * (n + 1)) { j -> if (j < 2) (if (j == 0) sx else sy) else v[j - 2] }
    }

    /** Face [k]'s (1-based) speed label: its text (the outer wall's speed, whole mm/s) and its cell ("speed label 1"). */
    fun speedText(k: Int): String = PlateMarks.whole(faceSpeed(k))
    fun speedLabel(k: Int) = "speed label $k"
    /** Which face (1-based) a speed label's cell names, or null. */
    fun faceOfLabel(label: String): Int? = Regex("speed label (\\d+)").matchEntire(label)?.groupValues?.get(1)?.toInt()

    /** The cell labels: band [b]'s outer and inner walls ("band 1 outer"), the slab's layers ("adhesion:slab", "slab 2" ...). */
    fun outerLabel(b: Int) = "band ${b + 1} outer"
    fun innerLabel(b: Int) = "band ${b + 1} inner"
    fun slabLabel(layer: Int) = if (layer == 0) "adhesion:slab" else "slab ${layer + 1}"
    /** Which band (0-based) a cell label names and whether it is the inner wall's, or null. */
    fun bandOfLabel(label: String): Pair<Int, Boolean>? = Regex("band (\\d+) (outer|inner)").matchEntire(label)?.let { it.groupValues[1].toInt() - 1 to (it.groupValues[2] == "inner") }

    val filamentMm2: Double get() = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
    /** Bead cross-section of a [w] µm line [h] µm high, mm² (the writer's bead model). */
    fun beadMm2(w: Long, h: Long): Double { val hm = h / 1000.0; return hm * (w / 1000.0 - hm * (1 - Math.PI / 4)) }
}

/**
 * Builds the motion plate's program from its [spec]. [without]: for the read-back check's controls only, which must catch
 * them: "grooves" (no grooves), "chamfer" (no chamfer), "outerfirst" (each layer's outer wall before its inner), "speeds" (the
 * faces' speeds in reverse, the slowest on face 6), "swap" (faces 1 and 3 swapped), "accel" (every band at the first band's
 * acceleration), "seam" (the loops starting at the front arm's front-left corner, not on face 12), "slabspeed" (the slab's upper
 * layers at the fastest face's speed), "marks" (no arm marks), "markswap" (the front arm with three marks, the back arm with one),
 * "markdeep" (the marks cut through the margin to the faces), "markearly" (the marks a third of an arm nearer the faces' starts),
 * "firsttemp" (the slab's first layer at the tower's temperature), "towerwait" (no wait for the tower's temperature before it),
 * "speedlabel" (face 1's speed label left out), "labeltext" (face 1's label showing the fastest speed), "labelsafter" (the labels
 * laid after the walls, so the travels between them cross the layer's walls unlifted).
 */
class MotionPlate(private val spec: MotionSpec, private val without: Set<String> = emptySet()) {
    private val g = MotionGeometry

    /** How many marks arm [n] (1-based) carries, the controls applied ("marks": none; "markswap": the front and back arms' swapped). */
    fun marksOf(n: Int): Int = when {
        "marks" in without -> 0
        "markswap" in without && (n == 1 || n == 3) -> 4 - n
        else -> n
    }

    /**
     * The slab's outline (µm, unplaced, counter-clockwise): the cross grown by the margin, the front arm's front-left corner
     * chamfered, each arm's end edge notched with its marks ([MotionGeometry.markAt]).
     */
    fun outline(): Polygon {
        val a = g.arm; val m = g.margin; val s = spec.slabSize
        val ch = if ("chamfer" in without) 0L else g.chamfer
        val pts = ArrayList<Long>()
        fun p(x: Long, y: Long) { pts += x; pts += y }
        // arm n's marks along its end edge, which runs from (sx, sy) in direction (ux, uy) (counter-clockwise, so the slab is to
        // its left, (-uy, ux)); the end face starts [margin] along it
        val deep = if ("markdeep" in without) m else g.markDepth
        val early = if ("markearly" in without) g.arm / 3 else 0L
        fun marks(n: Int, sx: Long, sy: Long, ux: Int, uy: Int) {
            val c = marksOf(n)
            for (j in 0 until c) {
                val mid = m + g.markCenter(j, c) - early
                for ((along, depth) in listOf(mid - g.markWidth / 2 to 0L, mid - g.markWidth / 2 to deep, mid + g.markWidth / 2 to deep, mid + g.markWidth / 2 to 0L))
                    p(sx + ux * along - uy * depth, sy + uy * along + ux * depth)
            }
        }
        if (ch > 0) p(a + ch, 0) else p(a, 0)
        marks(1, a, 0, 1, 0)
        p(2 * a + 2 * m, 0); p(2 * a + 2 * m, a); p(s, a)
        marks(2, s, a, 0, 1)
        p(s, 2 * a + 2 * m); p(2 * a + 2 * m, 2 * a + 2 * m); p(2 * a + 2 * m, s)
        marks(3, 2 * a + 2 * m, s, -1, 0)
        p(a, s); p(a, 2 * a + 2 * m); p(0, 2 * a + 2 * m)
        marks(4, 0, 2 * a + 2 * m, 0, -1)
        p(0, a); p(a, a)
        if (ch > 0) p(a, ch)
        return Polygon(pts.toLongArray())
    }

    /**
     * Face [k]'s (1-based) speed label (µm, unplaced, raised glyphs): the face's speed, outside the cross [MotionGeometry.labelGap]
     * from the face and centered along it, read from outside the face (its baseline along the face). The controls: "speedlabel" (face 1's left out),
     * "labeltext" (face 1's showing the fastest speed).
     */
    fun speedMark(k: Int): List<Polygon> {
        if ("speedlabel" in without && k == 1) return emptyList()
        val t = if ("labeltext" in without && k == 1) PlateMarks.whole(spec.speeds.last()) else spec.speedText(k)
        val a = spec.corner(k - 1); val b = spec.corner(k)
        val dx = java.lang.Long.signum(b[0] - a[0]); val dy = java.lang.Long.signum(b[1] - a[1])
        val w = PlateMarks.labelWidth(t); val h = PlateMarks.labelHeight(t); val gap = g.labelGap
        // the outward normal of a counter-clockwise edge is to its right, (dy, -dx); the glyphs' up toward the face, their
        // baseline along it, so the label reads from outside the face: (u, v) on the label to the face's middle + (u - w/2)
        // along the reading direction + (gap + h - v) outward
        val nx = dy.toLong(); val ny = -dx.toLong()
        val upX = -nx; val upY = -ny
        val rx = upY; val ry = -upX
        val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
        return PlateMarks.label(t, 0, 0).map { p ->
            Polygon(LongArray(p.xy.size) { i ->
                val u = p.xy[i - i % 2] - w / 2; val v = p.xy[i - i % 2 + 1] - (gap + h)
                if (i % 2 == 0) mx + u * rx + v * upX else my + u * ry + v * upY
            })
        }
    }

    /** The slab's fill angle on [layer]: alternating 45 and 135 so neighbors cross. */
    fun fillAngle(layer: Int): Double = if (layer % 2 == 0) 45.0 else 135.0

    /** The slab on [layer] in [lw] µm lines [hUm] high ([PlateFill]): its rings (outermost first), then the inside solid, monotonic. */
    fun slabPaths(layer: Int, lw: Long, hUm: Long): Pair<List<LongArray>, List<LongArray>> = PlateFill.fill(listOf(outline()), lw, hUm, fillAngle(layer))

    /** The plate's extent before it is placed (µm: x0, y0, x1, y1). */
    fun bounds(): LongArray = longArrayOf(0, 0, spec.slabSize, spec.slabSize)

    /** Band [b]'s acceleration, a control applied ("accel"). */
    fun accelOf(b: Int): Double = if ("accel" in without) spec.accels[0] else spec.accels[b]

    /** Face [k]'s (1-based) speed on a wall, the controls applied ("speeds": reversed; "swap": faces 1 and 3 swapped). */
    fun speedOf(k: Int, inner: Boolean): Double {
        val face = if ("swap" in without && (k == 1 || k == 3)) 4 - k else k
        val v = if ("speeds" in without) spec.speeds[g.speeds - 1 - g.FACE_SPEED[face - 1]] else spec.faceSpeed(face)
        return if (inner) Math.min(v, spec.innerCap) else v
    }

    /**
     * A wall's loop on tower layer [i] and its segments' speeds, the controls applied ("grooves": no groove; "seam": the loop from
     * the front arm's front-left corner, face 1 first).
     */
    fun loopOf(i: Int, inner: Boolean): Pair<LongArray, DoubleArray> {
        val d = spec.insetUm(i, inner) - (if ("grooves" in without && spec.isGroove(i)) spec.grooveUm else 0L)
        if ("seam" in without) return spec.inset(d) to DoubleArray(g.faces) { speedOf(it + 1, inner) }
        return spec.loopAt(d) to DoubleArray(g.faces + 1) { j -> speedOf(if (j == 0 || j == g.faces) 12 else j, inner) }
    }

    fun program(): PrintProgram {
        val steps = ArrayList<Step>()
        val ox = spec.originXUm; val oy = spec.originYUm
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        val firstC = if ("firsttemp" in without) spec.tempC else spec.firstTempC
        steps += SetTemp(spec.tool, firstC, wait = true)
        steps += SetFan(0.0)
        val park = parkUm()
        for (layer in 0 until g.slabLayers) {
            val z = spec.zTop(layer); val hz = spec.heightOf(layer)
            steps += LayerStart(layer, z, hz)
            if (layer == 1) { steps += SetFan(spec.fanPct); if (firstC != spec.tempC) steps += SetTemp(spec.tool, spec.tempC, wait = false) }
            val first = layer == 0
            val pv = if (first) spec.adhesion else if ("slabspeed" in without) PathValues(spec.speeds.last(), spec.frame.accel, spec.frame.flowRatio, spec.frame.retractMm) else spec.frame
            val lw = if (first) spec.firstWidthUm else spec.widthUm
            val ex = Exact(spec.slabLabel(layer), doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm)
            val (rings, lines) = slabPaths(layer, lw, hz)
            for ((i, ring) in rings.withIndex()) steps += placed(if (i == 0) "Outer wall" else "Inner wall", ring, true, z, ex, ox, oy, lw, hz)
            for (line in lines) steps += placed(if (first) "Bottom surface" else "Internal solid infill", line, false, z, ex, ox, oy, lw, hz)
        }
        // the tower at its one temperature from its first line: where the slab's first layer was at another, lift, park in front
        // of the slab and wait for it
        if (firstC != spec.tempC && "towerwait" !in without) {
            steps += LiftTo(spec.slabTop + spec.liftUm)
            steps += Park(park.first, park.second, 0.0)
            steps += SetTemp(spec.tool, spec.tempC, wait = true)
        }
        val h = spec.layerUm
        for (i in 0 until spec.towerLayers) {
            val z = spec.slabTop + (i + 1) * h
            steps += LayerStart(g.slabLayers + i, z, h)
            val b = spec.bandOf(i)
            val a = accelOf(b)
            val (il, isp) = loopOf(i, true); val (ol, osp) = loopOf(i, false)
            val inner = Extrude("Inner wall", il, true, z, spec.innerWidthUm, h, tool = spec.tool, exact = Exact(spec.innerLabel(b), isp, a, spec.flowRatio, spec.retractMm))
            val outer = Extrude("Outer wall", ol, true, z, spec.widthUm, h, tool = spec.tool, exact = Exact(spec.outerLabel(b), osp, a, spec.flowRatio, spec.retractMm))
            // the speed labels on the tower's first layers, before its walls (so no travel crosses a wall of the layer), at the
            // frame's values, face by face counter-clockwise
            val labels = ArrayList<Step>()
            if (i < g.labelLayers) for (k in 1..g.faces) {
                val glyphs = speedMark(k)
                if (glyphs.isEmpty()) continue
                val ex = Exact(spec.speedLabel(k), doubleArrayOf(spec.frame.speed), spec.frame.accel, spec.frame.flowRatio, spec.frame.retractMm)
                val (rings, lines) = PlateFill.fill(glyphs, spec.widthUm, h, if (i % 2 == 0) 0.0 else 90.0)
                for (ring in rings) labels += placed("Outer wall", ring, true, z, ex, ox, oy, spec.widthUm, h)
                for (line in lines) labels += placed("Outer wall", line, false, z, ex, ox, oy, spec.widthUm, h)
            }
            if ("labelsafter" !in without) steps += labels
            if ("outerfirst" in without) { steps += outer; steps += inner } else { steps += inner; steps += outer }
            if ("labelsafter" in without) steps += labels
            // the cooling pause: park in front of the slab and wait out the rest of the filament's minimum layer time (the walls'
            // time only, so the labels' layers are paused if anything long)
            if (spec.minLayerTimeS > 0 && i + 1 < spec.towerLayers) {
                val rest = spec.minLayerTimeS - layerTimeS(listOf(inner, outer), a) - travelS(outer.xy, park)
                if (rest > 0) steps += Park(park.first, park.second, rest)
            }
        }
        steps += LiftTo(spec.topZ + spec.liftUm)
        val bb = bounds().let { longArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }
        val fp = doubleArrayOf(bb[0] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[3] / 1000.0, bb[0] / 1000.0, bb[3] / 1000.0)
        return PrintProgram(steps, listOf(PrintObject("motion_plate", fp, (bb[0] + bb[2]) / 2000.0, (bb[1] + bb[3]) / 2000.0)), exact = true)
    }

    /** The plate's g-code: the machine's start and end, everything between exactly as specified. [pressureAdvance]: by tool. */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String, pressureAdvance: Map<Int, Double> = emptyMap()): String =
        java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version, pressureAdvance = pressureAdvance).write(program(), it) }.toString()

    /** Where the head waits out a layer's cooling pause: [PlateGeometry.parkFront] in front of the slab, at its middle (bed, µm). */
    fun parkUm(): Pair<Long, Long> = (spec.originXUm + spec.slabSize / 2) to (spec.originYUm - PlateGeometry.parkFront)

    /**
     * A layer's time (s) at [accel]: each segment speeding up from a stop to its speed and slowing to a stop again (a trapezoid,
     * or a triangle where it is too short), so if anything long and the pause short.
     */
    private fun layerTimeS(paths: List<Extrude>, accel: Double): Double = paths.sumOf { p ->
        val m = p.xy.size / 2
        (0 until m).sumOf { k ->
            val u = (k + 1) % m
            val len = Math.hypot((p.xy[2 * u] - p.xy[2 * k]).toDouble(), (p.xy[2 * u + 1] - p.xy[2 * k + 1]).toDouble()) / 1000.0
            val v = p.exact!!.speed(k)
            if (len >= v * v / accel) len / v + v / accel else 2 * Math.sqrt(len / accel)
        }
    }

    /** To the park and back from the loop's start (s), at the travel speed. */
    private fun travelS(xy: LongArray, park: Pair<Long, Long>): Double = 2 * Math.hypot((xy[0] - park.first).toDouble(), (xy[1] - park.second).toDouble()) / 1000.0 / spec.travelSpeed

    /** A path of the slab, placed on the bed. */
    private fun placed(feature: String, xy: LongArray, closed: Boolean, zUm: Long, ex: Exact, ox: Long, oy: Long, widthUm: Long, heightUm: Long): Extrude {
        val moved = LongArray(xy.size) { i -> xy[i] + if (i % 2 == 0) ox else oy }
        return Extrude(feature, moved, closed, zUm, widthUm, heightUm, tool = spec.tool, exact = ex)
    }
}
