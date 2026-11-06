package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.gcode.PrimeLine
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.PrintObject
import plymeld.program.PrintProgram
import plymeld.program.Step

/**
 * The prime line (PrimeLine) on synthetic first layers: a 30 x 20 mm box's outer wall, alone, with a brim,
 * with a prime tower, at the bed's edge, next to another object. Every expected position is worked from the box's wall line
 * and the settings (plan.mechanism.adhesion.prime.gap, plan.mechanism.adhesion.prime.length, the first-layer width): the line lies plan.mechanism.adhesion.prime.gap plus a line's width out from the
 * wall's center line (a plan.mechanism.adhesion.prime.gap of air between the two beads), plan.mechanism.adhesion.prime.length long, centered on the middle of the side.
 * Controls: a changed plan.mechanism.adhesion.prime.gap moves it by exactly the change; plan.mechanism.adhesion.prime.enabled = false, a prime tower, and a program that primes
 * itself each leave the program untouched; the front blocked (by the bed's edge or another object) moves it to the back.
 */
class PrimeLineTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "200 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "10000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "G28", "hardware.machine.m.gcode.end" to "M84",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic")
    val box = ModelObject("box", listOf("box"), listOf(1), "box.3mf")
    fun settings(vararg extra: Pair<String, Any>): SliceSettings {
        val entries = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        return SliceSettings.of(ConfigSetup.setup(Config(ConfigStack(entries), listOf(box))).setup)
    }
    val s = settings()
    val w = s.firstLayerWidthUm
    val h = 200L

    /** A closed rectangle (µm), counter-clockwise. */
    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
    fun wall(xy: LongArray, feature: String = "Outer wall") = Extrude(feature, xy, true, h, w, h)
    /** The first layer: objects (name to their first-layer extrusions) in print order, then a second layer. */
    fun program(vararg objects: Pair<String, List<Extrude>>, selfPrimed: Boolean = false): PrintProgram {
        val steps = ArrayList<Step>()
        steps += LayerStart(0, h, h)
        for ((name, ex) in objects) { steps += ObjectStart(name); steps += ex; steps += ObjectEnd(name) }
        steps += LayerStart(1, 2 * h, h)
        steps += Extrude("Outer wall", rect(100_000, 100_000, 130_000, 120_000), true, 2 * h, w, h)
        return PrintProgram(steps, objects.map { PrintObject(it.first, doubleArrayOf(), 0.0, 0.0) }, selfPrimed = selfPrimed)
    }
    val boxWall = rect(100_000, 100_000, 130_000, 120_000)
    fun added(before: PrintProgram, after: PrintProgram): List<Extrude> {
        after.steps[0] shouldBe before.steps[0]                                 // the first LayerStart stays first
        val n = after.steps.size - before.steps.size
        val made = after.steps.subList(1, 1 + n).map { it as Extrude }
        after.steps.subList(1 + n, after.steps.size) shouldBe before.steps.subList(1, before.steps.size)
        return made
    }
    fun pts(xy: LongArray) = List(xy.size / 2) { xy[2 * it] to xy[2 * it + 1] }
    fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(px - ax - t * dx, py - ay - t * dy)
    }
    /** The distance from (x, y) to a closed loop. */
    fun toLoop(x: Long, y: Long, loop: LongArray): Double { val m = loop.size / 2
        return (0 until m).minOf { val j = (it + 1) % m; segDist(x.toDouble(), y.toDouble(), loop[2 * it].toDouble(), loop[2 * it + 1].toDouble(), loop[2 * j].toDouble(), loop[2 * j + 1].toDouble()) } }
    fun length(xy: LongArray) = pts(xy).zipWithNext().sumOf { (a, b) -> Math.hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }
    /** The free line (one path, prime.gap out): the cases on where the line goes read it; the default is the tab. */
    val near = settings("plan.mechanism.adhesion.prime.line" to "near")

    "near (plan.mechanism.adhesion.prime.line = near): plan.mechanism.adhesion.prime.length along the middle of the front, plan.mechanism.adhesion.prime.gap of air from the wall" {
        val p = program("box" to listOf(wall(boxWall)))
        val r = PrimeLine.insert(p, settings("plan.mechanism.adhesion.prime.line" to "near"))
        r.note shouldStartWith "near the print (prime.line = near)"
        r.note shouldContain "front"
        val line = added(p, r.program).single()
        line.feature shouldBe "Prime line"
        (line.widthUm to line.heightUm) shouldBe (w to h)
        line.closed shouldBe false
        // the front: y = the wall's 100 mm less a line's width and the gap; centered on x = 115 mm
        val y = 100_000 - w - s.primeGapUm
        for ((px, py) in pts(line.xy)) withClue("($px, $py)") { py shouldBe y }
        length(line.xy) shouldBe (s.primeLengthUm.toDouble() plusOrMinus 2.0)
        ((line.xy[0] + line.xy[line.xy.size - 2]) / 2.0) shouldBe (115_000L.toDouble() plusOrMinus 1.0)
        // control: 3 mm more gap moves it 3 mm further out, and nothing else
        val r8 = PrimeLine.insert(p, settings("plan.mechanism.adhesion.prime.line" to "near", "plan.mechanism.adhesion.prime.gap" to "8 mm"))
        val y8 = added(p, r8.program).single().xy.let { xy -> pts(xy).map { it.second }.distinct().single() }
        y - y8 shouldBe 3_000L
    }

    "a side shorter than the line: it follows the wall round the corners, at the same distance all along" {
        val p = program("box" to listOf(wall(boxWall)))
        val line = added(p, PrimeLine.insert(p, settings("plan.mechanism.adhesion.prime.line" to "near", "plan.mechanism.adhesion.prime.length" to "50 mm")).program).single()
        length(line.xy) shouldBe (50_000.0 plusOrMinus 2.0)
        // 50 mm on a 30 mm front: it turns up both sides
        pts(line.xy).maxOf { it.second } shouldNotBe pts(line.xy).minOf { it.second }
        val want = (w + s.primeGapUm).toDouble()
        // never closer than the gap; round a corner up to √2 further (the offset's corners are mitred: sharp, not round)
        for ((px, py) in pts(line.xy)) withClue("($px, $py)") { toLoop(px, py, boxWall).let { (it >= want - 2 && it <= want * Math.sqrt(2.0) + 2) shouldBe true } }
        // and exactly at it wherever it faces a straight stretch of wall (beside it: x within the box, or y within it)
        for ((px, py) in pts(line.xy).filter { (px, py) -> px in 100_000..130_000 || py in 100_000..120_000 })
            withClue("($px, $py)") { toLoop(px, py, boxWall) shouldBe (want plusOrMinus 2.0) }
        // and never round to the back half of the box
        (pts(line.xy).maxOf { it.second } < 110_000L) shouldBe true
    }

    "tab (the default with a brim): the line against the brim's outer edge, no lines added under it" {
        val brims = (1..3).map { k -> val d = k * w; wall(rect(100_000 - d, 100_000 - d, 130_000 + d, 120_000 + d), "Brim") }
        val p = program("box" to brims + wall(boxWall))
        val r = PrimeLine.insert(p, s)
        r.note shouldStartWith "on a peel tab, against the brim (there is a brim)"
        val line = added(p, r.program).single()
        line.feature shouldBe "Prime line"
        // touching the outer brim line: one line's width center to center
        for ((px, py) in pts(line.xy)) withClue("($px, $py)") { py shouldBe 100_000 - 3 * w - w }
    }

    "tab without a brim (the default with no tower; also plan.mechanism.adhesion.prime.line = tab): partial brim lines fill between the print and the line, only under it" {
        val p = program("box" to listOf(wall(boxWall)))
        // the default, unset: the tab, the reason "no prime tower"
        PrimeLine.insert(p, s).note shouldStartWith "on a peel tab ("
        PrimeLine.insert(p, s).note shouldContain "(no prime tower)"
        val r = PrimeLine.insert(p, settings("plan.mechanism.adhesion.prime.line" to "tab"))
        r.note shouldStartWith "on a peel tab ("
        val made = added(p, r.program)
        made[0].feature shouldBe "Prime line"
        val tab = made.drop(1)
        tab.map { it.feature }.distinct() shouldBe listOf("Brim")
        // one spacing apart (the brim's bead spacing), from the line in to the print, the innermost touching the wall
        val sp = w - Math.round(h * (1 - Math.PI / 4))
        val ys = tab.map { e -> pts(e.xy).map { it.second }.distinct().single() }
        ys.zipWithNext().forEach { (a, b) -> a - b shouldBe -sp }
        val lineY = pts(made[0].xy).first().second
        ys.first() shouldBe lineY + sp
        (100_000 - w - ys.last()).let { gap -> withClue("innermost's air to the wall: $gap") { (gap >= -1 && gap < sp) shouldBe true } }
        tab.size shouldBe ((s.primeGapUm + w / 2 - (s.brimGapUm + w / 2) + 1) / sp).toInt()
        // only under the line: no further out along x than the line's ends
        val lx = pts(made[0].xy).map { it.first }
        for (e in tab) for ((px, _) in pts(e.xy)) withClue("x $px") { (px >= lx.min() - 1 && px <= lx.max() + 1) shouldBe true }
        // alternating direction (no long travel back between them)
        tab.zipWithNext().forEach { (a, b) -> (a.xy[0] < a.xy[a.xy.size - 2]) shouldNotBe (b.xy[0] < b.xy[b.xy.size - 2]) }
    }

    "none: with a prime tower (the default), plan.mechanism.adhesion.prime.enabled = false, or a program that primes itself; a tower with plan.mechanism.adhesion.prime.line = near still gets one" {
        val tower = Extrude("Prime tower", rect(200_000, 150_000, 220_000, 170_000), true, h, w, h)
        val withTower = program("box" to listOf(wall(boxWall), tower))
        PrimeLine.insert(withTower, s).let { it.program shouldBe withTower; it.note shouldBe "none (a prime tower primes)" }
        val plain = program("box" to listOf(wall(boxWall)))
        PrimeLine.insert(plain, settings("plan.mechanism.adhesion.prime.enabled" to false)).program shouldBe plain
        val self = program("box" to listOf(wall(boxWall)), selfPrimed = true)
        PrimeLine.insert(self, s).program shouldBe self
        // control: opted in with a tower, it is laid
        added(withTower, PrimeLine.insert(withTower, settings("plan.mechanism.adhesion.prime.line" to "near")).program).single().feature shouldBe "Prime line"
    }

    "the front off the bed: the back instead; control: the same box clear of the edge takes the front" {
        val atEdge = rect(100_000, 3_000, 130_000, 23_000)
        val p = program("box" to listOf(wall(atEdge)))
        val r = PrimeLine.insert(p, near)
        r.note shouldContain "back"
        pts(added(p, r.program).single().xy).forEach { (_, y) -> y shouldBe 23_000 + w + s.primeGapUm }
        PrimeLine.insert(program("box" to listOf(wall(rect(100_000, 13_000, 130_000, 33_000)))), near).note shouldContain "front"
    }

    "another object within the clearance in front: the back instead, on the FIRST object; control: that object further off" {
        val lineY = 100_000 - w - s.primeGapUm
        fun other(gapUm: Long) = "b" to listOf(wall(rect(100_000, 80_000, 130_000, lineY - w - gapUm)))
        val blocked = PrimeLine.insert(program("box" to listOf(wall(boxWall)), other(PrimeLine.CLEAR_UM - 100)), s)
        blocked.note shouldContain "back"
        PrimeLine.insert(program("box" to listOf(wall(boxWall)), other(PrimeLine.CLEAR_UM + 100)), s).note shouldContain "front"
    }

    "an edge that turns in between two feet: that side skipped, the line never under the span; control: the box takes the front" {
        // two 10 mm feet joined at the back, the 20 x 10 mm notch between them open to the front (an arch's footprint
        // seen from above; wider than twice the line's distance out, so the offset leaves it open): the front's middle
        // line meets the edge at the notch's bottom first
        val u = longArrayOf(100_000, 100_000, 110_000, 100_000, 110_000, 110_000, 130_000, 110_000, 130_000, 100_000, 140_000, 100_000, 140_000, 120_000, 100_000, 120_000)
        val p = program("box" to listOf(wall(u)))
        val r = PrimeLine.insert(p, near)
        r.note shouldContain "back"
        val line = added(p, r.program).single()
        // nowhere in the notch, nor in front of it short of the feet's front edge
        for ((px, py) in pts(line.xy)) withClue("($px, $py)") { (px in 110_000..130_000 && py < 110_000) shouldBe false }
        // control: the same outline without the notch takes the front
        PrimeLine.insert(program("box" to listOf(wall(boxWall))), near).note shouldContain "front"
    }

    "no room on any side: none, and the note says why" {
        // a box with objects close on all four sides
        val lineGap = w + s.primeGapUm
        val around = listOf(
            rect(90_000, 80_000, 140_000, 100_000 - lineGap - w - 500), rect(90_000, 120_000 + lineGap + w + 500, 140_000, 140_000),
            rect(80_000, 90_000, 100_000 - lineGap - w - 500, 130_000), rect(130_000 + lineGap + w + 500, 90_000, 150_000, 130_000))
        val p = program("box" to listOf(wall(boxWall)), *around.mapIndexed { k, r -> "o$k" to listOf(wall(r)) }.toTypedArray())
        val r = PrimeLine.insert(p, s)
        r.program shouldBe p
        r.note shouldStartWith "none: no room"
    }
})
