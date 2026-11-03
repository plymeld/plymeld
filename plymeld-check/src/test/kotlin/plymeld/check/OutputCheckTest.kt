package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import plymeld.mesh.TriangleSoup

private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): TriangleSoup {
    val v = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
        floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
    val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
    val out = FloatArray(12 * 9)
    for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d]
    return TriangleSoup(out, 12)
}

/**
 * The read-back checks of plymeld's output (GcodeCheck: against the config; ModelCheck: against the
 * model) on a small hand-written print of a 10 mm box, two layers: the file as written passes, and every
 * deliberately broken copy (a control) fails, so a pass means something.
 */
class OutputCheckTest : StringSpec({
    val model = listOf(box(100f, 100f, 0f, 110f, 110f, 0.4f))
    val start = "M83\nG28\nM109 S210"
    val end = "M104 S0"
    val expect = GcodeCheck.Expect(0.0, 0.0, 256.0, 256.0, 256.0, maxPrintSpeed = 300.0, maxTravelSpeed = 500.0, maxAccel = 10000.0,
        retractLengths = setOf(0.8), startTemplate = start, endTemplate = end)

    /** One layer: the outer wall square half a line in from the box's edge (minus the sides in [skip]), and an infill line. */
    fun layer(z: Double, skip: Set<Int> = emptySet(), infillTo: Double = 109.0): String {
        val a = 100.22; val b = 109.78
        val corners = listOf(a to a, b to a, b to b, a to b, a to a)
        val sb = StringBuilder("; CHANGE_LAYER\n; Z_HEIGHT: $z\n; LAYER_HEIGHT: 0.2\n")
        sb.append("G1 E-0.8 F2400\nG1 Z${z + 0.2} F600\nG1 X$a Y$a F18000\nG1 Z$z\nG1 E0.8 F2400\n; FEATURE: Outer wall\n; LINE_WIDTH: 0.44\nG1 F3000\n")
        for (k in 0 until 4) {
            val (x, y) = corners[k + 1]
            sb.append(if (k in skip) "G1 X$x Y$y\n" else "G1 X$x Y$y E0.3\n")
        }
        sb.append("; FEATURE: Sparse infill\nG1 X101 Y105 F18000\nG1 X$infillTo Y105 E0.25 F6000\n")
        return sb.toString()
    }
    fun file(layer2: String = layer(0.4), tail: String = "G1 E-0.8 F2400\nG1 Z2 F600\nG1 X150 Y150 F6000\nM104 S0\n") =
        "$start\nG1 X5 Y5 Z0.3 F6000\nG1 X50 Y5 E5\n" + layer(0.2) + layer2 + tail

    "the file as written passes both checks" {
        GcodeCheck.check(file(), expect).shouldBeEmpty()
        ModelCheck.check(file(), model, 0.0).shouldBeEmpty()
    }

    "control: a wall side missing is an uncovered outline" {
        ModelCheck.check(file(layer2 = layer(0.4, skip = setOf(1))), model, 0.0).any { it.contains("no wall") } shouldBe true
    }

    "control: a line printed past the model is outside it" {
        ModelCheck.check(file(layer2 = layer(0.4, infillTo = 112.0)), model, 0.0).any { it.contains("outside the model") } shouldBe true
    }

    "control: a retraction not restored in full" {
        GcodeCheck.check(file().replaceFirst("G1 E0.8 F2400\n; FEATURE: Outer wall", "G1 E0.5 F2400\n; FEATURE: Outer wall"), expect).any { it.contains("restores") } shouldBe true
    }

    "control: a retraction of another length" {
        val f = file().replace("G1 E-0.8 F2400\nG1 Z0.6", "G1 E-0.5 F2400\nG1 Z0.6").replace("G1 E0.8 F2400\n; FEATURE: Outer wall\n; LINE_WIDTH: 0.44\nG1 F3000\nG1 X109.78 Y100.22 E0.3\nG1 X109.78 Y109.78 E0.3\nG1 X100.22 Y109.78 E0.3\nG1 X100.22 Y100.22 E0.3\n; FEATURE: Sparse infill\nG1 X101 Y105 F18000\nG1 X109.0 Y105 E0.25 F6000\nG1 E-0.8",
            "G1 E0.5 F2400\n; FEATURE: Outer wall\n; LINE_WIDTH: 0.44\nG1 F3000\nG1 X109.78 Y100.22 E0.3\nG1 X109.78 Y109.78 E0.3\nG1 X100.22 Y109.78 E0.3\nG1 X100.22 Y100.22 E0.3\n; FEATURE: Sparse infill\nG1 X101 Y105 F18000\nG1 X109.0 Y105 E0.25 F6000\nG1 E-0.8")
        GcodeCheck.check(f, expect).any { it.contains("the config's are") } shouldBe true
    }

    "control: a travel at print height across the outer wall" {
        // on the second layer, the travel to the infill detours out of the box and back in across the wall
        val f = file(layer2 = layer(0.4).replace("G1 X101 Y105 F18000", "G1 X101 Y95 F18000\nG1 X101 Y105 F18000"))
        GcodeCheck.check(f, expect).any { it.contains("across the Outer wall") } shouldBe true
    }

    // An arc (G2 / G3) is read where the nozzle goes, not as its chord (a tube's outer wall, written as arcs, had a
    // travel inside the wall "across the Outer wall" because the chord cut inside the circle). The wall's +X side as an arc
    // bulging 1.2 mm out (to x 110.98 at y 105): a travel from x 106 out to x 110.7 crosses the chord (x 109.78) within the part
    // the rule reads (all but its first and last 0.75 mm) and not the arc; control: one out to x 112.5 crosses the arc itself.
    "an arc read as the nozzle goes: a travel inside its bulge crosses nothing; control: one past it crosses the wall" {
        fun arcFile(to: Double) = file(layer2 = layer(0.4).replaceFirst("G1 X109.78 Y109.78 E0.3\n", "G3 X109.78 Y109.78 I-8.9 J4.78 E0.35\n")
            .replace("G1 X101 Y105 F18000", "G1 X106 Y105 F18000\nG1 X$to Y105 F18000\nG1 X101 Y105 F18000"))
        GcodeCheck.check(arcFile(110.7), expect).none { it.contains("across the Outer wall") } shouldBe true
        GcodeCheck.check(arcFile(112.5), expect).any { it.contains("across the Outer wall") } shouldBe true
    }

    "control: the end moving sideways without lifting" {
        GcodeCheck.check(file(tail = "G1 E-0.8 F2400\nG1 X150 Y150 F6000\nM104 S0\n"), expect).any { it.contains("not above the print") } shouldBe true
    }

    "control: printing faster than the machine prints" {
        GcodeCheck.check(file().replaceFirst("G1 F3000", "G1 F30000"), expect).any { it.contains("over the machine's 300") } shouldBe true
    }

    "control: the start template's lines missing" {
        GcodeCheck.check(file().replaceFirst("G28\n", ""), expect).any { it.contains("start template line 'G28'") } shouldBe true
    }

    // Klipper's exclude-object definition carries its outline as a list of numbers in brackets: not a placeholder (
    // every slice with exclude-object on refused its own output). Control: a square-bracket placeholder is still caught.
    "an exclude-object outline in brackets is no placeholder; control: [first_layer_temperature] is one" {
        val define = "EXCLUDE_OBJECT_DEFINE NAME=box_id_0_copy_0 CENTER=105,105 POLYGON=[[100,100],[110,100],[110,110],[100,110]]"
        GcodeCheck.check(file().replaceFirst("M109 S210\n", "M109 S210\n$define\n"), expect).none { it.contains("placeholder") } shouldBe true
        GcodeCheck.check(file().replaceFirst("M109 S210\n", "M109 S210\nM104 S[first_layer_temperature]\n"), expect).any { it.contains("placeholder") } shouldBe true
    }

    // E values are amounts: without M83 first the printer reads them as positions (a start template of "G28" alone).
    "control: extruding without relative extrusion set (no M83) is refused; the file as written is not" {
        GcodeCheck.check(file(), expect).none { it.contains("relative extrusion not set") } shouldBe true
        GcodeCheck.check(file().replaceFirst("M83\n", ""), expect).any { it.contains("relative extrusion not set") } shouldBe true
        GcodeCheck.check(file().replaceFirst("M109 S210\n", "M109 S210\nM82\n"), expect).any { it.contains("relative extrusion not set") } shouldBe true
    }

    "control: a placeholder left unrendered" {
        GcodeCheck.check(file().replaceFirst("M109 S210", "M109 S{nozzle_temperature}"), expect).any { it.contains("placeholder") } shouldBe true
    }

    // Scarf seams (plan.technique.scarf_seam.where): the second layer's outer wall starts [startZ] high (a quarter of the layer
    // above its bottom, 0.25, with plan.technique.scarf_seam.start 0.25), rises to the layer along the first side, goes round at the
    // layer, then re-prints the first side at the layer, thinning.
    fun scarfLayer(z: Double, startZ: Double, h: Double = 0.2): String {
        val a = 100.22; val b = 109.78
        return "; CHANGE_LAYER\n; Z_HEIGHT: $z\n; LAYER_HEIGHT: $h\nG1 E-0.8 F2400\nG1 Z${z + 0.2} F600\nG1 X$a Y$a F18000\nG1 Z$startZ\nG1 E0.8 F2400\n" +
            "; FEATURE: Outer wall\n; LINE_WIDTH: 0.44\nG1 F3000\n" +
            "G1 X105 Y$a Z${(startZ + z) / 2} E0.08\nG1 X$b Y$a Z$z E0.15\n" +
            "G1 X$b Y$b E0.3\nG1 X$a Y$b E0.3\nG1 X$a Y$a E0.3\n" +
            "G1 X105 Y$a E0.15\nG1 X$b Y$a E0.07\n" +
            "; FEATURE: Sparse infill\nG1 X101 Y105 F18000\nG1 X109 Y105 E0.25 F6000\n"
    }
    val scarfExpect = GcodeCheck.Expect(0.0, 0.0, 256.0, 256.0, 256.0, maxPrintSpeed = 300.0, maxTravelSpeed = 500.0, maxAccel = 10000.0,
        retractLengths = setOf(0.8), startTemplate = start, endTemplate = end, scarfStart = 0.25)

    "a scarf ramp from a quarter of the layer up passes when the config scarfs outer walls (scarf.start 0.25)" {
        GcodeCheck.check(file(layer2 = scarfLayer(0.4, 0.25)), scarfExpect).shouldBeEmpty()
    }
    "control: the same ramp without a scarf in the config is printing below the layer" {
        GcodeCheck.check(file(layer2 = scarfLayer(0.4, 0.25)), expect).any { it.contains("printing at Z0.325 on the layer at 0.400") } shouldBe true
    }
    "control: a ramp starting under the scarf's depth (Z 0.21, under 0.2 + 0.25 x 0.2 = 0.25) is refused" {
        val p = GcodeCheck.check(file(layer2 = scarfLayer(0.4, 0.21)), scarfExpect)
        // its first line ends at Z 0.305, inside the band: the start at 0.21 is what is wrong
        p.any { it.contains("an outer wall line starting at Z0.210, under a scarf ramp's lowest 0.250") } shouldBe true
    }
    // A layer whose ramp's lowest falls between the file's 0.001 mm steps: 0.245 mm thick at 0.445, its lowest
    // 0.445 - 0.75 x 0.245 = 0.26125, which a writer printing three decimals rounds to 0.261 (the adaptive Benchy)
    "a ramp starting at its lowest rounded to the file's 0.001 mm steps passes" {
        GcodeCheck.check(file(layer2 = scarfLayer(0.445, 0.261, h = 0.245)), scarfExpect).shouldBeEmpty()
    }
    "control: one step lower (Z 0.260, 0.00125 under 0.26125) is refused" {
        GcodeCheck.check(file(layer2 = scarfLayer(0.445, 0.260, h = 0.245)), scarfExpect)
            .any { it.contains("an outer wall line starting at Z0.260, under a scarf ramp's lowest 0.261") } shouldBe true
    }
})
