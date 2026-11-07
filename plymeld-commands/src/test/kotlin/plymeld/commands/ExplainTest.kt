package plymeld.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `--explain` / `--explain-json`: how each object's techniques were chosen, in
 * the design's order, as text and as JSON. It changes nothing decided or printed: the g-code is byte-identical with it and
 * without. The JSON is asserted on a locked curved band (over a flat band) and on a locked lifted bridge, each against a
 * control without the lock; and on the surface techniques and seams with ironing, Z anti-aliasing (both, and each against
 * a control) and fuzzy skin, the flat tops locked where a control needs ironing locked off (unlocked, the search weighs
 * it: SearchLogTest).
 */
class ExplainTest : StringSpec({
    /** A Klipper machine whose g-code carries no time of day (so two slices compare byte for byte). */
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
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
    """.trimIndent()
    /** The same with the head described (curved layers need the nozzle's cone, a lifted bridge the head's reach); the values are the test's own. */
    val headed = q2.replace("layer.height.max = \"= tool.bore * 0.75\"", "layer.height.max = \"= tool.bore * 0.75\"\nhead.nozzle.angle = \"45 deg\"\nhead.nozzle.height = \"4 mm\"\n" +
        "head.nozzle.x = \"70 mm\"\nhead.nozzle.y = \"40 mm\"\nhead.size.x = \"90 mm\"\nhead.size.y = \"75 mm\"") + "\n[plan.technique.flat]\nlayer.height = \"0.2 mm\"\n"

    /** Triangles (9 floats each) of a closed box, outward. */
    fun boxTris(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): List<DoubleArray> {
        val v = arrayOf(doubleArrayOf(x0, y0, z0), doubleArrayOf(x1, y0, z0), doubleArrayOf(x1, y1, z0), doubleArrayOf(x0, y1, z0),
            doubleArrayOf(x0, y0, z1), doubleArrayOf(x1, y0, z1), doubleArrayOf(x1, y1, z1), doubleArrayOf(x0, y1, z1))
        val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        return (0 until 12).map { t -> DoubleArray(9) { v[f[3 * t + it / 3]][it % 3] } }
    }
    fun stl(tris: List<DoubleArray>): String = buildString {
        append("solid s\n")
        for (t in tris) { append("facet normal 0 0 0\nouter loop\n"); for (c in 0..2) append("vertex ${t[3 * c]} ${t[3 * c + 1]} ${t[3 * c + 2]}\n"); append("endloop\nendfacet\n") }
        append("endsolid s\n")
    }
    /** A strip along X whose underside passes through [pts] (x, z), 1.2 mm thick, 5 mm wide about y = 0. */
    fun strip(pts: List<Pair<Double, Double>>, t: Double = 1.2, w: Double = 5.0): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        fun v(i: Int, top: Boolean, s: Int) = doubleArrayOf(pts[i].first, s * w / 2, pts[i].second + if (top) t else 0.0)
        fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray) { out += a + b + c }
        for (i in 0 until pts.size - 1) {
            tri(v(i, false, -1), v(i, false, 1), v(i + 1, false, 1)); tri(v(i, false, -1), v(i + 1, false, 1), v(i + 1, false, -1))
            tri(v(i, true, -1), v(i + 1, true, -1), v(i + 1, true, 1)); tri(v(i, true, -1), v(i + 1, true, 1), v(i, true, 1))
            tri(v(i, false, -1), v(i + 1, false, -1), v(i + 1, true, -1)); tri(v(i, false, -1), v(i + 1, true, -1), v(i, true, -1))
            tri(v(i, false, 1), v(i + 1, true, 1), v(i + 1, false, 1)); tri(v(i, false, 1), v(i, true, 1), v(i + 1, true, 1))
        }
        val n = pts.size - 1
        tri(v(0, false, -1), v(0, true, -1), v(0, true, 1)); tri(v(0, false, -1), v(0, true, 1), v(0, false, 1))
        tri(v(n, false, -1), v(n, true, 1), v(n, true, -1)); tri(v(n, false, -1), v(n, false, 1), v(n, true, 1))
        return out
    }
    /** A 3MF of one object [name] made of the named parts. */
    fun threeMf(path: Path, name: String, parts: List<Pair<String, List<DoubleArray>>>) {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources>")
        for ((k, p) in parts.withIndex()) {
            sb.append("<object id=\"${k + 1}\" type=\"model\" name=\"${name}_${p.first}\"><mesh><vertices>")
            for (t in p.second) for (c in 0..2) sb.append("<vertex x=\"${t[3 * c]}\" y=\"${t[3 * c + 1]}\" z=\"${t[3 * c + 2]}\"/>")
            sb.append("</vertices><triangles>")
            for (i in p.second.indices) sb.append("<triangle v1=\"${3 * i}\" v2=\"${3 * i + 1}\" v3=\"${3 * i + 2}\"/>")
            sb.append("</triangles></mesh></object>")
        }
        val g = parts.size + 1
        sb.append("<object id=\"$g\" type=\"model\" name=\"$name\"><components>")
        for (k in parts.indices) sb.append("<component objectid=\"${k + 1}\"/>")
        sb.append("</components></object></resources><build><item objectid=\"$g\"/></build></model>")
        ZipOutputStream(Files.newOutputStream(path)).use { z -> z.putNextEntry(ZipEntry("3D/3dmodel.model")); z.write(sb.toString().toByteArray()); z.closeEntry() }
    }

    val d: Path = Files.createTempDirectory("plymeld-explain")
    // the seam locked as the bundled defaults locked it earlier (scarf seams on the smooth loops): these cases read the
    // seam styles as locks; unset, the seam is decided after the plan and explain does not yet carry the decision
    Files.writeString(d.resolve("q2.toml"), q2 + "\n[plan]\nseam = \"corners\"\ntechnique.scarf_seam.enabled = \"true\"\n")
    Files.writeString(d.resolve("headed.toml"), headed)
    Files.writeString(d.resolve("box.stl"), stl(boxTris(0.0, 0.0, 0.0, 20.0, 20.0, 10.0)))
    val ramp = d.resolve("ramp.3mf")
    threeMf(ramp, "ramp", listOf(
        "supports" to boxTris(-28.0, -5.0, 0.0, -20.0, 5.0, 6.0) + boxTris(20.0, -5.0, 0.0, 28.0, 5.0, 12.0),
        "strip" to strip(listOf(-28.0 to 6.0, -20.0 to 6.0, 20.0 to 12.0, 28.0 to 12.0))))
    fun p(n: String) = d.resolve(n).toString()

    /** `plymeld plan` with [cfs] on [model], `--explain-json`; the JSON's one object. */
    fun explained(cfs: List<String>, model: String, json: String): JsonObject {
        PlanCommand().parse(cfs.flatMap { listOf("-cf", p(it)) } + listOf("-m", model, "--explain-json", p(json)))
        val all = Json.parseToJsonElement(Files.readString(d.resolve(json))).jsonObject
        return all.getValue("objects").jsonArray.single().jsonObject
    }
    fun JsonElement.str(k: String) = jsonObject.getValue(k).let { if (it is JsonNull) null else it.jsonPrimitive.content }
    fun JsonObject.list(k: String): JsonArray = getValue(k).jsonArray

    "explain changes nothing printed: the g-code is byte-identical with --explain and --explain-json and without; control: another layer height changes it" {
        val plain = d.resolve("plain.gcode"); val with = d.resolve("explained.gcode"); val other = d.resolve("other.gcode")
        SliceCommand().parse(listOf("-cf", p("q2.toml"), "-m", p("box.stl"), "-o", plain.toString()))
        SliceCommand().parse(listOf("-cf", p("q2.toml"), "-m", p("box.stl"), "--explain", "--explain-json", p("slice_explain.json"), "-o", with.toString()))
        Files.readAllBytes(with).contentEquals(Files.readAllBytes(plain)) shouldBe true
        // the explain was really written
        Json.parseToJsonElement(Files.readString(d.resolve("slice_explain.json"))).jsonObject.list("objects").size shouldBe 1
        // control: the comparison can tell two slices apart
        SliceCommand().parse(listOf("-cf", p("q2.toml"), "-cv", "plan.technique.flat.layer.height=0.3 mm", "-m", p("box.stl"), "-o", other.toString()))
        Files.readAllBytes(other).contentEquals(Files.readAllBytes(plain)) shouldBe false
    }

    "the text follows §7's order: proposals, candidates, handoffs, scoring, decision; control: without --explain, none of it" {
        val out = PlanCommand().test(listOf("-cf", p("q2.toml"), "-m", p("box.stl"), "--explain")).stdout
        val at = listOf("1. proposals", "2. candidates", "3. handoffs", "4. scoring", "5. decision").map { out.indexOf(it) }
        at.all { it >= 0 } shouldBe true
        at shouldBe at.sorted()
        out shouldContain "explain 'box.stl'"
        PlanCommand().test(listOf("-cf", p("q2.toml"), "-m", p("box.stl"))).stdout.contains("1. proposals") shouldBe false
    }

    "a locked curved band over a flat band: both locked proposals chosen, curved cuts only at its lower edge, flat -> curved a compatible handoff; control: unlocked, no curved proposal" {
        Files.writeString(d.resolve("curved.toml"), """
            [plan.object."box.stl"]
            band.1.technique = "flat"
            band.1.heights = "one"
            band.1.layer.height = "0.2 mm"
            band.1.to = "6 mm"
            band.2.technique = "curved"
            band.2.from = "6 mm"
        """.trimIndent())
        val e = explained(listOf("headed.toml", "curved.toml"), p("box.stl"), "curved.json")
        val proposals = e.list("proposals").map { it.jsonObject }
        val curved = proposals.single { it.str("technique") == "curved" }
        curved.getValue("locked").jsonPrimitive.boolean shouldBe true
        curved.str("cuts") shouldBe "lower_edge"
        curved.str("fate") shouldBe "chosen"
        curved.getValue("volume").jsonObject.str("from_mm") shouldBe "6.0"
        proposals.single { it.str("technique") == "flat" && it.getValue("locked").jsonPrimitive.boolean }.str("fate") shouldBe "chosen"
        // the whole-object flat proposals give way to the requirements
        proposals.filter { !it.getValue("locked").jsonPrimitive.boolean }.all { it.str("fate") == "rejected" } shouldBe true
        e.list("portions").map { it.str("technique") } shouldBe listOf("flat", "curved")
        e.list("fill").size shouldBe 0
        val h = e.list("handoffs").map { it.jsonObject }
        h.map { it.str("status") } shouldBe listOf("compatible", "compatible")
        h[1].str("exit") shouldBe "flat_top"
        h[1].list("entry").map { it.jsonPrimitive.content } shouldContain "flat_top"
        // the poses were measured with the locked plan, and the decision names the lock
        e.getValue("candidates").jsonObject.list("measured").size shouldNotBe 0
        e.getValue("decision").jsonObject.list("locks").any { "the plan's bands locked" in it.jsonPrimitive.content } shouldBe true
        // control: the same box without the locks proposes no curved band (curved layers are locked only)
        val free = explained(listOf("headed.toml"), p("box.stl"), "free.json")
        free.list("proposals").none { it.str("technique") == "curved" } shouldBe true
        free.getValue("candidates").jsonObject.list("screen").map { it.jsonObject }.single { "curved layers" in it.str("name")!! }.str("why")!! shouldContain "locked only"
    }

    // surface techniques and seam styles: locked by the job's locks
    fun surfaceTechnique(e: JsonObject, t: String) = e.list("surface_techniques").map { it.jsonObject }.single { it.str("technique") == t }
    fun region(e: JsonObject, faces: String, side: String? = null) = e.list("surfaces").map { it.jsonObject }
        .single { it.str("faces") == faces && it.getValue("region").jsonObject.str("side") == side }
    fun seamCurve(e: JsonObject, loops: String) = e.list("seams").map { it.jsonObject }.single { it.getValue("curve").jsonObject.str("loops") == loops }
    fun owners(s: JsonObject) = s.list("owners").map { it.jsonObject.str("technique") }
    "an ironing case: the surface techniques listed with their kind and locked fate, ironing the surface technique of the flat tops; control: the flat tops locked as laid, ironing locked off" {
        // control first: the flat tops locked as laid, ironing locked off (no lock at all would have the search weigh it: SearchLogTest)
        Files.writeString(d.resolve("laid.toml"), "[plan.faces]\nflat_top.technique = \"as_laid\"\n")
        val e = explained(listOf("q2.toml", "laid.toml"), p("box.stl"), "iron.json")
        surfaceTechnique(e, "ironing").let { s -> s.str("kind") shouldBe "surface"; s.str("lock") shouldBe "off"; s.str("fate") shouldBe "rejected"; s.str("lock_source")!! shouldContain "locked off" }
        region(e, "flat_top").str("surface_technique") shouldBe "none"
        Files.writeString(d.resolve("iron.toml"), "[plan.faces]\nflat_top.technique = \"ironing\"\n")
        val iron = explained(listOf("q2.toml", "iron.toml"), p("box.stl"), "iron_on.json")
        val s = surfaceTechnique(iron, "ironing")
        s.str("kind") shouldBe "surface"
        s.str("lock") shouldBe "on"
        s.str("fate") shouldBe "chosen"
        s.str("lock_source")!! shouldContain "plan.faces.flat_top.technique = \"ironing\""
        s.list("serves").map { it.jsonPrimitive.content } shouldBe listOf("flat_top")
        // every surface technique is listed; what is not switched on is rejected (locked off)
        iron.list("surface_techniques").map { it.str("technique") } shouldBe listOf("ironing", "z_antialiasing", "fuzzy_skin", "wave_overhang")
        surfaceTechnique(iron, "z_antialiasing").str("fate") shouldBe "rejected"
        surfaceTechnique(iron, "fuzzy_skin").str("fate") shouldBe "rejected"
        region(iron, "flat_top").str("surface_technique") shouldBe "ironing"
        region(iron, "flat_top").list("can_serve").map { it.str("technique") } shouldBe listOf("ironing")
        // the volume proposals carry their kind too, and ironing's contract with the volume under it is met
        iron.list("proposals").all { it.str("kind") == "volume" } shouldBe true
        iron.list("finish_handoffs").map { it.jsonObject }.single { it.str("technique") == "ironing" }.str("met") shouldBe "all of it"
    }

    "ironing with Z anti-aliasing: each surface region with what it faces, the surface techniques that can serve it and the one chosen; ironing's tread passes said; control: Z anti-aliasing alone, ironing locked off and nothing laid beyond" {
        Files.writeString(d.resolve("iron.toml"), "[plan.faces]\nflat_top.technique = \"ironing\"\n")
        Files.writeString(d.resolve("zaa.toml"), "[plan.faces]\nsloped_top.technique = \"z_antialiasing\"\n")
        val both = explained(listOf("q2.toml", "iron.toml", "zaa.toml"), p("box.stl"), "iron_zaa.json")
        surfaceTechnique(both, "ironing").str("fate") shouldBe "chosen"
        surfaceTechnique(both, "z_antialiasing").str("fate") shouldBe "chosen"
        both.list("surfaces").map { it.str("faces") } shouldBe listOf("flat_top", "sloped_top", "wall", "wall", "underside")
        region(both, "flat_top").let { r -> r.str("surface_technique") shouldBe "ironing"; r.list("can_serve").map { it.str("technique") } shouldBe listOf("ironing") }
        val sloped = region(both, "sloped_top")
        sloped.str("surface_technique") shouldBe "z_antialiasing"
        sloped.list("can_serve").map { it.str("technique") } shouldBe listOf("z_antialiasing")
        sloped.str("also_laid")!! shouldContain "ironing's lines on the treads"
        sloped.str("also_laid")!! shouldContain "lowered onto the slope by Z anti-aliasing"
        region(both, "wall", "outside").let { r -> r.str("surface_technique") shouldBe "none"; r.list("can_serve").map { it.str("technique") } shouldBe listOf("fuzzy_skin") }
        // wave overhangs serve the undersides, by default (plan.faces.under.technique = "wave_overhang")
        region(both, "underside").let { r -> r.str("surface_technique") shouldBe "wave_overhang"; r.list("can_serve").map { it.str("technique") } shouldBe listOf("wave_overhang") }
        both.list("surfaces").count { it.str("also_laid") != null } shouldBe 1
        // each loop's seam curve with its technique: the bundled scarf seams (where = "smooth") on the outer loops with no
        // corner that Z anti-aliasing does not lower, the bundled placement on the rest
        both.list("seam_styles").map { it.jsonObject.str("kind") }.distinct() shouldBe listOf("curve")
        owners(seamCurve(both, "outer_outside")) shouldBe listOf("scarf_seam", "corners")
        seamCurve(both, "outer_outside").list("owners")[0].str("loops")!! shouldContain "not lowered by Z anti-aliasing"
        owners(seamCurve(both, "inner")) shouldBe listOf("corners")
        // control: Z anti-aliasing alone (the flat tops locked as laid): ironing locked off, the flat tops as laid, nothing laid beyond the surface techniques
        Files.writeString(d.resolve("laid.toml"), "[plan.faces]\nflat_top.technique = \"as_laid\"\n")
        val zaa = explained(listOf("q2.toml", "zaa.toml", "laid.toml"), p("box.stl"), "zaa.json")
        surfaceTechnique(zaa, "ironing").str("lock") shouldBe "off"
        region(zaa, "flat_top").str("surface_technique") shouldBe "none"
        region(zaa, "sloped_top").str("surface_technique") shouldBe "z_antialiasing"
        zaa.list("surfaces").all { it.str("also_laid") == null } shouldBe true
    }

    "a fuzzy case: fuzzy skin the surface technique of the outside walls, the holes' walls as laid; no scarf on a fuzzed wall (as the toolpaths do); control: without fuzzy skin, scarf on the outside loops" {
        Files.writeString(d.resolve("fuzzy.toml"), "[plan.faces]\nside.technique = \"fuzzy_skin\"\n")
        val e = explained(listOf("q2.toml", "fuzzy.toml"), p("box.stl"), "fuzzy.json")
        surfaceTechnique(e, "fuzzy_skin").let { s -> s.str("kind") shouldBe "surface"; s.str("lock") shouldBe "on"; s.str("fate") shouldBe "chosen" }
        region(e, "wall", "outside").str("surface_technique") shouldBe "fuzzy_skin"
        region(e, "wall", "holes").str("surface_technique") shouldBe "none"
        // plan.seam = "corners" with scarf seams on (where = "smooth"): locked on, kept off the fuzzed walls
        e.list("seam_styles").map { it.jsonObject }.single { it.str("technique") == "scarf_seam" }.str("lock") shouldBe "on"
        owners(seamCurve(e, "outer_outside")) shouldBe listOf("corners")
        seamCurve(e, "outer_outside").str("why")!! shouldContain "no scarf on a fuzzed wall"
        owners(seamCurve(e, "outer_holes")) shouldBe listOf("scarf_seam", "corners")
        // control: no fuzzy skin: locked off, scarf seams on the outside loops with no corner
        val plain = explained(listOf("q2.toml"), p("box.stl"), "nofuzzy.json")
        surfaceTechnique(plain, "fuzzy_skin").str("fate") shouldBe "rejected"
        owners(seamCurve(plain, "outer_outside")) shouldBe listOf("scarf_seam", "corners")
    }

    "a locked lifted bridge: its proposal chosen, no cut across its span, anchors first; nothing measured (locked); control: unlocked, no bridge proposal" {
        Files.writeString(d.resolve("bridge.toml"), """
            [plan.object.ramp]
            pose.label = "as given (-Z down)"
            pose.turn = "1 0 0 0 1 0 0 0 1"
            band.1.technique = "bridge"
        """.trimIndent())
        val e = explained(listOf("headed.toml", "bridge.toml"), ramp.toString(), "bridge.json")
        val bridge = e.list("proposals").map { it.jsonObject }.single { it.str("technique") == "bridge" }
        bridge.str("cuts") shouldBe "none_across_span"
        bridge.str("fate") shouldBe "chosen"
        bridge.getValue("volume").jsonObject.str("to_mm") shouldBe null   // the whole object
        e.list("proposals").map { it.jsonObject }.filter { it.str("technique") == "flat" }.all { it.str("fate") == "rejected" && "locked" in it.str("why")!! } shouldBe true
        val h = e.list("handoffs").single().jsonObject
        h.str("from") shouldBe "the bed"
        h.list("entry").map { it.jsonPrimitive.content } shouldContain "anchors"
        h.str("first")!! shouldContain "anchors"
        e.getValue("candidates").jsonObject.str("not_measured")!! shouldContain "locked"
        e.getValue("decision").jsonObject.list("locks").map { it.jsonPrimitive.content }.any { it.startsWith("pose locked") } shouldBe true
        // control: without the lock the bridge is not proposed, and flat layers take the object; in flat layers the ramp's
        // underside is a cantilever (each layer steps out past a line width, anchored on one side), so support under it
        // is required and stands beside the flat layers
        val free = explained(listOf("headed.toml"), ramp.toString(), "ramp_free.json")
        free.list("proposals").none { it.str("technique") == "bridge" } shouldBe true
        free.list("portions").map { it.str("technique") }.distinct() shouldBe listOf("flat", "support")
    }
})
