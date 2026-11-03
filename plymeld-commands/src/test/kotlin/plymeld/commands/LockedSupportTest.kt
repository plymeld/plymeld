package plymeld.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Support as a technique on the product path: the switch unset, the search decides per
 * overhang and the slice prints what it chose; a locked spot (`plan.support.locked.<key>`, here scoped to the object) is
 * supported whatever the weights, and refused where support cannot be generated; `plan -o` writes the chosen spots as
 * locks and the slice reads them back; `--explain` shows support's proposals, the locked spots and the columns' order.
 * The model is a T (a post with a bar across its top: two flat undersides over air).
 */
class LockedSupportTest : StringSpec({
    /** A Klipper machine whose g-code carries no time of day; the tool's nozzle protrudes 20 mm (so a column may be finished first). */
    val q2 = """
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
        head.nozzle.height = "20 mm"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [method.search]
        log.enabled = "false"
        layer.heights = "3"
    """.trimIndent()
    /** The T of SupportTechniqueTest as ASCII STL: a 10 x 10 x 10 post under a 30 x 10 x 4 bar; undersides at z 10 over x -15..-5 and 5..15. */
    fun tStl(): String {
        val faces = ArrayList<List<DoubleArray>>()
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
        fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray) { faces += listOf(a, b, c); faces += listOf(a, c, d) }
        quad(p(-5.0, -5.0, 0.0), p(-5.0, 5.0, 0.0), p(5.0, 5.0, 0.0), p(5.0, -5.0, 0.0))
        quad(p(-5.0, -5.0, 0.0), p(5.0, -5.0, 0.0), p(5.0, -5.0, 10.0), p(-5.0, -5.0, 10.0))
        quad(p(5.0, 5.0, 0.0), p(-5.0, 5.0, 0.0), p(-5.0, 5.0, 10.0), p(5.0, 5.0, 10.0))
        quad(p(-5.0, 5.0, 0.0), p(-5.0, -5.0, 0.0), p(-5.0, -5.0, 10.0), p(-5.0, 5.0, 10.0))
        quad(p(5.0, -5.0, 0.0), p(5.0, 5.0, 0.0), p(5.0, 5.0, 10.0), p(5.0, -5.0, 10.0))
        quad(p(-15.0, -5.0, 10.0), p(-15.0, 5.0, 10.0), p(-5.0, 5.0, 10.0), p(-5.0, -5.0, 10.0))
        quad(p(5.0, -5.0, 10.0), p(5.0, 5.0, 10.0), p(15.0, 5.0, 10.0), p(15.0, -5.0, 10.0))
        quad(p(-15.0, -5.0, 10.0), p(15.0, -5.0, 10.0), p(15.0, -5.0, 14.0), p(-15.0, -5.0, 14.0))
        quad(p(15.0, 5.0, 10.0), p(-15.0, 5.0, 10.0), p(-15.0, 5.0, 14.0), p(15.0, 5.0, 14.0))
        quad(p(-15.0, 5.0, 10.0), p(-15.0, -5.0, 10.0), p(-15.0, -5.0, 14.0), p(-15.0, 5.0, 14.0))
        quad(p(15.0, -5.0, 10.0), p(15.0, 5.0, 10.0), p(15.0, 5.0, 14.0), p(15.0, -5.0, 14.0))
        quad(p(-15.0, -5.0, 14.0), p(15.0, -5.0, 14.0), p(15.0, 5.0, 14.0), p(-15.0, 5.0, 14.0))
        return buildString {
            append("solid t\n")
            for (f in faces) { append("facet normal 0 0 0\nouter loop\n"); for (v in f) append("vertex ${v[0]} ${v[1]} ${v[2]}\n"); append("endloop\nendfacet\n") }
            append("endsolid t\n")
        }
    }
    val d: Path = Files.createTempDirectory("plymeld-locked-support")
    Files.writeString(d.resolve("q2.toml"), q2)
    Files.writeString(d.resolve("t.stl"), tStl())
    // the left overhang's underside (x -15..-5, y -5..5 at z 10 in the model's own coordinates), scoped to the object
    Files.writeString(d.resolve("lock.toml"), "[plan.object.\"t.stl\".support.locked.left]\nmin = \"-15 -5 9 mm\"\nmax = \"-5 5 11 mm\"\n")
    Files.writeString(d.resolve("air.toml"), "[plan.support.locked.air]\nmin = \"40 -5 9 mm\"\nmax = \"50 5 11 mm\"\n")
    Files.writeString(d.resolve("thrifty.toml"), "[job.priorities]\nquality = \"1\"\nmaterial = \"50\"\n")
    // the pose as given (the T upright), so the per-overhang decision is what is tested: left to the search, the weights lay
    // the T on its side, where it has no overhang to support at all
    Files.writeString(d.resolve("droop.toml"), "[plan]\npose = \"as-given\"\nplacement = \"center\"\n[job.priorities]\nquality = \"1\"\nmeasure.unsupported_area = \"50\"\nspeed = \"1\"\n")
    fun p(n: String) = d.resolve(n).toString()
    fun slice(out: String, vararg cfs: String, more: List<String> = emptyList()): String =
        SliceCommand().test(cfs.flatMap { listOf("-cf", p(it)) } + listOf("-m", p("t.stl"), "-o", p(out)) + more).let { it.stderr + it.stdout }
    fun gcode(n: String) = Files.readString(d.resolve(n))
    fun supportLines(g: String) = g.lines().count { it == "; FEATURE: Support" || it == "; FEATURE: Support interface" }

    "a locked spot is supported whatever the weights, and the slice prints it; control: without the spot, material-heavy weights print no support" {
        slice("locked.gcode", "q2.toml", "thrifty.toml", "lock.toml")
        (supportLines(gcode("locked.gcode")) > 0) shouldBe true
        slice("none.gcode", "q2.toml", "thrifty.toml")
        supportLines(gcode("none.gcode")) shouldBe 0
        // the switch locked off with a locked spot: refused, naming the spot and the way out (a decision's refusal is an
        // IllegalStateException, which Main reports as "plymeld: ..." and exits 1)
        val refused = io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
            slice("off.gcode", "q2.toml", "thrifty.toml", "lock.toml", more = listOf("-cv", "plan.support.enabled=false")) }.message!!
        refused shouldContain "forbids support, but support is locked on spot 'left'"
        Files.exists(d.resolve("off.gcode")) shouldBe false
    }

    "a locked spot with nothing to support in it, in any pose, is refused with the reason" {
        val refused = io.kotest.assertions.throwables.shouldThrow<IllegalStateException> { slice("air.gcode", "q2.toml", "air.toml") }.message!!
        refused shouldContain "locked support spot 'air'"
        refused shouldContain "holds no downward face"
        refused shouldContain "move or remove the locked spot"
        Files.exists(d.resolve("air.gcode")) shouldBe false
    }

    "decided per overhang: weights naming the droop support both overhangs, finished first; plan -o locks them as spots and the slice reads them back; --explain shows it" {
        val out = PlanCommand().test(listOf("-cf", p("q2.toml"), "-cf", p("droop.toml"), "-m", p("t.stl"), "-o", p("droop_plan.toml"), "--explain", "--explain-json", p("droop.json"))).let { it.stderr + it.stdout }
        out shouldContain "support: decided per overhang"
        out shouldContain "side-by-side order (a support column beside the model's layers): overhang #1"
        val plan = Files.readString(d.resolve("droop_plan.toml"))
        // the spots named by what they hold up: a one-part model's by the box's rounded center, in the posed frame
        val names = Regex("support\\.spot\\.([A-Za-z0-9_-]+)\\.min =").findAll(plan).map { it.groupValues[1] }.toList()
        names.size shouldBe 2
        names.all { it.startsWith("at_x") && "_y" in it && it.endsWith("_z10") } shouldBe true
        plan shouldContain "support.spot.${names[1]}.order = \"first\""
        plan.contains("support.enabled = \"false\"") shouldBe false   // the switch is not locked off: the spots lock the support
        val e = Json.parseToJsonElement(Files.readString(d.resolve("droop.json"))).jsonObject.getValue("objects").jsonArray.single().jsonObject
        val supports = e.getValue("proposals").jsonArray.map { it.jsonObject }.filter { it.getValue("technique").jsonPrimitive.content == "support" }
        supports.count { it.getValue("fate").jsonPrimitive.content == "chosen" } shouldBe 2
        // each column's order is the finalists' (their sliced g-code predicted in full): here one column finished first saves its
        // travel, the other costs 2 s more finished first than interleaved, so the quick estimate's "first" is overturned for it
        supports.filter { it.getValue("fate").jsonPrimitive.content == "chosen" }.any { it.getValue("order").jsonPrimitive.content == "first" } shouldBe true
        e.getValue("portions").jsonArray.map { it.jsonObject }.count { it.getValue("technique").jsonPrimitive.content == "support" } shouldBe 2
        e.getValue("decision").jsonObject.getValue("support").jsonObject.getValue("orders").jsonArray.size shouldBe 2
        // the slice with the plan: complete, the support printed as locked, nothing decided again
        val again = slice("droop.gcode", "q2.toml", "droop.toml", "droop_plan.toml")
        again shouldContain "complete in the config"
        (supportLines(gcode("droop.gcode")) > 0) shouldBe true
        // a kept spot is recognized by its box, not its number: mark spot 2's lines, re-plan, and the written plan carries
        // that box marked wherever it lands; the slice from the written plan (its locked spots now the plan's own) is not refused
        val n2 = Regex.escape(names[1])
        val spot2 = Regex("support\\.spot\\.$n2\\.(min|max|order) = \"").replace(plan) { m -> "support.spot.${names[1]}.${m.groupValues[1]} = \"!" }
        Files.writeString(d.resolve("droop_kept.toml"), spot2)
        PlanCommand().parse(listOf("-cf", p("q2.toml"), "-cf", p("droop.toml"), "-cf", p("droop_kept.toml"), "-m", p("t.stl"), "-o", p("droop_replan.toml")))
        val replan = Files.readString(d.resolve("droop_replan.toml"))
        val keptMinX = Regex("support\\.spot\\.$n2\\.min = \"!([^\"]*)\"").find(spot2)!!.groupValues[1]
        Regex("support\\.spot\\.[A-Za-z0-9_-]+\\.min = \"!" + Regex.escape(keptMinX) + "\"").containsMatchIn(replan) shouldBe true
        replan.lines().count { it.contains("= \"!") } shouldBe 3                       // the one spot, all three lines (min, max, order)
        slice("droop_replan.gcode", "q2.toml", "droop.toml", "droop_replan.toml") shouldContain "complete in the config"
        // material-heavy weights would rather print no support, but the bar's undersides are cantilevers, required whatever
        // the weights: left to choose its pose, the search turns the T upside down (the bar on the bed, nothing
        // over air) rather than pay for the two columns; either way nothing prints in the air
        PlanCommand().parse(listOf("-cf", p("q2.toml"), "-cf", p("thrifty.toml"), "-m", p("t.stl"), "-o", p("thrifty_plan.toml")))
        val thrifty = Files.readString(d.resolve("thrifty_plan.toml"))
        (Regex("support\\.spot\\.[A-Za-z0-9_-]+\\.min =").findAll(thrifty).count() >= 2 || thrifty.contains("0.0 mm² unsupported")) shouldBe true
        thrifty shouldContain "upside down"
        // the plan plymeld writes beside a locked spot, with weights that choose no other support, is one plymeld accepts: the locked
        // spot is written as the plan's own, never the switch off beside it (found in review: that file was refused by plymeld's own rule)
        PlanCommand().parse(listOf("-cf", p("q2.toml"), "-cf", p("thrifty.toml"), "-cf", p("lock.toml"), "-m", p("t.stl"), "-o", p("thrifty_lock_plan.toml")))
        val thriftyLock = Files.readString(d.resolve("thrifty_lock_plan.toml"))
        thriftyLock shouldNotContain "support.enabled = \"false\""
        Regex("support\\.spot\\.[A-Za-z0-9_-]+\\.min =").containsMatchIn(thriftyLock) shouldBe true
        slice("thrifty_lock.gcode", "q2.toml", "thrifty.toml", "lock.toml", "thrifty_lock_plan.toml") shouldContain "complete in the config"
    }
})
