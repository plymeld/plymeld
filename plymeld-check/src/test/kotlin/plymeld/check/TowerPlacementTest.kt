package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.model.withObjectSetups
import java.util.Locale

/**
 * Where the purge tower stands, end to end on the two-color Benchy (a machine of the bundled `flashforge-c5` model, two
 * tools): with plan.mechanism.purge.tower.x and .y unset, beside the object, within a tower's width of it and at least
 * plan.mechanism.purge.tower.gap clear of it and its brim (a tower at the bed's corner, far from the object, peeled off
 * there twice). Control: with them set, where they say. And the tower's first layer, its brim included, no faster than
 * plan.mechanism.purge.tower.first_layer.speed, the object's first layer at its own speed. Read back from the g-code TEXT.
 */
class TowerPlacementTest : StringSpec({
    fun slice(vararg extra: Pair<String, Any>): String {
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "G90", "hardware.machine.tm.firmware.exclude_object" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            "plan.mechanism.purge.tower.brim.enabled" to true, "plan.mechanism.purge.tower.brim.width" to "4 mm",
            *extra,
            models = listOf(plymeld.config.ModelObject("test", listOf("A", "B"), listOf(1, 2), "benchy-dual.3mf")))
        val o = base.objects[0]
        val setup = base.withObjectSetups(listOf(plymeld.model.ObjectSetup(o.name, o.intent, "spool 1", o.overrides, listOf(
            plymeld.model.PartSetup("A", "spool 1", plymeld.model.Values(plymeld.model.Entity.INTENT, "A")),
            plymeld.model.PartSetup("B", "spool 2", plymeld.model.Values(plymeld.model.Entity.INTENT, "B"))))))
        val raw = benchyDualParts()
        val b = plymeld.core.decide.PoseDecision.merged(raw).bounds()
        val move = plymeld.core.decide.Pose("placed", plymeld.core.decide.Pose.IDENTITY, doubleArrayOf(128 - (b[0] + b[3]) / 2, 128 - (b[1] + b[4]) / 2, -b[2]))
        val out = java.io.StringWriter()
        plymeld.core.IncrementalSlicer(raw.map(move::apply), "test", "benchy-dual").slice(setup, out)
        return out.toString()
    }

    /** Per feature on the first layer (or every layer with [allLayers]): the X/Y extents of its extrusions and the fastest feed (mm/min). */
    class Box(var x0: Double = 1e9, var y0: Double = 1e9, var x1: Double = -1e9, var y1: Double = -1e9, var fMax: Double = 0.0)
    fun boxes(gcode: String, allLayers: Boolean): Map<String, Box> {
        val out = HashMap<String, Box>()
        var layer = 0; var x = 0.0; var y = 0.0; var f = 0.0; var feature = ""
        for (line in gcode.lineSequence()) {
            if (line.startsWith(";LAYER_CHANGE")) { layer++; if (!allLayers && layer > 1) break }
            if (line.startsWith(";TYPE:")) feature = line.substringAfter(":").trim()
            if (!(line.startsWith("G1 ") || line.startsWith("G2 ") || line.startsWith("G3 "))) continue
            var nx = x; var ny = y; var e = 0.0
            for (m in Regex("([XYEF])(-?[\\d.]+)").findAll(line.substringBefore(';'))) {
                val v = m.groupValues[2].toDouble()
                when (m.groupValues[1]) { "X" -> nx = v; "Y" -> ny = v; "E" -> e = v; "F" -> f = v }
            }
            if (layer >= 1 && e > 0 && (nx != x || ny != y)) {
                val bx = out.getOrPut(feature) { Box() }
                bx.x0 = minOf(bx.x0, x, nx); bx.x1 = maxOf(bx.x1, x, nx); bx.y0 = minOf(bx.y0, y, ny); bx.y1 = maxOf(bx.y1, y, ny); bx.fMax = maxOf(bx.fMax, f)
            }
            x = nx; y = ny
        }
        return out
    }

    "unset, the tower stands beside the object, clear of it by the gap; control: set, where it says" {
        // the object with a brim (5 mm, 0.1 mm from it, in the test plan)
        val beside = boxes(slice("plan.mechanism.purge.tower.x" to Unset, "plan.mechanism.purge.tower.y" to Unset,
            "plan.mechanism.adhesion.brim.enabled" to true), allLayers = true)
        val tower = beside.getValue("Prime tower")
        // the object: every extrusion that is not the tower's or a brim's
        val model = beside.filterKeys { it != "Prime tower" && it != "Brim" }.values
        val mx0 = model.minOf { it.x0 }; val mx1 = model.maxOf { it.x1 }; val my0 = model.minOf { it.y0 }; val my1 = model.maxOf { it.y1 }
        // the gap between the tower's lines and the object's, along whichever side the tower stands
        val dx = maxOf(tower.x0 - mx1, mx0 - tower.x1); val dy = maxOf(tower.y0 - my1, my0 - tower.y1)
        val gap = maxOf(dx, dy)
        println("  beside: object X %.1f..%.1f Y %.1f..%.1f; tower X %.1f..%.1f Y %.1f..%.1f; gap %.1f mm".format(Locale.ROOT,
            mx0, mx1, my0, my1, tower.x0, tower.x1, tower.y0, tower.y1, gap))
        // clear of the object's brim (5.1 mm), the 5 mm gap and the tower's own brim (4 mm), line center to line center
        (gap >= 5.1 + 5.0 + 4.0 - 0.5) shouldBe true
        // and beside it: no more than a line or two beyond that
        (gap <= 5.1 + 5.0 + 4.0 + 1.5) shouldBe true
        // the tower overlaps the object along that side (centered on it), not off at a corner
        val overlapX = minOf(tower.x1, mx1) - maxOf(tower.x0, mx0); val overlapY = minOf(tower.y1, my1) - maxOf(tower.y0, my0)
        (overlapX > 0 || overlapY > 0) shouldBe true
        // control: set, at (165, 232) as the test plan says (the tower's own lines start a line inside its corner)
        val set = boxes(slice(), allLayers = true).getValue("Prime tower")
        (Math.abs(set.x0 - 165.0) < 1.0 && Math.abs(set.y0 - 232.0) < 1.0) shouldBe true
    }

    "the tower's first layer, its brim included, no faster than its first-layer speed; control: the object's first layer at its own" {
        val gcode = slice("plan.mechanism.purge.tower.first_layer.speed" to "20 mm/s")
        val first = boxes(gcode, allLayers = false)
        val tower = first.getValue("Prime tower")
        // the tower's brim: the first layer's brim moves within its 4 mm (and a line) of the tower's lines
        var layer = 0; var x = 0.0; var y = 0.0; var f = 0.0; var feature = ""; var towerBrimMax = 0.0; var towerBrimMoves = 0
        for (line in gcode.lineSequence()) {
            if (line.startsWith(";LAYER_CHANGE")) { layer++; if (layer > 1) break }
            if (line.startsWith(";TYPE:")) feature = line.substringAfter(":").trim()
            if (!line.startsWith("G1 ")) continue
            var nx = x; var ny = y; var e = 0.0
            for (m in Regex("([XYEF])(-?[\\d.]+)").findAll(line.substringBefore(';'))) {
                val v = m.groupValues[2].toDouble()
                when (m.groupValues[1]) { "X" -> nx = v; "Y" -> ny = v; "E" -> e = v; "F" -> f = v }
            }
            val near = { px: Double, py: Double -> px in tower.x0 - 5..tower.x1 + 5 && py in tower.y0 - 5..tower.y1 + 5 }
            if (layer == 1 && feature == "Brim" && e > 0 && (nx != x || ny != y) && near(x, y) && near(nx, ny)) { towerBrimMax = maxOf(towerBrimMax, f); towerBrimMoves++ }
            x = nx; y = ny
        }
        println("  first layer: tower at most %.0f mm/s, its brim (%d moves) %.0f, bottom %.0f".format(Locale.ROOT, tower.fMax / 60, towerBrimMoves, towerBrimMax / 60, first.getValue("Bottom surface").fMax / 60))
        (tower.fMax <= 20 * 60 + 1e-6) shouldBe true
        (towerBrimMoves > 0) shouldBe true
        (towerBrimMax <= 20 * 60 + 1e-6) shouldBe true
        // the object's bottom keeps the first layer's 50 mm/s (the test plan's)
        (first.getValue("Bottom surface").fMax > 20 * 60 + 1) shouldBe true
    }
})
