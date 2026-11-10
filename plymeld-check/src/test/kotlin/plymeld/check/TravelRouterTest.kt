package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.gcode.KlipperWriter
import plymeld.gcode.TravelRouter
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.PrintProgram
import plymeld.program.Step
import java.io.StringWriter

/**
 * Travels around the layer's other islands (plan.mechanism.motion.avoid_crossing, TravelRouter; strings
 * from a purge tower to a part embedded themselves in another part between them). Three squares on one layer: A (x 0-10),
 * B (x 20-30, y -5..5) and C (x 40-50), printed A, C, B. The travel from A's loop to C's start runs along y = 0, through B:
 * with the switch on, the written travel goes around B (no travel segment enters B's square); with it off, straight
 * through (the control). The router's own cases: a detour's length cap, and an island holding the travel's end is not
 * avoided.
 */
class TravelRouterTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.mechanism.adhesion.prime.enabled" to false, "hardware.filament.pla-generic.retract.travel.min" to "0 mm")
    val box = ModelObject("box", listOf("box"), listOf(1), "box.3mf")
    fun settings(extra: List<Pair<String, Any>>): SliceSettings {
        val all = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        return SliceSettings.of(ConfigSetup.setup(Config(ConfigStack(all), listOf(box))).setup)
    }
    fun square(x0: Long, y0: Long, x1: Long, y1: Long) = Extrude("Outer wall", longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), true, 200, 440, 200)
    val a = square(0, 0, 10_000, 10_000); val c = square(40_000, 0, 50_000, 10_000); val b = square(20_000, -5_000, 30_000, 5_000)
    val steps: List<Step> = listOf(LayerStart(0, 200, 200), ObjectStart("box"), a, c, b, ObjectEnd("box"))
    fun gcode(on: Boolean) = StringWriter().also { KlipperWriter(settings(listOf("plan.mechanism.motion.avoid_crossing" to on)), emptyMap(), "test").write(PrintProgram(steps, emptyList()), it) }.toString()
    /** The travel from A to C as XY points (mm): the moves without E between A's last line and C's first. */
    fun travel(g: String): List<DoubleArray> {
        val lines = g.lines()
        val endA = lines.indexOfLast { it.startsWith("G1") && " E" in it && it.contains("X0 Y0") || it.startsWith("G1 X0 Y0 E") }
        val startC = lines.indexOfFirst { it.startsWith("G1") && " E" in it && (it.startsWith("G1 X50 Y0 E") || it.startsWith("G1 X50 Y0 ")) }
        require(endA in 0 until startC) { "A's last line / C's first not found ($endA, $startC)" }
        return lines.subList(endA + 1, startC).filter { it.startsWith("G1") && " E" !in it && " X" in it }.map { l ->
            doubleArrayOf(Regex("X(-?[0-9.]+)").find(l)!!.groupValues[1].toDouble(), Regex("Y(-?[0-9.]+)").find(l)!!.groupValues[1].toDouble()) }
    }
    /** Whether the segment p -> q enters B's square (20..30 x -5..5 mm), by sampling. */
    fun entersB(p: DoubleArray, q: DoubleArray) = (0..200).any { k -> val t = k / 200.0; val x = p[0] + (q[0] - p[0]) * t; val y = p[1] + (q[1] - p[1]) * t; x > 20.0 && x < 30.0 && y > -5.0 && y < 5.0 }
    fun crossesB(pts: List<DoubleArray>): Boolean { var prev = doubleArrayOf(0.0, 0.0); for (p in pts) { if (entersB(prev, p)) return true; prev = p }; return false }

    "on: the travel from A to C goes around B; off: straight through it (the control)" {
        val on = travel(gcode(true))
        (on.size >= 2) shouldBe true
        crossesB(on) shouldBe false
        on.last().toList() shouldBe listOf(40.0, 0.0)
        val off = travel(gcode(false))
        crossesB(off) shouldBe true
    }
    // the head's place is kept across layers (found in review: the first travel of a layer, from the purge tower's base or the
    // last island of the layer below, was never routed); islands are also the open outer-wall pieces a scarfed loop is laid in
    "a travel from the last path of one layer to the first of the next is routed; a loop laid as three open pieces is an island" {
        val two: List<Step> = listOf(LayerStart(0, 200, 200), ObjectStart("box"), c, ObjectEnd("box"), LayerStart(1, 400, 200), ObjectStart("box"), a, b, ObjectEnd("box"))
        val routed = TravelRouter.route(PrintProgram(two, emptyList())).steps.filterIsInstance<Extrude>()
        val aOnLayer1 = routed.first { it.xy[0] == 0L }                             // a, on layer 1
        (aOnLayer1.via != null) shouldBe true                               // c's end (40, 0) -> a's start (0, 0) goes around b
        aOnLayer1.via!!.toList().chunked(2).all { (x, y) -> !(x > 20_000 && x < 30_000 && y > -5_000 && y < 5_000) } shouldBe true
        // b as a scarfed loop: three open pieces end to end (Scarf.extrudes lays up, rest, down)
        val up = Extrude("Outer wall", longArrayOf(20_000, -5_000, 30_000, -5_000), false, 200, 440, 200)
        val rest = Extrude("Outer wall", longArrayOf(30_000, -5_000, 30_000, 5_000, 20_000, 5_000, 20_000, -5_000), false, 200, 440, 200)
        val down = Extrude("Outer wall", longArrayOf(20_000, -5_000, 30_000, -5_000), false, 200, 440, 200)
        val scarfed: List<Step> = listOf(LayerStart(0, 200, 200), ObjectStart("box"), a, c, up, rest, down, ObjectEnd("box"))
        val cRouted = TravelRouter.route(PrintProgram(scarfed, emptyList())).steps.filterIsInstance<Extrude>().first { it.xy[0] == 40_000L }
        (cRouted.via != null) shouldBe true
        // control: the pieces not chained (the rest reversed) make no island, so the travel flies straight
        val restBack = Extrude("Outer wall", longArrayOf(20_000, -5_000, 20_000, 5_000, 30_000, 5_000, 30_000, -5_000), false, 200, 440, 200)
        val unchained: List<Step> = listOf(LayerStart(0, 200, 200), ObjectStart("box"), a, c, up, restBack, ObjectEnd("box"))
        (TravelRouter.route(PrintProgram(unchained, emptyList())).steps.filterIsInstance<Extrude>().first { it.xy[0] == 40_000L }.via == null) shouldBe true
    }
    "an island holding the travel's end is not avoided; a detour past the cap is not taken" {
        val hb = TravelRouter.hull(b.xy, 1000)!!
        TravelRouter.detour(0.0, 0.0, 25_000.0, 0.0, listOf(hb)) shouldBe null          // ends inside B
        (TravelRouter.detour(0.0, 0.0, 40_000.0, 0.0, listOf(hb)) != null) shouldBe true
        val bar = TravelRouter.hull(longArrayOf(20_000, -500_000, 30_000, -500_000, 30_000, 500_000, 20_000, 500_000), 1000)!!
        TravelRouter.detour(0.0, 0.0, 40_000.0, 0.0, listOf(bar)) shouldBe null          // a kilometre around: the straight hop stays
    }

    // within one island: the travel stays inside it (combing; travels across an engraved name's notches strung).
    // A U (x 0..30, y 0..20, the notch x 10..20 above y 5); a path ending on its left arm (5, 18), the next starting on its
    // right arm (25, 18): the straight travel crosses the notch, the routed one goes down and through the base
    val u = Extrude("Outer wall", longArrayOf(0, 0, 30_000, 0, 30_000, 20_000, 20_000, 20_000, 20_000, 5_000, 10_000, 5_000, 10_000, 20_000, 0, 20_000), true, 200, 440, 200)
    fun inner(vararg xy: Long) = Extrude("Inner wall", xy, false, 200, 440, 200)
    fun routedVia(island: Extrude, from: Extrude, to: Extrude): LongArray? =
        TravelRouter.route(PrintProgram(listOf(LayerStart(0, 200, 200), ObjectStart("box"), island, from, to, ObjectEnd("box")), emptyList()))
            .steps.filterIsInstance<Extrude>().last().via
    /** Whether a -> b passes through the notch (x 10..20, y 5..20 mm, µm), by sampling. */
    fun inNotch(ax: Long, ay: Long, bx: Long, by: Long) = (0..400).any { k -> val t = k / 400.0; val x = ax + (bx - ax) * t; val y = ay + (by - ay) * t; x > 10_000 && x < 20_000 && y > 5_000 && y < 20_000 }

    "within a U-shaped island the travel from one arm to the other stays inside it, round through the base" {
        val via = routedVia(u, inner(4_000, 10_000, 5_000, 18_000), inner(25_000, 18_000, 26_000, 10_000))
        (via != null) shouldBe true
        val pts = listOf(5_000L, 18_000L) + via!!.toList() + listOf(25_000L, 18_000L)
        var crossed = false
        for (k in 0 until pts.size / 2 - 1) if (inNotch(pts[2 * k], pts[2 * k + 1], pts[2 * k + 2], pts[2 * k + 3])) crossed = true
        crossed shouldBe false
        inNotch(5_000, 18_000, 25_000, 18_000) shouldBe true                          // the straight travel did cross it (the control)
        // through the base: some via point below the notch's floor (y 5), each at least the inset (one outer wall, 0.44 mm) inside
        (0 until via.size / 2).any { via[2 * it + 1] < 5_000 } shouldBe true
        (0 until via.size / 2).all { k -> via[2 * k] >= 440 && via[2 * k] <= 29_560 && via[2 * k + 1] >= 440 } shouldBe true
    }
    "the same travel over a solid island, and a travel that stays inside the U, go straight (the controls)" {
        val solid = Extrude("Outer wall", longArrayOf(0, 0, 30_000, 0, 30_000, 20_000, 0, 20_000), true, 200, 440, 200)
        routedVia(solid, inner(4_000, 10_000, 5_000, 18_000), inner(25_000, 18_000, 26_000, 10_000)) shouldBe null
        routedVia(u, inner(4_000, 1_000, 5_000, 2_000), inner(25_000, 2_000, 26_000, 1_000)) shouldBe null
    }

    // a speck (an outer-wall line kept because it lines the outline) just past the U's right arm, 0.3 mm above its top edge:
    // the travel to it from the left arm goes round through the base, and only its last hop, out to the speck, crosses the
    // wall
    "a travel to a speck just outside the island's wall stays inside up to one short hop out to it; 2 mm out, it is left straight (the control)" {
        fun speck(y: Long) = Extrude("Outer wall", longArrayOf(25_000, y, 25_200, y), false, 200, 440, 200)
        val via = routedVia(u, inner(4_000, 10_000, 5_000, 18_000), speck(20_300))
        (via != null) shouldBe true
        val pts = listOf(5_000L, 18_000L) + via!!.toList() + listOf(25_000L, 20_300L)
        val legs = pts.size / 2 - 1
        (0 until legs - 1).none { k -> inNotch(pts[2 * k], pts[2 * k + 1], pts[2 * k + 2], pts[2 * k + 3]) } shouldBe true
        (0 until via.size / 2).any { via[2 * it + 1] < 5_000 } shouldBe true                      // round through the base
        // the last hop: short (within one more outer wall of the region's reach) and not through the notch
        val hop = Math.hypot((pts[2 * legs] - pts[2 * legs - 2]).toDouble(), (pts[2 * legs + 1] - pts[2 * legs - 1]).toDouble())
        (hop <= 1_200.0) shouldBe true
        inNotch(pts[2 * legs - 2], pts[2 * legs - 1], pts[2 * legs], pts[2 * legs + 1]) shouldBe false
        routedVia(u, inner(4_000, 10_000, 5_000, 18_000), speck(22_000)) shouldBe null
        // only an open outer-wall line's end may lie past the walls: a support line in the speck's place is not combed to
        routedVia(u, inner(4_000, 10_000, 5_000, 18_000), Extrude("Support", longArrayOf(25_000, 20_300, 25_200, 20_300), false, 200, 440, 200)) shouldBe null
    }

    // along the wall a speck lines (strings across a raised name's letter gaps, where every inset region of the
    // letters' tips had thinned to nothing and combing left the travels between specks straight): a toothed loop, a base
    // y 0..5 with three teeth 6 mm wide up to y 15 (gaps x 6..12 and 18..24 above y 5), specks 0.3 mm past the first two
    // teeth's tips; the route between them goes in to the loop, along it down one tooth, across the base, up the next
    "a travel between specks past two teeth of a loop goes along the loop, never across the gap between them; 2 mm out it is not routed (the control)" {
        val teeth = plymeld.geom.Polygon(longArrayOf(0, 0, 30_000, 0, 30_000, 15_000, 24_000, 15_000, 24_000, 5_000, 18_000, 5_000, 18_000, 15_000,
            12_000, 15_000, 12_000, 5_000, 6_000, 5_000, 6_000, 15_000, 0, 15_000))
        val layer = TravelRouter.CombLayer(emptyList(), listOf(teeth), 440)
        fun inGap(ax: Double, ay: Double, bx: Double, by: Double) = (0..400).any { k -> val t = k / 400.0; val x = ax + (bx - ax) * t; val y = ay + (by - ay) * t
            x > 6_050 && x < 11_950 && y > 5_050 }
        inGap(3_000.0, 15_300.0, 15_000.0, 15_300.0) shouldBe true                     // the straight travel crosses the gap (the control)
        val via = TravelRouter.alongWall(3_000.0, 15_300.0, 15_000.0, 15_300.0, layer)!!
        val pts = listOf(3_000.0, 15_300.0) + via.map { it.toDouble() } + listOf(15_000.0, 15_300.0)
        (0 until pts.size / 2 - 1).none { k -> inGap(pts[2 * k], pts[2 * k + 1], pts[2 * k + 2], pts[2 * k + 3]) } shouldBe true
        // every route point on the loop's center line: in at (3, 15), the tooth's corner (6, 15), down to (6, 5), across to
        // (12, 5), up to (12, 15), out at (15, 15)
        via.toList().chunked(2).map { it[0] to it[1] } shouldBe listOf(3_000L to 15_000L, 6_000L to 15_000L, 6_000L to 5_000L, 12_000L to 5_000L, 12_000L to 15_000L, 15_000L to 15_000L)
        TravelRouter.alongWall(3_000.0, 15_300.0, 15_000.0, 17_000.0, layer) shouldBe null   // 2 mm past the tip: beyond 3 widths of the wall
        // through the router: two specks 0.3 mm past the same tooth's top, 2.3 mm apart; the straight travel crosses no wall
        // but runs over air all the way (in front of a raised name's letters), so it goes along the top's center line
        val loop = Extrude("Outer wall", teeth.xy, true, 200, 440, 200)
        fun speck(x: Long) = Extrude("Outer wall", longArrayOf(x, 15_300, x + 200, 15_300), false, 200, 440, 200)
        val routed = TravelRouter.route(PrintProgram(listOf(LayerStart(0, 200, 200), ObjectStart("box"), loop, speck(3_000), speck(5_500), ObjectEnd("box")), emptyList()))
            .steps.filterIsInstance<Extrude>().last()
        val along = routed.via!!.toList().chunked(2)
        along.all { it[1] == 15_000L } shouldBe true
        routed.retractBefore shouldBe true
        routed.hopOnly shouldBe false
        layer.overAir(3_200.0, 15_300.0, 5_500.0, 15_300.0) shouldBe true
        layer.overAir(3_200.0, 14_000.0, 5_500.0, 14_000.0) shouldBe false                    // the same inside the tooth (the control)
        // a speck is an open outer-wall line shorter than two of its widths; a longer line, a closed loop or another feature is not
        TravelRouter.isSpeck(Extrude("Outer wall", longArrayOf(0, 0, 300, 0), false, 200, 440, 200)) shouldBe true
        TravelRouter.isSpeck(Extrude("Outer wall", longArrayOf(0, 0, 900, 0), false, 200, 440, 200)) shouldBe false
        TravelRouter.isSpeck(Extrude("Inner wall", longArrayOf(0, 0, 300, 0), false, 200, 440, 200)) shouldBe false
    }

    // a thin U (an engraved name's notched shell, thinner than two insets, left its travels
    // straight across its face): arms x 0..0.6 and 29.4..30 mm, base y 0..0.6 mm, so the island shrunk by the full inset (one
    // outer wall, 0.44 mm) is empty; the travel from the left arm's middle (0.3, 18) to the right arm's (29.7, 18) runs down
    // the middle of the material
    val thinU = Extrude("Outer wall", longArrayOf(0, 0, 30_000, 0, 30_000, 20_000, 29_400, 20_000, 29_400, 600, 600, 600, 600, 20_000, 0, 20_000), true, 200, 440, 200)
    "within a U thinner than two insets the travel still stays inside it, down the middle of its arms and base" {
        val via = routedVia(thinU, inner(300, 10_000, 300, 18_000), inner(29_700, 18_000, 29_700, 10_000))
        (via != null) shouldBe true
        val pts = listOf(300L, 18_000L) + via!!.toList() + listOf(29_700L, 18_000L)
        /** Whether a -> b passes through the U's open middle (x 0.6..29.4, y above 0.6 mm), by sampling. */
        fun inGap(ax: Long, ay: Long, bx: Long, by: Long) = (0..400).any { k -> val t = k / 400.0; val x = ax + (bx - ax) * t; val y = ay + (by - ay) * t; x > 600 && x < 29_400 && y > 600 }
        (0 until pts.size / 2 - 1).none { k -> inGap(pts[2 * k], pts[2 * k + 1], pts[2 * k + 2], pts[2 * k + 3]) } shouldBe true
        inGap(300, 18_000, 29_700, 18_000) shouldBe true                              // the straight travel did cross it (the control)
        // every via point in the material and away from its edges: within the middle half of the 0.6 mm arm or base
        (0 until via.size / 2).all { k -> val x = via[2 * k]; val y = via[2 * k + 1]; (x in 150..450 || x in 29_550..29_850 || y in 150..450) } shouldBe true
    }

    // a speck's hop (Extrude.hopOnly): the travel from the U's loop to a speck just past its wall is
    // lifted over the wall but not retracted; the same travel to a path without the flag retracts (the control)
    "a speck's hop is lifted over its wall and not retracted; without the flag, or 2 mm out (no hop), the same travel retracts (the controls)" {
        fun travel(hop: Boolean, y: Long = 20_300): List<String> {
            val speck = Extrude("Outer wall", longArrayOf(25_000, y, 25_200, y), false, 200, 440, 200, hopOnly = hop)
            val g = StringWriter().also { KlipperWriter(settings(emptyList()), emptyMap(), "test").write(PrintProgram(listOf(LayerStart(0, 200, 200), ObjectStart("box"), u, speck, ObjectEnd("box")), emptyList()), it) }.toString().lines()
            // the lines between the loop's last extrusion and the speck's first
            val first = g.indexOfFirst { it.startsWith("G1 X25.2 Y" + (y / 1000.0).toString().removeSuffix(".0")) && " E" in it }
            val last = g.subList(0, first).indexOfLast { it.startsWith("G1") && Regex(" E[0-9]").containsMatchIn(it) && " X" in it }
            return g.subList(last + 1, first)
        }
        val hop = travel(true); val plain = travel(false)
        println("hop: $hop\nplain: $plain")
        hop.none { Regex(" E-").containsMatchIn(it) } shouldBe true                     // no retraction
        hop.any { Regex("^G1 .*Z0\\.[3-9]").containsMatchIn(it) } shouldBe true         // lifted above the layer (z 0.2)
        plain.any { Regex(" E-").containsMatchIn(it) } shouldBe true                    // the control retracts
        // flagged, but 2 mm out: not combed and no hop (Extrude.hops), so it retracts as any travel
        travel(true, 22_000).any { Regex(" E-").containsMatchIn(it) } shouldBe true
    }

    // a layer's first travel starts where the layer below ended, nothing of its own layer printed yet (combed in its
    // own layer's islands, a start outside their outline left 36 of the regression Benchy's travels straight over air). Layer 1:
    // the U, its last path ending on the left arm at (0.3, 18); layer 2: the U 1 mm narrower each side, so (0.3, 18) lies outside
    // its outline; the travel to layer 2's first path on the right arm goes round through layer 1's base
    "a layer's first travel stays inside the layer below's island, where the head is, though its start lies outside its own layer's" {
        val u2 = Extrude("Outer wall", longArrayOf(1_000, 1_000, 29_000, 1_000, 29_000, 20_000, 20_000, 20_000, 20_000, 5_000, 10_000, 5_000, 10_000, 20_000, 1_000, 20_000), true, 400, 440, 200)
        val to = Extrude("Inner wall", longArrayOf(25_000, 18_000, 26_000, 10_000), false, 400, 440, 200)
        val program = PrintProgram(listOf(LayerStart(0, 200, 200), ObjectStart("box"), u, inner(4_000, 10_000, 300, 18_000), ObjectEnd("box"),
            LayerStart(1, 400, 200), ObjectStart("box"), to, u2, ObjectEnd("box")), emptyList())
        val via = TravelRouter.route(program).steps.filterIsInstance<Extrude>().first { it === to || it.xy.contentEquals(to.xy) }.via
        (via != null) shouldBe true
        val pts = listOf(300L, 18_000L) + via!!.toList() + listOf(25_000L, 18_000L)
        (0 until pts.size / 2 - 1).none { k -> inNotch(pts[2 * k], pts[2 * k + 1], pts[2 * k + 2], pts[2 * k + 3]) } shouldBe true
        (0 until via.size / 2).any { via[2 * it + 1] < 5_000 } shouldBe true                      // round through the base
        inNotch(300, 18_000, 25_000, 18_000) shouldBe true                            // the straight travel did cross it (the control)
    }

    // the writer wipes after the router planned the travel from the path's end: the travel is planned again from where the wipe
    // left the head (77 of the regression Benchy's 123 travels across their own island's wall were a wipe's end cutting
    // back across the wall it had followed). The U's loop ends at (0, 0); a 15 mm wipe takes the head back up the left arm to
    // (0, 15); the next path starts in the base at (25, 2): straight from (0, 0) it stays in the base (so the router leaves it), from
    // (0, 15) it crosses the notch
    "a travel after a wipe is planned from where the wipe left the head: round the notch, not across it; avoid_crossing off crosses it (the control)" {
        // the wipe along the wall itself (not stepped inside it: wipe.inward), so it takes the head up the arm
        val wipe = listOf("hardware.filament.pla-generic.retract.wipe.enabled" to true, "hardware.filament.pla-generic.retract.wipe.distance" to "15 mm",
            "hardware.filament.pla-generic.retract.wipe.inward" to false)
        fun travelAfterWipe(on: Boolean): List<DoubleArray> {
            val steps = listOf(LayerStart(0, 200, 200), ObjectStart("box"), u, inner(25_000, 2_000, 26_000, 3_000), ObjectEnd("box"))
            val g = StringWriter().also { KlipperWriter(settings(wipe + ("plan.mechanism.motion.avoid_crossing" to on)), emptyMap(), "test").write(PrintProgram(steps, emptyList()), it) }.toString().lines()
            fun xy(l: String) = doubleArrayOf(Regex("X(-?[0-9.]+)").find(l)!!.groupValues[1].toDouble(), Regex("Y(-?[0-9.]+)").find(l)!!.groupValues[1].toDouble())
            val lastWipe = g.indexOfLast { it.startsWith("G1 X") && Regex(" E-").containsMatchIn(it) }
            require(lastWipe >= 0) { "no wipe written" }
            val pts = arrayListOf(xy(g[lastWipe]))
            for (l in g.subList(lastWipe + 1, g.size)) {
                if (l.startsWith("G1") && Regex(" E[0-9]").containsMatchIn(l) && " X" in l) break
                if (l.startsWith("G1") && " X" in l && " E" !in l) pts.add(xy(l))
            }
            return pts
        }
        fun crossesNotch(pts: List<DoubleArray>) = (0 until pts.size - 1).any { k ->
            inNotch(Math.round(pts[k][0] * 1000), Math.round(pts[k][1] * 1000), Math.round(pts[k + 1][0] * 1000), Math.round(pts[k + 1][1] * 1000)) }
        val routed = travelAfterWipe(true)
        // the wipe did take the head up the left arm
        (routed[0][0] < 0.01 && routed[0][1] > 10.0) shouldBe true
        crossesNotch(routed) shouldBe false
        (routed.last()[0] == 25.0 && routed.last()[1] == 2.0) shouldBe true
        crossesNotch(travelAfterWipe(false)) shouldBe true
    }
})
