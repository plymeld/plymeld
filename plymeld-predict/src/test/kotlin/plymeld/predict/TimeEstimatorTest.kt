package plymeld.predict

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.BufferedReader
import java.io.StringReader

class TimeEstimatorTest : StringSpec({
    val est = TimeEstimator(maxVelocity = 300.0, maxAccel = 20000.0, maxZVelocity = 20.0, maxZAccel = 500.0, squareCornerVelocity = 5.0)
    fun time(accelLine: String): Double {
        val moves = (1..20).joinToString("\n") { k -> "G1 X${if (k % 2 == 0) 0 else 50} Y${k * 2} F18000" }
        return est.estimate(BufferedReader(StringReader("$accelLine\n$moves\n"))).totalS
    }

    "an acceleration is read in either dialect: M204 S and SET_VELOCITY_LIMIT ACCEL= time alike" {
        val m204 = time("M204 S1000")
        time("SET_VELOCITY_LIMIT ACCEL=1000") shouldBe (m204 plusOrMinus 1e-9)
        // control: with no acceleration command the moves run at the machine's 20000 mm/s2, so they take less time
        time("") shouldBeLessThan m204 * 0.8
    }
})
