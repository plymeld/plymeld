package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.commands.TechniqueSamples.box
import plymeld.commands.TechniqueSamples.threeMf
import java.nio.file.Files
import java.nio.file.Path

/**
 * A line laid no faster than it can hold a steady speed over a share of its length (`plan.mechanism.motion.steady.share`),
 * read from the g-code that `plymeld slice` writes, by [LineRuns]: every printed line's length from its own moves, the
 * acceleration the file sets while it is laid, and its feeds. The bound is the test's own, from that geometry alone: a line
 * L long starting and stopping at rest at acceleration a holds a steady v over L - v²/a, at least the share s of L when
 * v <= sqrt(a L (1 - s)).
 *
 * Each case is sliced twice, the setting unset (the control) and set; the planned lines are the same, so the files' lines
 * pair one to one. In the set slice every line is fed no faster than its bound; some line is slower than in the control
 * (the cap bites); a line whose bound is over its control speed is laid exactly as in the control (long lines untouched);
 * and in the control some line is faster than its bound (without the setting the check would fail). The purge tower's
 * lines and the prime line are left as they are (laid exactly as in the control). Cases: the hole block; two square boxes
 * with scarf seams on every outer wall (a ramp, the loop, and its closing pass are one line, none of it faster than the
 * line's bound, the ramps still at their own lower speed); an open section of the hole block (the bound from the pieces
 * laid inside the box); and an object of two filaments on a tool changer, with its purge tower, lines of both filaments
 * capped. The shares are the tests' own, high enough that each model has lines under their own speed. The minimum layer
 * time's slow-down is off in those cases: it scales a layer's speeds by its time, which the cap changes. Also: a line of no
 * length (a star's tips give some) gets no cap, so no move is written at a feed of 0 or below and no NaN or Infinity
 * anywhere, the slow-down on; and a share of 100 % is refused, saying why.
 */
class SteadyShareTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val block = root.resolve("test-models/holeblock.stl")
    val exempt = setOf("Prime tower", "Prime line")

    /** The slice's exit code, what it said, and its g-code's lines (empty when it wrote none). */
    fun slice(cf: List<String>, models: List<Path>, cv: List<String>): Triple<Int, String, List<String>> {
        val out = Files.createTempDirectory("plymeld-steady").resolve("out.gcode")
        val said = StringBuilder()
        val code = Commands.invoke(listOf("slice") + cf.flatMap { listOf("-cf", it) } + cv.flatMap { listOf("-cv", it) } +
            models.flatMap { listOf("-m", it.toString()) } + listOf("-o", out.toString()), { said.appendLine(it) })
        return Triple(code, said.toString(), if (Files.exists(out)) Files.readAllLines(out) else emptyList())
    }
    fun lines(cf: List<String>, models: List<Path>, cv: List<String>): List<LineRuns.Run> {
        val (code, said, g) = slice(cf, models, cv)
        withClue(said) { code shouldBe 0 }
        return LineRuns.runs(g)
    }

    /**
     * A star of [points] tips, [rIn] and [rOut] mm from its center (110, 110), turned [rot] rad, [h] mm tall, as an ASCII STL
     * (a fan from the center; coordinates to 6 decimals).
     */
    fun starStl(points: Int, rIn: Double, rOut: Double, rot: Double, h: Double): String {
        val c = 110.0
        val ring = (0 until 2 * points).map { k -> val r = if (k % 2 == 0) rOut else rIn; val a = rot + Math.PI * k / points
            c + r * Math.cos(a) to c + r * Math.sin(a) }
        fun v(x: Double, y: Double, z: Double) = String.format(java.util.Locale.ROOT, "vertex %.6f %.6f %.6f\n", x, y, z)
        return buildString {
            append("solid star\n")
            fun facet(vararg p: Triple<Double, Double, Double>) {
                append("facet normal 0 0 0\nouter loop\n"); p.forEach { append(v(it.first, it.second, it.third)) }; append("endloop\nendfacet\n") }
            for (i in ring.indices) {
                val (ax, ay) = ring[i]; val (bx, by) = ring[(i + 1) % ring.size]
                facet(Triple(c, c, 0.0), Triple(bx, by, 0.0), Triple(ax, ay, 0.0))
                facet(Triple(c, c, h), Triple(ax, ay, h), Triple(bx, by, h))
                facet(Triple(ax, ay, 0.0), Triple(bx, by, 0.0), Triple(bx, by, h))
                facet(Triple(ax, ay, 0.0), Triple(bx, by, h), Triple(ax, ay, h))
            }
            append("endsolid star\n")
        }
    }

    /** [share]'s bound for a line laid as [r] (mm/s). */
    fun bound(r: LineRuns.Run, share: Double) = Math.sqrt(r.accel * r.lengthMm * (1 - share))

    /** The checks above on [capped] (sliced with [share]) against [free] (unset), for case [what]; the lines the cap slowed. */
    fun check(what: String, capped: List<LineRuns.Run>, free: List<LineRuns.Run>, share: Double): List<LineRuns.Run> {
        val slowed = ArrayList<LineRuns.Run>()
        capped.size shouldBe free.size
        var bites = 0; var untouched = 0; var overInFree = 0; var exemptN = 0
        for (i in capped.indices) {
            val r = capped[i]; val c = free[i]
            withClue("$what, line $i (${r.feature}, layer ${r.layer})") {
                (Math.abs(r.lengthMm - c.lengthMm) < 1e-6 && r.feature == c.feature) shouldBe true     // the same line
                if (r.feature in exempt) { exemptN++; r.feeds shouldBe c.feeds; return@withClue }
                r.accel.isNaN() shouldBe false
                val b = bound(r, share)
                // a feed is written in whole mm/min; the length from coordinates rounded to 1 µm
                (r.fastest <= b * 1.002 + 0.01) shouldBe true
                if (c.fastest > b * 1.01 + 0.1) overInFree++
                if (r.fastest < c.fastest - 0.5) { bites++; slowed += r; if (c.feeds.distinct().size == 1) r.feeds.distinct().size shouldBe 1 }
                if (b > c.fastest * 1.01 + 0.1) { untouched++; r.feeds shouldBe c.feeds }
            }
        }
        println("$what: ${capped.size} lines, $bites capped, $untouched untouched (bound over their speed), $exemptN purge or prime lines as they were; unset, $overInFree faster than the bound")
        (bites > 0) shouldBe true
        (untouched > 0) shouldBe true
        (overInFree > 0) shouldBe true
        return slowed
    }

    val base = listOf("plan.pose=as-given", "plan.placement=center", "hardware.machine.example-corexy.firmware.arcs=false",
        "hardware.filament.pla-white.cooling.slowdown.enabled=false")

    "the hole block: every line within its bound, short lines capped, long ones untouched; control: unset, short lines over it" {
        // 90 %: the block's lines are mostly long (its infill one zigzag a piece); at 50 % none was short enough to cap
        check("hole block", lines(cfs, listOf(block), base + "plan.mechanism.motion.steady.share=90%"), lines(cfs, listOf(block), base), 0.9)
    }

    "scarf seams: a ramp, its loop, and its closing pass are one line, none of it over the line's bound" {
        // two square boxes, 20 and 6 mm, as one object: their outer walls are of one width, so they take a scarf (the hole
        // block's walls vary in width, and a loop of varying width is laid without one)
        val boxes = Files.createTempDirectory("plymeld-steady-scarf").resolve("boxes.stl").also {
            Files.writeString(it, TechniqueSamples.stl(box(100.0, 100.0, 0.0, 120.0, 120.0, 2.0) + box(130.0, 100.0, 0.0, 136.0, 106.0, 2.0)))
        }
        val scarf = base + listOf("plan.technique.scarf_seam.enabled=true", "plan.technique.scarf_seam.where=all")
        // 97 %: the small box's scarfed walls (about 32 mm laid) come under their own speed, the large box's (about 88) do not
        val capped = lines(cfs, listOf(boxes), scarf + "plan.mechanism.motion.steady.share=97%")
        val free = lines(cfs, listOf(boxes), scarf)
        // the scarfed walls are read as one line each (several feeds: the ramps at the scarf's speed), and some are capped
        val slowed = check("two boxes, scarf seams", capped, free, 0.97)
        val scarfed = capped.filter { it.feature == "Outer wall" && it.layer > 1 && it.feeds.distinct().size > 1 }
        (scarfed.isNotEmpty()) shouldBe true
        (slowed.any { it in scarfed }) shouldBe true
    }

    "an open section: the bound from each piece laid inside the box" {
        val key = "model.object.\"holeblock.stl\".section"
        val b = plymeld.mesh.StlReader.read(block).bounds()
        val box = listOf("$key.min=${b[0] - 1} ${b[1] - 1} ${b[2] - 1} mm", "$key.max=${(b[0] + b[3]) / 2} ${(b[1] + b[4]) / 2} ${b[5] + 1} mm")
        val capped = lines(cfs, listOf(block), base + box + "plan.mechanism.motion.steady.share=50%")
        check("open section", capped, lines(cfs, listOf(block), base + box), 0.5)
        // the walls cut by the box are laid as pieces: open lines shorter than the whole block's loops (control: whole, 100 mm or more)
        val whole = lines(cfs, listOf(block), base).filter { it.feature == "Outer wall" }.maxOf { it.lengthMm }
        (capped.filter { it.feature == "Outer wall" }.maxOf { it.lengthMm } < whole - 20) shouldBe true
    }

    /** A tool changer of two tools, each its own filament (fa, fb), and the object "duo" of two parts, one on each: TEST VALUES. */
    val toolChangerHw = """
        [hardware.machine.m]
        model = "flashforge-c5"
        build.x = "250 mm"
        build.y = "250 mm"
        build.z = "250 mm"
        motion.travel.speed = "300 mm/s"
        motion.print.speed = "300 mm/s"
        motion.print.acceleration = "5000 mm/s2"
        motion.travel.acceleration = "= machine.motion.print.acceleration"
        firmware.flavor = "klipper"
        firmware.arcs = "false"
        firmware.exclude_object = "false"
        gcode.start = "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}"
        gcode.end = "M104 S0"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "fa"
        tool.t2.filament = "fb"
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        [hardware.tool.t2]
        like = "t1"
        index = "2"
        [hardware.filament.fa]
        like = "pla-generic"
        color = "#FF0000"
        cooling.slowdown.enabled = "false"
        [hardware.filament.fb]
        like = "pla-generic"
        color = "#0000FF"
        cooling.slowdown.enabled = "false"
        [method.search]
        log.enabled = "false"
        [plan]
        pose = "as-given"
        placement = "as-given"
        bond_cooling = "off"
        technique.flat.heights = "one"
        technique.flat.layer.height = "0.2 mm"
        [model.object.duo.part.duo_pa]
        slot = "1"
        [model.object.duo.part.duo_pb]
        slot = "2"
    """.trimIndent()

    /** The object "duo" in [d]: each part a 20 mm box (long lines) and a 4 mm one (short lines). */
    fun duoModel(d: Path): Path = d.resolve("duo.3mf").also { threeMf(it, "duo", listOf(
        "pa" to box(100.0, 110.0, 0.0, 120.0, 130.0, 3.0) + box(100.0, 140.0, 0.0, 104.0, 144.0, 3.0),
        "pb" to box(135.0, 110.0, 0.0, 155.0, 130.0, 3.0) + box(135.0, 140.0, 0.0, 139.0, 144.0, 3.0))) }

    "an object of two filaments on a tool changer: both filaments' lines within their bound, the purge tower as it was" {
        val hw = toolChangerHw
        val d = Files.createTempDirectory("plymeld-steady-duo")
        val cf = d.resolve("hw.toml").also { Files.writeString(it, hw) }.toString()
        val duo = duoModel(d)
        val capped = lines(listOf(cf), listOf(duo), listOf("plan.mechanism.motion.steady.share=90%"))
        val free = lines(listOf(cf), listOf(duo), emptyList())
        val slowed = check("two filaments", capped, free, 0.9)
        (slowed.map { it.tool }.toSet()) shouldBe setOf(0, 1)                                       // both filaments' lines capped
        (capped.any { it.feature == "Prime tower" }) shouldBe true                                  // and the tower is there, as it was
    }

    // A line of no length: a thin area's center line along the outline whose points come out as one (here at a five-pointed
    // star's tips; on a real print, a lettering's). Its cap was sqrt(a * 0 * (1 - share)) = 0: written G1 F0, a feed at which
    // the printer stopped the print, and the preheat's estimate of it 0 / 0, "NaN s". The star is the test's own shape, found
    // to give such lines (inner radius 1 mm, outer 10, turned 0.13 rad, 1 mm tall, the sample config).
    "a line of no length gets no cap: no feed of 0 or below, no NaN or Infinity; the line is there (the probe)" {
        val star = Files.createTempDirectory("plymeld-steady-star").resolve("star.stl").also { Files.writeString(it, starStl(5, 1.0, 10.0, 0.13, 1.0)) }
        val cv = listOf("plan.pose=as-given", "plan.placement=center", "hardware.machine.example-corexy.firmware.arcs=false", "plan.mechanism.motion.steady.share=90%")
        val (code, said, g) = slice(cfs, listOf(star), cv)
        withClue(said) { code shouldBe 0 }
        // the probe: a feed line followed by a retraction, nothing laid between (a line of no length), is in the file
        val empty = g.indices.filter { i -> Regex("^G1 F\\S+$").matches(g[i].trim()) &&
            g.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith(";") }?.trim()?.startsWith("G1 E-") == true }
        println("star, steady share 90 %: ${g.size} lines, ${empty.size} lines of no length (${empty.take(3).joinToString { "line ${it + 1}: ${g[it]}" }})")
        (empty.isNotEmpty()) shouldBe true
        // every move's feed a number above 0, read from its F word
        val dead = g.withIndex().filter { (_, l) -> val c = l.substringBefore(';').trim()
            Regex("^G[0-3]\\s").containsMatchIn(c) && Regex("(?<=\\s)F(\\S*)").findAll(c).any { !((it.groupValues[1].toDoubleOrNull() ?: Double.NaN) > 0) } }
        dead.map { (i, l) -> "line ${i + 1}: $l" } shouldBe emptyList()
        g.withIndex().filter { (_, l) -> Regex("NaN|Infinity").containsMatchIn(l) }.map { (i, l) -> "line ${i + 1}: $l" } shouldBe emptyList()
    }

    // A sweep: the synthetic models above (the hole block, its open section, the star, the two-filament object on a tool
    // changer), each with the steady share at 0 %, 50 %, and 99 %, and with short walls on (a floor speed, both lengths) and
    // the share at 50 %, the slow-down on: every slice passes its pre-print check, and every line of every file passes the
    // bad-number rule (BadNumbers, as the writer, the pre-print check, and `plymeld printer send` apply it), its comments
    // too. Control: a file with a bad line planted is caught, at that line.
    "a sweep of the caps by length: no output holds a number a printer may refuse; control: a planted bad line is caught" {
        val d = Files.createTempDirectory("plymeld-steady-sweep")
        val star = d.resolve("star.stl").also { Files.writeString(it, starStl(5, 1.0, 10.0, 0.13, 1.0)) }
        val hwCf = d.resolve("hw.toml").also { Files.writeString(it, toolChangerHw) }.toString()
        val duo = duoModel(d)
        val key = "model.object.\"holeblock.stl\".section"
        val b = plymeld.mesh.StlReader.read(block).bounds()
        val section = listOf("$key.min=${b[0] - 1} ${b[1] - 1} ${b[2] - 1} mm", "$key.max=${(b[0] + b[3]) / 2} ${(b[1] + b[4]) / 2} ${b[5] + 1} mm")
        val sample = listOf("plan.pose=as-given", "plan.placement=center", "hardware.machine.example-corexy.firmware.arcs=false")
        class Case(val name: String, val cf: List<String>, val model: Path, val cv: List<String>)
        val models = listOf(Case("hole block", cfs, block, sample), Case("open section", cfs, block, sample + section),
            Case("star", cfs, star, sample), Case("two filaments", listOf(hwCf), duo, emptyList()))
        val shortWalls = listOf("plan.mechanism.motion.short_walls.length=5 mm", "plan.mechanism.motion.short_walls.full_length=20 mm", "plan.mechanism.motion.short_walls.speed=10 mm/s")
        val settings = listOf("share 0 %" to listOf("plan.mechanism.motion.steady.share=0%"), "share 50 %" to listOf("plan.mechanism.motion.steady.share=50%"),
            "share 99 %" to listOf("plan.mechanism.motion.steady.share=99%"), "short walls, share 50 %" to shortWalls + "plan.mechanism.motion.steady.share=50%")
        var some: List<String>? = null
        for (m in models) for ((what, cv) in settings) {
            val (code, said, g) = slice(m.cf, listOf(m.model), m.cv + cv)
            withClue("${m.name}, $what: $said") { code shouldBe 0 }
            val bad = plymeld.gcode.BadNumbers.scan(g.asSequence(), comments = true)
            println("${m.name}, $what: ${g.size} lines, ${bad.size} with a bad number")
            withClue("${m.name}, $what") { bad.map { (ln, s) -> "line $ln: $s" } shouldBe emptyList() }
            if (some == null) some = g
        }
        // control: one line of the first file made a dead feed, and another's preheat-like comment NaN: both caught, at their lines
        val g = some!!.toMutableList()
        val at = g.indexOfFirst { Regex("^G1 F\\d+$").matches(it) }
        g[at] = "G1 F0"; g.add(at + 1, "M104 S210 T1 ; preheat, NaN s of printing before the change")
        plymeld.gcode.BadNumbers.scan(g.asSequence(), comments = true).map { it.first } shouldBe listOf(at + 1, at + 2)
    }

    "a share of 100 % is refused, saying why; control: 99 % is accepted" {
        val (code, said, g) = slice(cfs, listOf(block), base + "plan.mechanism.motion.steady.share=100%")
        (code != 0) shouldBe true
        said shouldContain "plan.mechanism.motion.steady.share is 100 %"
        g.size shouldBe 0
        slice(cfs, listOf(block), base + "plan.mechanism.motion.steady.share=99%").first shouldBe 0
    }
})
