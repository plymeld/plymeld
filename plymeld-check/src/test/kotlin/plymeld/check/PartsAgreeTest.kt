package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.ModelObject
import plymeld.core.IncrementalSlicer
import plymeld.mesh.TriangleSoup
import plymeld.model.Context
import plymeld.model.Resolver
import plymeld.model.Sourced

/**
 * A part's own value that equals the one it would have anyway (here a project's per-part key restating the object's sparse
 * density, which an import keeps) changes nothing: the object is not laid part by part, so touching parts are one area.
 * Control: a value that differs, laid part by part. And [Resolver.inherited] gives the value without the part's own.
 */
class PartsAgreeTest : StringSpec({
    fun box(x0: Float, x1: Float): TriangleSoup {
        val v = arrayOf(floatArrayOf(x0, 0f, 0f), floatArrayOf(x1, 0f, 0f), floatArrayOf(x1, 10f, 0f), floatArrayOf(x0, 10f, 0f),
            floatArrayOf(x0, 0f, 4f), floatArrayOf(x1, 0f, 4f), floatArrayOf(x1, 10f, 4f), floatArrayOf(x0, 10f, 4f))
        val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        val out = FloatArray(12 * 9)
        for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d]
        return TriangleSoup(out, 12)
    }
    val parts = listOf(box(100f, 110f), box(110f, 120f))
    val id = "plan.layer.infill.density"
    /** Whether the pair is laid part by part, part b given [value] of its own for the sparse density. */
    fun perPart(value: (Any) -> Any): Boolean {
        val setup = testSetup(models = listOf(ModelObject("pair", listOf("a", "b"), listOf(1, 1), "pair.3mf")))
        val objects = Resolver.resolve(setup, id, Context(0)).value
        setup.objects[0].parts[1].overrides[id] = Sourced(value(objects), "test: part b's own")
        Resolver.inherited(setup, id, Context(0, 1))!!.value shouldBe objects
        return IncrementalSlicer(parts, "pair").isMultiFilament(setup)
    }

    "a part's own value equal to the object's: laid as one; control: a value that differs, laid part by part" {
        perPart { it } shouldBe false
        perPart { (it as Double) * 2 } shouldBe true
    }
})
