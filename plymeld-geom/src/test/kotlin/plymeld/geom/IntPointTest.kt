package plymeld.geom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/**
 * Property tests for the kernel's primitive predicates. These are the pattern
 * for the rest of `plymeld-geom`: state an invariant, let kotest search for a
 * counter-example, and include a case that MUST fail so the harness is known
 * to be live (see `orientation is antisymmetric` vs the control below).
 */
class IntPointTest : StringSpec({
    // Keep coordinates well inside the exact range for cross products.
    val coord = Arb.long(-1_000_000_000L, 1_000_000_000L)
    val point = arbitrary { IntPoint(coord.bind(), coord.bind()) }

    "cross product is antisymmetric" {
        checkAll(point, point) { a, b -> (a cross b) shouldBe -(b cross a) }
    }

    "orientation flips sign when two vertices swap" {
        checkAll(point, point, point) { a, b, c ->
            orientation(a, b, c) shouldBe -orientation(a, c, b)
        }
    }

    "orientation is invariant under translation" {
        checkAll(point, point, point, point) { a, b, c, t ->
            orientation(a + t, b + t, c + t) shouldBe orientation(a, b, c)
        }
    }

    "collinear points have zero orientation" {
        orientation(IntPoint(0, 0), IntPoint(1, 1), IntPoint(3, 3)) shouldBe 0
    }

    "control: a deliberately wrong expectation is caught" {
        // If this ever passes, the test runner is not running assertions.
        runCatching { orientation(IntPoint(0, 0), IntPoint(1, 0), IntPoint(0, 1)) shouldBe -1 }
            .isFailure shouldBe true
    }

    "mm round-trips through the grid at 1 µm" {
        Units.toMm(Units.mm(12.345)) shouldBe 12.345
    }
})
