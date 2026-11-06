package plymeld.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.config.ConfigStack
import java.nio.file.Files
import java.nio.file.Path

/** The product path: config + model -> plan -> plan file -> slice. */
class ProductTest : StringSpec({
    /** An ASCII STL box [x] x [y] x [z] mm, corner at the origin (12 outward-facing triangles). */
    fun box(x: Double, y: Double, z: Double): String {
        val v = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(x, 0.0, 0.0), doubleArrayOf(x, y, 0.0), doubleArrayOf(0.0, y, 0.0),
            doubleArrayOf(0.0, 0.0, z), doubleArrayOf(x, 0.0, z), doubleArrayOf(x, y, z), doubleArrayOf(0.0, y, z))
        val faces = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        return buildString {
            append("solid box\n")
            for (f in faces) { append("facet normal 0 0 0\nouter loop\n"); for (i in f) append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); append("endloop\nendfacet\n") }
            append("endsolid box\n")
        }
    }
    val hardware = """
        [hardware.machine.m]
        model = "qidi-q2"
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
        gcode.start = "M109 S{first_layer_nozzle_temperature}"
        gcode.end = "M104 S0"
        [hardware.tool.t1]
        machine = "m"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
    """.trimIndent()
    fun dir(vararg files: Pair<String, String>): Path = Files.createTempDirectory("plymeld-product").also { d -> for ((n, t) in files) Files.writeString(d.resolve(n), t) }
    fun product(d: Path, cfs: List<String>, model: String = "box.stl", said: MutableList<String> = ArrayList(), machine: String? = null) =
        Product(ConfigStack.load(cfs, emptyList(), d), listOf(d.resolve(model)), machine) { said += it }

    "plan -o writes a complete plan; slice with it decides nothing again" {
        val d = dir("hw.toml" to hardware, "box.stl" to box(20.0, 20.0, 10.0))
        val said = ArrayList<String>()
        product(d, listOf("hw.toml"), said = said).checkPlan()
        said shouldContain "no plan given: deciding now; run `plymeld plan -o FILE` to see and keep the decisions"
        val first = product(d, listOf("hw.toml"))
        val plan = first.decide()
        Files.writeString(d.resolve("plan.toml"), first.planFile(plan))
        val again = ArrayList<String>()
        val second = product(d, listOf("hw.toml", "plan.toml"), said = again)
        second.planComplete shouldBe true
        second.checkPlan()
        again.last() shouldContain "complete in the config, used as it is"
        val locked = second.decide()
        locked.decisions.all { it.overridden } shouldBe true
        // the same decisions: pose, bands, order, placement
        locked.layout.techniques shouldBe plan.layout.techniques
        locked.layout.placed.single().cx shouldBe plan.layout.placed.single().cx
        locked.layout.order shouldBe plan.layout.order
    }
    "a changed model or config is said; control: unchanged, nothing" {
        val d = dir("hw.toml" to hardware, "box.stl" to box(20.0, 20.0, 10.0), "tall.stl" to box(20.0, 20.0, 12.0))
        val first = product(d, listOf("hw.toml"))
        Files.writeString(d.resolve("plan.toml"), first.planFile(first.decide()))
        val same = ArrayList<String>(); product(d, listOf("hw.toml", "plan.toml"), said = same).checkPlan()
        same.none { "changed" in it } shouldBe true
        Files.writeString(d.resolve("box.stl"), box(20.0, 20.0, 12.0))
        val moved = ArrayList<String>(); product(d, listOf("hw.toml", "plan.toml"), said = moved).checkPlan()
        val modelNote = moved.single { "changed" in it }
        modelNote shouldContain "the model of 'box.stl' changed"
        Files.writeString(d.resolve("more.toml"), "[hardware.filament.pla-generic]\ntemperature.print = \"200 C\"\n")
        val hw = ArrayList<String>(); product(d, listOf("hw.toml", "more.toml", "plan.toml"), said = hw).checkPlan()
        val note = hw.first { line -> line.contains("config changed") }
        note shouldContain "(hardware)"
    }
    // flat layers: a flat band says heights = "one" (with its layer height) or "follow" (without one)
    "flat bands: one height and following the surfaces, each stated; the plan file writes both and reads back; mistakes refused" {
        val bands = """
            [plan.object."box.stl"]
            band.1.technique = "flat"
            band.1.heights = "one"
            band.1.layer.height = "0.2 mm"
            band.1.to = "4 mm"
            band.2.technique = "flat"
            band.2.heights = "follow"
            band.2.from = "4 mm"
        """.trimIndent()
        val d = dir("hw.toml" to hardware, "box.stl" to box(20.0, 20.0, 10.0), "bands.toml" to bands)
        val pr = product(d, listOf("hw.toml", "bands.toml"))
        val locks = pr.overrides().objects.single()
        locks.layerHeightMm shouldBe 0.2
        locks.techniques!!.bands.map { it.oneHeight to it.follow } shouldBe listOf(true to false, false to true)
        // the plan file writes each band's heights, the height on the band of one height only, and reads back as the same bands
        val plan = pr.decide()
        val file = pr.planFile(plan)
        file shouldContain "band.1.technique = \"flat\"\nband.1.heights = \"one\"\nband.1.layer.height = \"0.2 mm\""
        file shouldContain "band.2.technique = \"flat\"\nband.2.heights = \"follow\"\nband.2.from"
        Files.writeString(d.resolve("plan.toml"), file)
        val back = product(d, listOf("hw.toml", "plan.toml"))
        back.overrides().objects.single().techniques!!.bands.map { it.oneHeight to it.follow } shouldBe listOf(true to false, false to true)
        back.decide().layout.techniques shouldBe plan.layout.techniques
        // a layer height on a band that is not flat, and flat bands of two heights, are refused
        Files.writeString(d.resolve("curved.toml"), "[plan.object.\"box.stl\"]\nband.1.technique = \"curved\"\nband.1.layer.height = \"0.2 mm\"\n")
        io.kotest.assertions.throwables.shouldThrow<com.github.ajalt.clikt.core.UsageError> { product(d, listOf("hw.toml", "curved.toml")).overrides() }
            .message!! shouldContain "is for a flat band"
        Files.writeString(d.resolve("two.toml"), bands.replace("band.2.heights = \"follow\"", "band.2.heights = \"one\"\nband.2.layer.height = \"0.1 mm\""))
        io.kotest.assertions.throwables.shouldThrow<com.github.ajalt.clikt.core.UsageError> { product(d, listOf("hw.toml", "two.toml")).overrides() }
            .message!! shouldContain "flat bands of different layer heights"
        // plan.technique.flat.heights locks how the object's heights are set; control: unset, decided
        Files.writeString(d.resolve("one.toml"), "[plan.technique.flat]\nheights = \"one\"\n")
        product(d, listOf("hw.toml", "one.toml")).overrides().objects.single().flatHeights shouldBe plymeld.core.decide.FlatLayers.Heights.ONE
        product(d, listOf("hw.toml")).overrides().objects.single().flatHeights shouldBe null
    }
    // a flat band must say its heights, an error if omitted
    "a flat band's heights: omitted, one without a height, follow with one and heights on a curved band are refused; controls accepted" {
        val d = dir("hw.toml" to hardware, "box.stl" to box(20.0, 20.0, 10.0))
        fun bandsOf(vararg lines: String): List<Pair<Boolean, Boolean>> {
            Files.writeString(d.resolve("b.toml"), "[plan.object.\"box.stl\"]\n" + lines.joinToString("\n") + "\n")
            return product(d, listOf("hw.toml", "b.toml")).overrides().objects.single().techniques!!.bands.map { it.oneHeight to it.follow }
        }
        fun refused(vararg lines: String) = io.kotest.assertions.throwables.shouldThrow<com.github.ajalt.clikt.core.UsageError> { bandsOf(*lines) }.message!!
        // omitted: refused, saying what to write (a flat band without heights used to follow the surfaces)
        refused("band.1.technique = \"flat\"", "band.1.layer.height = \"0.2 mm\"") shouldContain
            "plan.object.box.stl.band.1 is flat but does not say how its layers' heights are set: write plan.object.box.stl.band.1.heights = \"one\""
        refused("band.1.technique = \"flat\"") shouldContain "or plan.object.box.stl.band.1.heights = \"follow\""
        // control: stated, accepted
        bandsOf("band.1.technique = \"flat\"", "band.1.heights = \"one\"", "band.1.layer.height = \"0.2 mm\"") shouldBe listOf(true to false)
        bandsOf("band.1.technique = \"flat\"", "band.1.heights = \"follow\"") shouldBe listOf(false to true)
        // one without a layer height: refused when nothing else gives one
        refused("band.1.technique = \"flat\"", "band.1.heights = \"one\"") shouldContain "heights = \"one\" without a layer height"
        // control: the object's locked plan.technique.flat.layer.height gives it (every flat band of one height takes it)
        Files.writeString(d.resolve("lh.toml"), "[plan.technique.flat]\nlayer.height = \"0.16 mm\"\n")
        Files.writeString(d.resolve("b.toml"), "[plan.object.\"box.stl\"]\nband.1.technique = \"flat\"\nband.1.heights = \"one\"\n")
        product(d, listOf("hw.toml", "lh.toml", "b.toml")).overrides().objects.single().let { p ->
            p.layerHeightMm shouldBe 0.16
            p.techniques!!.layerHeightMm shouldBe 0.16
            p.techniques!!.bands.single().oneHeight shouldBe true
        }
        // follow with a layer height: contradictory, refused (control: follow without one, accepted above)
        refused("band.1.technique = \"flat\"", "band.1.heights = \"follow\"", "band.1.layer.height = \"0.2 mm\"") shouldContain
            "heights = \"follow\" with plan.object.box.stl.band.1.layer.height"
        // heights on a band that is not flat (control: the curved band without it is read)
        refused("band.1.technique = \"curved\"", "band.1.heights = \"one\"") shouldContain "band.1.heights is for a flat band (this one is 'curved')"
        bandsOf("band.1.technique = \"curved\"") shouldBe listOf(false to false)
    }
    "--technique: one and follow; anything else refused; control: derive locks nothing" {
        techniqueOverride("one") shouldBe plymeld.core.decide.FlatLayers.Heights.ONE
        techniqueOverride("follow") shouldBe plymeld.core.decide.FlatLayers.Heights.FOLLOW
        techniqueOverride("derive") shouldBe null
        io.kotest.assertions.throwables.shouldThrow<com.github.ajalt.clikt.core.UsageError> { techniqueOverride("uniform") }.message!! shouldContain "must be 'derive', 'one', or 'follow'"
    }
    "the flat layer height is decided among the tool's range, locked in the plan file, and printed; a config value locks it" {
        // a 20 x 20 x 10 box on a 0.4 nozzle: thinnest 0.08, thickest 0.3; the ends, the baseline (0.2) and the midpoints
        // first, then up to four more near the best (the bundled method.search.layer.heights = 9)
        val d = dir("hw.toml" to hardware, "box.stl" to box(20.0, 20.0, 10.0))
        val pr = product(d, listOf("hw.toml"))
        val plan = pr.decide()
        val decision = plan.decisions.single { it.value is plymeld.core.decide.TechniquePlan }
        decision.facts.single { it.startsWith("flat layers' layer height, sampled") } shouldContain "in every pose 0.08, 0.3, 0.2 mm"
        val st = decision.value as plymeld.core.decide.TechniquePlan
        // the search scores the winner again each round: the alternatives list each distinct plan once, the chosen one marked once
        decision.alternatives.map { it.label + "|" + it.score }.let { it.distinct().size shouldBe it.size }
        decision.alternatives.count { it.chosen } shouldBe 1
        val h = st.layerHeightMm
        if (st.oneHeight) (h!! in 0.08..0.3) shouldBe true
        if (h != null) {
            (plymeld.model.Resolver.resolve(pr.result.setup, "plan.technique.flat.layer.height").value as Double) shouldBe h
            pr.planFile(plan) shouldContain "technique.flat.layer.height"
        }
        // control: the user's own value locks it: one candidate, that one
        Files.writeString(d.resolve("lh.toml"), "[plan.technique.flat]\nlayer.height = \"0.16 mm\"\n")
        val locked = product(d, listOf("hw.toml", "lh.toml"))
        locked.overrides().objects.single().layerHeightMm shouldBe 0.16
        val p2 = locked.decide().decisions.single { it.value is plymeld.core.decide.TechniquePlan }.value as plymeld.core.decide.TechniquePlan
        if (p2.oneHeight) p2.layerHeightMm shouldBe 0.16
    }

    "the baseline is flat layers at half the nozzle: it follows the nozzle, and a config value overrides the share" {
        fun baselineLine(hw: String, vararg more: Pair<String, String>): String {
            val d = dir("hw.toml" to hw, "box.stl" to box(20.0, 20.0, 10.0), *more)
            val pose = product(d, listOf("hw.toml") + more.map { it.first }).decide().decisions.first()
            return pose.facts.single { it.startsWith("baseline") }
        }
        val wide = hardware.replace("bore = \"0.4 mm\"", "bore = \"0.6 mm\"")
        baselineLine(hardware) shouldContain "flat layers 0.20 mm = 50 % of the 0.4 mm nozzle of tool 1"
        // control: a 0.6 mm nozzle gives 0.30 mm (the same share)
        baselineLine(wide) shouldContain "flat layers 0.30 mm = 50 % of the 0.6 mm nozzle of tool 1"
        // overridable: 40 % of 0.6 mm
        baselineLine(wide, "b.toml" to "[method.search.baseline]\nlayer.height_share = \"40%\"\n") shouldContain "flat layers 0.24 mm = 40 % of the 0.6 mm nozzle"
    }

    "plymeld config: a filament's melt rate for a machine caps it there, its own elsewhere; the start template gets it" {
        // a second machine like m, its tool like t1; pla-generic on both; a melt rate for n only
        val d = dir("hw.toml" to hardware + "\n" + """
            [hardware.machine.n]
            like = "m"
            [hardware.machine.n.loadout]
            plate = "pei"
            tool.n1.filament = "pla-generic"
            [hardware.tool.n1]
            like = "t1"
            machine = "n"
            [hardware.filament.pla-generic.machine.n]
            melt.flow.max = "= filament.temperature.print / 10 C * 1 mm3/s"
        """.trimIndent(), "box.stl" to box(20.0, 20.0, 2.0),
            "start.toml" to "[hardware.machine.m]\ngcode.start = \"M109 S{first_layer_nozzle_temperature}\\n; max flow {max_filament_flow}\"\n")
        fun shown(on: String): String = ShowConfigCommand().test(listOf("-cf", d.resolve("hw.toml").toString(), "--machine", on, "hardware.filament.pla-generic")).stdout
        // pla-generic prints at 210 C: 21 mm3/s on n, its own bundled 15 on m
        shown("n").let {
            it shouldContain "hardware.filament.pla-generic.machine.n.melt.flow.max = \"= filament.temperature.print / 10 C * 1 mm3/s\"  -> 21 mm3/s"
            it shouldContain "    used for 'pla-generic' in tool 'n1' on machine 'n': 21 mm3/s"
            it shouldContain "    not used for 'pla-generic' in tool 'n1' on machine 'n': its melt cap there is 21 mm3/s, hardware.filament.pla-generic.machine.n.melt.flow.max"
        }
        shown("m").let {
            it shouldContain "    used for 'pla-generic' in tool 't1' on machine 'm': 15 mm3/s"
            it shouldContain "    not used for 'pla-generic' in tool 't1' on machine 'm': its melt cap there is 15 mm3/s, hardware.filament.pla-generic.melt.flow.max"
        }
        // the g-code's start template: the machine's own value (n is like m, so both start templates print it)
        fun startFlow(on: String): String {
            SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("start.toml").toString(), "--machine", on,
                "-m", d.resolve("box.stl").toString(), "-o", d.resolve("$on.gcode").toString()))
            return Files.readString(d.resolve("$on.gcode")).lines().first { it.startsWith("; max flow ") }
        }
        startFlow("n") shouldBe "; max flow 21"
        startFlow("m") shouldBe "; max flow 15"
        // every move's flow, by the g-code alone (filament per mm x the 1.75 mm filament's area x speed), held to the melt
        // rate less the melt buffer: 90 % of 21 with the bundled 10 %; the control, the buffer at 0 %, reaches the 21 itself
        fun highestFlow(vararg cv: String): Double {
            val out = d.resolve("flow-${cv.size}.gcode")
            SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "--machine", "n", "-m", d.resolve("box.stl").toString(), "-o", out.toString()) + cv.flatMap { listOf("-cv", it) })
            var x = Double.NaN; var y = Double.NaN; var f = 0.0; var top = 0.0
            for (l in Files.readString(out).lines()) {
                if (!l.startsWith("G1 ")) continue
                fun num(c: Char) = Regex(" $c([-0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble()
                num('F')?.let { f = it }
                val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0
                val len = Math.hypot(nx - x, ny - y)
                if (e > 0 && len > 0.5) top = maxOf(top, e / len * Math.PI * 1.75 * 1.75 / 4 * f / 60)
                x = nx; y = ny
            }
            return top
        }
        highestFlow().let { (it <= 21 * 0.9 * 1.002) shouldBe true; (it > 21 * 0.9 * 0.98) shouldBe true }
        highestFlow("plan.mechanism.motion.melt.buffer=0%").let { (it > 21 * 0.98) shouldBe true; (it <= 21 * 1.002) shouldBe true }
    }

    "plymeld config: a machine's measured visible speed is said to replace the bundled stand-in; control: a job value of yours is not" {
        val d = dir("hw.toml" to hardware, "motion.toml" to "[hardware.machine.m]\nmotion.visible.speed = \"200 mm/s\"\n",
            "job.toml" to "[plan.mechanism.motion]\nvisible.speed = \"70 mm/s\"\n")
        fun shown(vararg cf: String): String = ShowConfigCommand().test(cf.flatMap { listOf("-cf", d.resolve(it).toString()) } + listOf("--machine", "m", "plan.mechanism.motion")).stdout
        shown("hw.toml", "motion.toml") shouldContain "    not used on machine 'm': its own (its motion plate's reading) is \"200 mm/s\", hardware.machine.m.motion.visible.speed"
        shown("hw.toml") shouldNotContain "not used on machine 'm'"                  // control: no measured value, no note
        shown("hw.toml", "motion.toml", "job.toml").let {
            it shouldContain "plan.mechanism.motion.visible.speed = \"70 mm/s\""
            it shouldNotContain "not used on machine 'm'"
        }
    }

    // ---- the first-layer window, end to end: config -> plan -> g-code, read back from the g-code

    // a window for pla-generic on pei, a 0.4 mm nozzle, at 215 C (pla-generic's first layer: 210 C + 5); within the tool's
    // 0.08-0.3 mm only 0.28 mm is good, its good tiles reach 180 mm/s and the next row is over: 0.28 mm at 140 mm/s
    val window = """
        [hardware.filament.pla-generic]
        melt.flow.max = "30 mm3/s"    # above the first layer's 0.28 mm x 0.5 mm at 140 mm/s (17 mm3/s): the melt cap does not bite
        [hardware.filament.pla-generic.first_layer.window.pei]
        from = "a test window"
        bore = "0.4 mm"
        "215 C" = '''
        speed \ height   0.20 mm   0.24 mm   0.28 mm   0.32 mm   0.36 mm
        300 mm/s         over      over      over      over      over
        260 mm/s         bad       over      over      over      over
        220 mm/s         bad       marginal  over      over      over
        180 mm/s         bad       marginal  good      over      over
        140 mm/s         bad       marginal  good      good      good
        100 mm/s         bad       marginal  good      good      good
         60 mm/s         bad       marginal  good      good      good
        '''
    """.trimIndent()
    /**
     * As the printer reads the g-code: the Z (from the moves, not the layer comments) of the extrusions of each of the first
     * two layers (layers counted by `; CHANGE_LAYER`), and the feed rates (mm/s) of the first layer's extrusions.
     */
    fun readBack(gcode: String): Triple<Set<Double>, Set<Double>, Set<Double>> {
        val z1 = HashSet<Double>(); val z2 = HashSet<Double>(); val fs = HashSet<Double>(); var f = 0.0; var z = Double.NaN; var layer = 0
        for (l in gcode.lines()) {
            if (l.startsWith("; CHANGE_LAYER")) layer++
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val w = c.split(Regex("\\s+")).drop(1).associate { it[0] to it.substring(1).toDouble() }
            w['F']?.let { f = it / 60 }; w['Z']?.let { z = it }
            if ((w['E'] ?: 0.0) <= 0 || ('X' !in w && 'Y' !in w)) continue
            val zr = Math.round(z * 1e6) / 1e6
            if (layer == 1) { z1 += zr; fs += Math.round(f * 1000) / 1000.0 } else if (layer == 2) z2 += zr
        }
        return Triple(z1, z2, fs)
    }
    fun slice(d: Path, cfs: List<String>, out: String): String {
        SliceCommand().parse(cfs.flatMap { listOf("-cf", d.resolve(it).toString()) } + listOf("-m", d.resolve("box.stl").toString(), "-o", d.resolve(out).toString()))
        return Files.readString(d.resolve(out))
    }

    // the g-code records each model it was made from : the file's SHA-256 in the source block, plymeld's geometry fingerprint in its plan part
    "the g-code records the model's SHA-256 and its geometry fingerprint; control: a changed model changes both" {
        val d = dir("hw.toml" to hardware, "box.stl" to box(20.0, 20.0, 10.0))
        fun sha(p: Path) = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString("") { "%02x".format(it) }
        fun recorded(text: String) = text.lines().filter { it.startsWith("; model = \"box.stl\"") || it.startsWith("; \"box.stl\" = ") }
        val first = slice(d, listOf("hw.toml"), "a.gcode")
        val bytes = Files.size(d.resolve("box.stl"))
        val geometry = product(d, listOf("hw.toml")).let { p -> p.planFile(p.decide()) }.lines().single { it.startsWith("\"box.stl\" = ") }
        recorded(first) shouldBe listOf("; model = \"box.stl\"  # $bytes bytes, sha256 ${sha(d.resolve("box.stl"))}", "; $geometry")
        Files.writeString(d.resolve("box.stl"), box(20.0, 20.0, 12.0))
        val second = recorded(slice(d, listOf("hw.toml"), "b.gcode"))
        second.size shouldBe 2
        (second[0] == recorded(first)[0]) shouldBe false
        (second[1] == recorded(first)[1]) shouldBe false
        second[0] shouldContain sha(d.resolve("box.stl"))
    }

    "slicing with the plan file gives the g-code of the slice that made it, its first layer included; control: without the plan's first-layer line it differs" {
        val d = dir("hw.toml" to hardware, "box.stl" to box(20.0, 20.0, 10.0))
        fun gcode(text: String) = text.substringBefore(SourceBlock.START)
        val free = gcode(slice(d, listOf("hw.toml"), "free.gcode"))
        val p = product(d, listOf("hw.toml"))
        val plan = p.planFile(p.decide())
        Files.writeString(d.resolve("plan.toml"), plan)
        gcode(slice(d, listOf("hw.toml", "plan.toml"), "locked.gcode")) shouldBe free
        val firstLayer = plan.lines().filter { it.startsWith("mechanism.adhesion.first_layer.height = ") }
        firstLayer.size shouldBe 1
        Files.writeString(d.resolve("plan.toml"), plan.lines().filterNot { it in firstLayer }.joinToString("\n"))
        (gcode(slice(d, listOf("hw.toml", "plan.toml"), "unlocked.gcode")) == free) shouldBe false
    }

    "the g-code's record lists the hardware the print uses, not the rest, nor values not used yet; the plan finds the record's config unchanged" {
        val extra = """
            [job]
            machine = "m"
            [hardware.machine.m.loadout]
            tool.t1.filament = "pla-mine"
            [hardware.filament.pla-mine]
            like = "pla-generic"
            color = "#FFFFFF"
            [hardware.machine.m2]
            model = "flashforge-c5"
            [hardware.tool.t2]
            machine = "m2"
            bore = "0.4 mm"
            [hardware.machine.m2.loadout]
            tool.t2.filament = "petg-mine"
            [hardware.filament.petg-mine]
            like = "petg-generic"
        """.trimIndent()
        val d = dir("hw.toml" to hardware, "extra.toml" to extra, "box.stl" to box(20.0, 20.0, 10.0))
        val first = slice(d, listOf("hw.toml", "extra.toml"), "a.gcode")
        val block = first.lines().dropWhile { it != SourceBlock.START }
        val config = block.dropWhile { !it.startsWith("; config:") }.drop(1).takeWhile { !it.startsWith(SourceBlock.PLAN) }
            .filter { l -> SourceBlock.Group.entries.none { l.startsWith(it.header) } }.map { it.removePrefix("; ") }
        val plan = block.dropWhile { !it.startsWith(SourceBlock.PLAN) }.drop(1).takeWhile { it != SourceBlock.END }.map { it.removePrefix(";").removePrefix(" ") }
        fun has(prefix: String) = config.any { it.startsWith(prefix) }
        // used: the machine, its tool, its model, its plate, the filament it has loaded and that filament's base
        for (p in listOf("hardware.machine.m.", "hardware.tool.t1.", "hardware.model.qidi-q2.", "hardware.plate.pei.", "hardware.filament.pla-mine.like", "hardware.filament.pla-generic."))
            withClue("kept: $p") { has(p) shouldBe true }
        // not used: the other machine, its tool, model and filaments, and the bundled filaments no one loaded
        for (p in listOf("hardware.machine.m2.", "hardware.tool.t2.", "hardware.model.flashforge-c5.", "hardware.filament.petg-mine.", "hardware.filament.petg-generic.", "hardware.filament.abs-generic."))
            withClue("left out: $p") { has(p) shouldBe false }
        // control: the config in force did hold every one of them
        val inForce = product(d, listOf("hw.toml", "extra.toml")).usedStack.effective.keys
        for (p in listOf("hardware.machine.m2.", "hardware.tool.t2.", "hardware.model.flashforge-c5.", "hardware.filament.petg-mine.", "hardware.filament.abs-generic."))
            withClue("in force: $p") { inForce.any { it.startsWith(p) } shouldBe true }
        // a value plymeld does not use yet is there (a formula may read it), with the values the print did not read
        has("hardware.model.qidi-q2.fan.") shouldBe true
        val other = block.dropWhile { !it.startsWith(SourceBlock.Group.OTHER_YOURS.header) }.takeWhile { !it.startsWith(SourceBlock.PLAN) }
        other.any { it.startsWith("; hardware.model.qidi-q2.fan.") } shouldBe true
        Files.writeString(d.resolve("record.toml"), config.joinToString("\n") + "\n")
        Files.writeString(d.resolve("plan.toml"), plan.joinToString("\n") + "\n")
        // the record stands in for the config: the plan finds its config unchanged
        val said = ArrayList<String>()
        product(d, listOf("record.toml", "plan.toml"), said = said).checkPlan()
        withClue(said.joinToString("\n")) { said.none { "changed" in it } shouldBe true }
        // control: a value the print uses, changed, is said
        Files.writeString(d.resolve("wider.toml"), "[hardware.machine.m]\nbuild.x = \"240 mm\"\n")
        val changed = ArrayList<String>()
        product(d, listOf("record.toml", "wider.toml", "plan.toml"), said = changed).checkPlan()
        changed.any { "the config changed since this plan was made (hardware)" in it } shouldBe true
    }

    "what the print used is the same when its layers come from the layer cache; control: the second slice did take layers from it" {
        val d = dir("hw.toml" to hardware, "box.stl" to box(20.0, 20.0, 10.0))
        plymeld.core.LayerCache.clear()
        val first = slice(d, listOf("hw.toml"), "one.gcode")
        val hits = plymeld.core.LayerCache.hits
        val second = slice(d, listOf("hw.toml"), "two.gcode")
        (plymeld.core.LayerCache.hits > hits) shouldBe true
        second shouldBe first   // the block's groups included
        // and what the print did not ask for stays out of "used": the box is not ironed
        val used = first.lines().dropWhile { !it.startsWith(SourceBlock.Group.USED_YOURS.header) }.takeWhile { !it.startsWith(SourceBlock.Group.OTHER_YOURS.header) }
        used.none { it.startsWith("; plan.technique.ironing.speed") } shouldBe true
        first.lines().any { it.startsWith("; plan.technique.ironing.speed") } shouldBe true
    }

    "slice --from a g-code gives that g-code again: plymeld's values stay plymeld's; control: read back as a plain config file, a bundled stand-in wins" {
        // pla-mine takes pla-generic's first-layer speed (80 mm/s) through like; plymeld's stand-in (40 mm/s) gives way to it
        val extra = "[hardware.machine.m.loadout]\ntool.t1.filament = \"pla-mine\"\n[hardware.filament.pla-mine]\nlike = \"pla-generic\"\n"
        val d = dir("hw.toml" to hardware, "extra.toml" to extra, "box.stl" to box(20.0, 20.0, 10.0))
        fun gcode(text: String) = text.substringBefore(SourceBlock.START)
        val first = slice(d, listOf("hw.toml", "extra.toml"), "a.gcode")
        // the groups: the speed that won is used, the stand-in that gave way is not; each where it came from
        fun groupOf(prefix: String) = first.lines().dropWhile { !it.startsWith(SourceBlock.CONFIG) }.takeWhile { !it.startsWith(SourceBlock.PLAN) }
            .fold(null as SourceBlock.Group? to null as SourceBlock.Group?) { (g, found), l ->
                val h = SourceBlock.Group.entries.firstOrNull { l.startsWith(it.header) }
                (h ?: g) to (found ?: g.takeIf { h == null && l.startsWith("; $prefix = ") })
            }.second
        groupOf("hardware.filament.pla-generic.motion.first_layer.speed") shouldBe SourceBlock.Group.USED_BUNDLED
        groupOf("plan.mechanism.motion.first_layer.speed") shouldBe SourceBlock.Group.OTHER_BUNDLED
        groupOf("hardware.filament.pla-mine.like") shouldBe SourceBlock.Group.USED_YOURS
        fun from(out: String, vararg more: String): String {
            SliceCommand().parse(listOf("--from", d.resolve("a.gcode").toString(), "-m", d.resolve("box.stl").toString(), "-o", d.resolve(out).toString()) + more)
            return Files.readString(d.resolve(out))
        }
        gcode(from("again.gcode")) shouldBe gcode(first)
        // control: the same lines as a plain config file (and the plan): plymeld's stand-in counts as the user's and wins
        val block = first.lines().dropWhile { it != SourceBlock.START }
        val config = block.dropWhile { !it.startsWith(SourceBlock.CONFIG) }.drop(1).takeWhile { !it.startsWith(SourceBlock.PLAN) }
            .filter { l -> SourceBlock.Group.entries.none { l.startsWith(it.header) } }.map { it.removePrefix("; ") }
        val plan = block.dropWhile { !it.startsWith(SourceBlock.PLAN) }.drop(1).takeWhile { it != SourceBlock.END }.map { it.removePrefix(";").removePrefix(" ") }
        Files.writeString(d.resolve("plain.toml"), config.joinToString("\n") + "\n")
        Files.writeString(d.resolve("plan.toml"), plan.joinToString("\n") + "\n")
        (gcode(slice(d, listOf("plain.toml", "plan.toml"), "plain.gcode")) == gcode(first)) shouldBe false
        // starting from it to change one thing: the change applies, the rest as it was
        val slower = gcode(from("slower.gcode", "-cv", "hardware.filament.pla-mine.motion.first_layer.speed=60mm/s"))
        (slower == gcode(first)) shouldBe false
        slower shouldContain "F3600"
    }

    // the start-template variables renamed by the naming rules; an old name is not refused by name
    "an old start-template variable is an unknown name, with the ordinary error; control: the new name renders" {
        val d = dir("hw.toml" to hardware, "box.stl" to box(20.0, 20.0, 10.0),
            "old.toml" to "[hardware.machine.m]\ngcode.start = \"M109 S{nozzle_temp_first_layer}\"\n")
        io.kotest.assertions.throwables.shouldThrow<Exception> { slice(d, listOf("hw.toml", "old.toml"), "old.gcode") }.message!! shouldContain
            "template refers to unknown name 'nozzle_temp_first_layer'"
        Regex("""^M109 S\d+$""", RegexOption.MULTILINE).containsMatchIn(slice(d, listOf("hw.toml"), "new.gcode")) shouldBe true
    }

    "a first-layer window sets the printed first layer's height and speed; control: without it, today's; a plan lock wins over it" {
        // 40 x 40 mm, so the first layer takes longer than the minimum layer time (no cooling slow-down)
        val d = dir("hw.toml" to hardware, "window.toml" to window, "box.stl" to box(40.0, 40.0, 3.0),
            "lock.toml" to "[plan.mechanism.adhesion.first_layer]\nheight = \"0.24 mm\"\n[plan.mechanism.motion]\nfirst_layer.speed = \"60 mm/s\"\n",
            "flow.toml" to "[hardware.filament.pla-generic]\nmelt.flow.max = \"30 mm3/s\"\n",
            // flat layers of one height only, so the second layer's Z is the first plus one decided height (a box may otherwise take
            // adaptive layers: its zero stair-step and unsupported area share quality's weight)
            "one_height.toml" to "[plan.technique.flat]\nheights = \"one\"\n")
        // each config's own decided layer height: the first layer changes the layers' tops, so the choice may differ
        fun decidedHeight(cfs: List<String>) = (product(d, cfs).let { p -> p.decide().decisions.single { it.value is plymeld.core.decide.TechniquePlan }.value as plymeld.core.decide.TechniquePlan }).layerHeightMm!!
        val h = decidedHeight(listOf("hw.toml", "window.toml", "one_height.toml"))
        val hPlain = decidedHeight(listOf("hw.toml", "flow.toml", "one_height.toml"))
        // the window: Z 0.28, the next layer one layer height above it, every first-layer extrusion at 140 mm/s
        fun r(v: Double) = Math.round(v * 1e6) / 1e6
        val (zw1, zw2, fw) = readBack(slice(d, listOf("hw.toml", "window.toml", "one_height.toml"), "window.gcode"))
        zw1 shouldBe setOf(0.28); zw2 shouldBe setOf(r(0.28 + h)); fw shouldBe setOf(140.0)
        // control: no window (the same melt limit): plymeld's first layer, 0.2 mm (the bundled stand-in), at pla-generic's 80 mm/s
        val (zn1, zn2, fn) = readBack(slice(d, listOf("hw.toml", "flow.toml", "one_height.toml"), "plain.gcode"))
        zn1 shouldBe setOf(0.2); zn2 shouldBe setOf(r(0.2 + hPlain)); fn shouldBe setOf(80.0)
        // a plan lock in a config wins over the window, for each
        val (zp1, _, fp) = readBack(slice(d, listOf("hw.toml", "window.toml", "lock.toml"), "locked.gcode"))
        zp1 shouldBe setOf(0.24); fp shouldBe setOf(60.0)
        // the plan says so, and the plan file locks it
        val pr = product(d, listOf("hw.toml", "window.toml"))
        pr.firstLayerLines().single().second shouldContain "0.28 mm at 140 mm/s, the first-layer window hardware.filament.pla-generic.first_layer.window.pei"
        pr.planFile(pr.decide()) shouldContain "mechanism.adhesion.first_layer.height = \"0.28 mm\""
    }

    "the pre-print check reads a window's first layer as printed; control: a first-layer line off its layer's height is caught" {
        val d = dir("hw.toml" to hardware, "window.toml" to window, "box.stl" to box(40.0, 40.0, 3.0))
        val text = slice(d, listOf("hw.toml", "window.toml"), "window.gcode")   // the slice itself refuses a g-code its checks fault
        val setup = product(d, listOf("hw.toml", "window.toml")).result.setup
        plymeld.core.check.GcodeCheck.check(text, plymeld.core.check.Expectations.forSetup(setup)) shouldBe emptyList()
        // control: one first-layer extrusion moved down to 0.2 mm (the height a first layer would have without the window)
        val lines = text.lines().toMutableList()
        val firstLayer = lines.indexOfFirst { it.startsWith("; Z_HEIGHT:") }
        val at = (firstLayer until lines.size).first { lines[it].startsWith("G1 X") && " E" in lines[it] }
        lines.add(at, "G1 Z0.2")
        plymeld.core.check.GcodeCheck.check(lines.joinToString("\n"), plymeld.core.check.Expectations.forSetup(setup)).any { "Z0.2" in it } shouldBe true
    }

    "calibrate first-layer window: the blank grid's cells are the plate's tiles; control: a conditions block listing a tile not in the grid is refused" {
        // a first-layer plate's g-code as plymeld writes it (its conditions block and its cells), 3 speeds x 2 heights, two blocks
        fun plate(leftOut1: String, extraCell: String = "") = """
            ; HEADER_BLOCK_START
            ; generated by plymeld 0.0.1-test
            ; total layer number: 1
            ; CALIBRATION_PLATE_START
            ; plate = first-layer (one layer: first-layer tiles, a block per nozzle temperature)
            ; filament = pla-generic, tool 1, build plate pei
            ; first_layer_tiles = speeds 60, 100, 140 mm/s (a group of rows each, front to back), heights 0.2, 0.24 mm (a row each in every group, front to back), 0.5 mm lines
            ; block 1 = 210 C, 1 marker dot, row 1 from the front, 1 from the left$leftOut1
            ; block 2 = 215 C, 2 marker dots, row 1 from the front, 2 from the left
            ; CALIBRATION_PLATE_END
        """.trimIndent() + "\n" + (1..2).flatMap { k -> (1..3).flatMap { r -> (1..2).mapNotNull { c ->
            if (k == 1 && r == 3 && c == 2) null else "; CELL: block $k tile $r.$c" } } }.joinToString("\n") + extraCell + "\n"
        val ok = plate(", melt limit 15 mm3/s, left out: tile 3.2 (140 mm/s, 0.24 mm: 16.0 mm3/s)")
        val text = FirstLayerRecord.blank(ok, "p.gcode", "my-pei", 0.4, java.time.LocalDate.of(2026, 10, 6))
        text shouldContain "[hardware.filament.pla-generic.first_layer.window.my-pei]"
        // the version from the header line, the date from the file (the header carries none)
        text shouldContain "from = \"p.gcode, made by plymeld 0.0.1-test on 2026-10-06: tool 1, build plate pei\""
        text shouldContain "bore = \"0.4 mm\""
        // block 1 (210 C): the 140 mm/s row is at the top, its 0.24 mm tile over; every other tile '?'
        text shouldContain "\"210 C\" = '''\nspeed \\ height   0.20 mm   0.24 mm\n140 mm/s         ?         over\n100 mm/s         ?         ?\n 60 mm/s         ?         ?\n'''"
        text shouldContain "\"215 C\" = '''\nspeed \\ height   0.20 mm   0.24 mm\n140 mm/s         ?         ?\n"
        // the window it writes reads as a config once graded, and is refused while a '?' is left
        val graded = text.replace("?", "good")
        val d = dir("hw.toml" to hardware.replace("plate = \"pei\"", "plate = \"my-pei\"") + "\n[hardware.plate.my-pei]\nlike = \"pei\"\n", "w.toml" to graded, "box.stl" to box(40.0, 40.0, 3.0))
        product(d, listOf("hw.toml", "w.toml")).result.firstLayer.size shouldBe 1
        Files.writeString(d.resolve("w.toml"), text)
        io.kotest.assertions.throwables.shouldThrow<com.github.ajalt.clikt.core.UsageError> { product(d, listOf("hw.toml", "w.toml")) }.message!! shouldContain "not graded yet"
        // controls: a left-out tile outside the grid; a left-out tile that was printed; a tile neither printed nor left out
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { FirstLayerRecord.blank(plate(", left out: tile 3.2 (x); tile 8.1 (y)"), "p.gcode", null, 0.4) }
            .message!! shouldContain "tile 8.1 as left out; its grid is 3 rows x 2 columns"
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { FirstLayerRecord.blank(plate(", left out: tile 3.2 (x)", "\n; CELL: block 1 tile 3.2"), "p.gcode", null, 0.4) }
            .message!! shouldContain "tile 3.2 of block 1 is printed, but its conditions list it as left out"
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { FirstLayerRecord.blank(plate(""), "p.gcode", null, 0.4) }
            .message!! shouldContain "tile 3.2 of block 1 is neither printed nor listed as left out"
    }
})
