package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.mesh.TriangleSoup

/**
 * [Pose.apply] gives the same posed soup for the same model and placement (what the search's caches by soup identity
 * read), its coordinates the placement's. Controls: another placement, or another model with the same coordinates, is
 * placed afresh.
 */
class PoseApplyTest : StringSpec({
    fun soup() = TriangleSoup(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 2f, 3f), 1)

    "the same model and placement give the same posed soup; another placement or model, another" {
        val s = soup()
        val flip = Pose("x down", doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 5.0))
        val a = flip.apply(s)
        (flip.apply(s) === a) shouldBe true
        // the same placement under another name is the same placement
        (Pose("again", flip.r.copyOf(), flip.t.copyOf()).apply(s) === a) shouldBe true
        // its coordinates: (x, -z, y) + (0, 0, 5)
        a.coords.toList() shouldBe listOf(0f, 0f, 5f, 1f, 0f, 5f, 0f, -3f, 7f)
        // controls
        (Pose("moved", flip.r, doubleArrayOf(0.0, 0.0, 6.0)).apply(s) === a) shouldBe false
        (flip.apply(soup()) === a) shouldBe false
    }
})
