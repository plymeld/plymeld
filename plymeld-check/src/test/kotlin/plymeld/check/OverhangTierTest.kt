package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.program.OverhangSpeed

/**
 * Overhang wall speed (OverhangSpeed): from the wall's own speed at the start h / 2w, straight down
 * to the bridge speed at share 1, where the line hangs wholly over air (replaces
 * four tier speeds). Expected values worked by hand.
 */
class OverhangTierTest : StringSpec({
    "straight from the wall's speed at h / 2w to the bridge speed at share 1; worked by hand" {
        val from = OverhangSpeed.from(420, 80)                      // 80 / 840 = 0.0952
        OverhangSpeed.graded(0.05, 125.0, 30.0, from) shouldBe 125.0   // before the start: the wall's own
        OverhangSpeed.graded(1.0, 125.0, 30.0, from) shouldBe (30.0 plusOrMinus 1e-9)
        // halfway between the start and 1: halfway between 125 and 30
        OverhangSpeed.graded((from + 1) / 2, 125.0, 30.0, from) shouldBe (77.5 plusOrMinus 1e-9)
    }

    "never faster than the wall's own speed: a wall slower than the bridge speed is not sped up" {
        OverhangSpeed.graded(0.8, 25.0, 30.0, 0.1) shouldBe 25.0
    }

    "the start is where a bead's flat underside first reaches over air: h / 2w; 0.2 mm layers start later" {
        OverhangSpeed.from(420, 80) shouldBe (80 / 840.0)
        OverhangSpeed.graded(0.2, 125.0, 30.0, OverhangSpeed.from(420, 200)) shouldBe 125.0
        (OverhangSpeed.graded(0.2, 125.0, 30.0, OverhangSpeed.from(420, 80)) < 125.0) shouldBe true
    }
})
