package plymeld.core.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.Locale

/**
 * The pre-print check reads a line's width along it from its plastic per mm (ModelCheck.layers, Seg.bead): the path's
 * widest is its stated width. On a curved layer read flat (CurvedCheck), the layer's thickness varies along the line, and
 * its plastic per mm with it at one width: read on the layer's stated height, a thin stretch read narrow (a curved island's
 * walls 0.44 mm wide read 0.32 mm, and the hole rule refused the slits between them).
 *
 * Read here: one wall 0.44 mm wide, 10 mm long, its first half on a layer 0.2 mm thick and its second 0.1 mm (its plastic per
 * mm a 0.44 mm bead's on each): with the layer's thickness given, it reads 0.44 mm all along. Control: without it, the thin
 * half reads under 0.3 mm.
 */
class CurvedBeadTest : StringSpec({
    fun f(v: Double) = String.format(Locale.ROOT, "%.5f", v)
    /** The plastic per mm of a bead [w] wide on a layer [t] thick (its cross-section, to scale). */
    fun rate(w: Double, t: Double) = t * (w - t * (1 - Math.PI / 4))
    val text = buildString {
        append(";LAYER_CHANGE\n;Z:0.2\n;HEIGHT:0.2\n;TYPE:Outer wall\n;WIDTH:0.44\nG1 X0 Y0 F6000\n")
        for (i in 1..10) append("G1 X$i Y0 E${f(rate(0.44, if (i <= 5) 0.2 else 0.1))}\n")
    }
    val thickness = ModelCheck.Thickness { _, x, _ -> if (x < 5) 0.2 else 0.1 }

    "a line on a layer thinner along part of it reads its own width all along, given the layer's thickness" {
        val segs = ModelCheck.layers(text, thickness).single().segs
        segs.size shouldBe 10
        for (s in segs) s.bead shouldBe (0.44 plusOrMinus 1e-4)
    }

    "control: read on the layer's stated height, the thin half reads narrow" {
        val segs = ModelCheck.layers(text).single().segs
        for (s in segs.take(5)) s.bead shouldBe (0.44 plusOrMinus 1e-4)
        for (s in segs.drop(5)) s.bead shouldBeLessThan 0.3
    }
})
