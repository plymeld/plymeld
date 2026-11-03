package plymeld.check

import io.kotest.core.spec.style.StringSpec
import plymeld.geom.Offset
import plymeld.mesh.LayerPlan
import plymeld.mesh.Slicer
import plymeld.mesh.StlReader

/**
 * Where the slice stage's time goes on the default test model (3DBenchy, 225,706 triangles, closing radius 49 µm and resolution 12 µm as in the Q2 preset,
 * 240 layers): reading, cutting triangles into loops, and the clean-up (closing gaps with two
 * offsets per layer, then simplifying). A report for G1, not a pass/fail budget.
 */
class SliceTimingTest : StringSpec({
    "Benchy slice stage: time by sub-step" {
        repeat(2) { round ->   // round 0 warms the JIT; round 1 is reported
            var t = System.nanoTime()
            fun lap(): Long { val n = System.nanoTime(); val d = (n - t) / 1_000_000; t = n; return d }
            val soup = StlReader.read(testModelsDir().resolve("3DBenchy.stl"))
            val read = lap()
            val b = soup.bounds()
            val plan = QidiLayers.precise(b[2], b[5], 0.2, 0.2)
            val layers = Slicer.slice(soup, plan.slices)
            val cut = lap()
            val closed = layers.map { l -> if (l.loops.isEmpty()) l.loops else Offset.offset(Offset.offset(l.loops, 49, Offset.Join.ROUND), -49, Offset.Join.ROUND) }
            val close = lap()
            closed.map { ls -> ls.map { it.douglasPeucker(12) } }
            val simplify = lap()
            val verts = layers.sumOf { l -> l.loops.sumOf { it.size } }
            // the other order: simplify first, then close gaps on the smaller outlines
            val simplified = layers.map { l -> l.loops.map { it.douglasPeucker(12) }.filter { it.size >= 3 && it.signedArea2() != 0L } }
            val simplifyFirst = lap()
            simplified.map { ls -> if (ls.isEmpty()) ls else Offset.offset(Offset.offset(ls, 49, Offset.Join.ROUND), -49, Offset.Join.ROUND) }
            val closeAfter = lap()
            val sverts = simplified.sumOf { ls -> ls.sumOf { it.size } }
            if (round == 1) println("benchy slice stage: read $read ms, cut $cut ms (${layers.size} layers, $verts loop vertices), close gaps $close ms, simplify $simplify ms | " +
                "simplify first $simplifyFirst ms ($sverts vertices), then close gaps $closeAfter ms")
        }
    }
})
