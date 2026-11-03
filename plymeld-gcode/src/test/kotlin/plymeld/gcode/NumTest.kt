package plymeld.gcode

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** [Num.trimmed] gives byte for byte what the writer's `String.format` path gave (the g-code must not change). */
class NumTest : StringSpec({
    val edge = listOf(0.0, -0.0, 1.0, -1.0, 0.5, 2.5, -2.5, 0.0005, -0.0005, 1.0005, 0.00005, -0.00005, 0.000005, 1.0000005, 0.1234567, 123.4565, 123.4575,
        -0.0004, -0.0001, -0.000001, 0.0009995, 99.9995, 999.9999, 1e-9, -1e-9, 12345678.123456, 0.3, 0.1 + 0.2, 2.675, 1.005, 0.125, 0.375, 0.0625, 260.0, 60.0 / 7)
    val rnd = Random(7)
    val random = List(200_000) { when (it % 4) { 0 -> rnd.nextDouble(-300.0, 300.0); 1 -> rnd.nextDouble(-1.0, 1.0); 2 -> Math.round(rnd.nextDouble(-50.0, 50.0) * 1000) / 1000.0 + rnd.nextInt(-1, 2) * 1e-7; else -> rnd.nextLong(-5_000_000, 5_000_000) / 100_000.0 } }

    "three decimals, minus zero as zero (X, Y, Z, F): equal to the format path" {
        for (v in edge + random) Num.trimmed(v, 3) shouldBe Num.slow(v, 3)
    }
    "five decimals, minus zero kept (E): equal to the format path" {
        for (v in edge + random) Num.trimmed(v, 5, minusZero = true) shouldBe Num.slow(v, 5, minusZero = true)
    }
    "control: the two helpers differ on a tiny negative (so the test above would catch a mixed-up flag)" {
        Num.slow(-0.0000001, 5, minusZero = true) shouldBe "-0"
        Num.slow(-0.0000001, 3) shouldBe "0"
    }
    "the fast path is taken for ordinary values (a control that the tie band is narrow)" {
        Num.trimmed(12.3456, 3) shouldBe "12.346"
        Num.trimmed(-0.0004, 3) shouldBe "0"
        Num.trimmed(100.0, 5, minusZero = true) shouldBe "100"
    }
})
