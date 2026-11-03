package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.ConfigStack
import java.nio.file.Files
import java.nio.file.Path

/**
 * A curved island locked by a plan (plan.object.<key>.island.<key>):
 * named by a point on its cap, found on the posed object, carried on the technique plan; `plan -o` writes it back with its
 * start and own layer height, and reading that back gives the same island. A point on no cap, layers thinner than the tool
 * allows where they dip and an island beside a band are refused, each saying why. The model: [IslandPin].
 */
class CurvedIslandLockTest : StringSpec({
    fun dir(vararg files: Pair<String, String>): Path = Files.createTempDirectory("plymeld-island").also { d -> for ((n, t) in files) Files.writeString(d.resolve(n), t) }
    fun product(d: Path, cfs: List<String>) = Product(ConfigStack.load(cfs, emptyList(), d), listOf(d.resolve("pin.stl")), null) { }
    fun lock(vararg lines: String) = "[plan.object.\"pin.stl\"]\n" + lines.joinToString("\n") + "\n"

    "an island locked by a point on its cap: found, written back with its start and layer height, read back the same; mistakes refused" {
        val d = dir("hw.toml" to IslandPin.hardware, "pin.stl" to IslandPin.stl(), "isl.toml" to IslandPin.lock())
        val pr = product(d, listOf("hw.toml", "isl.toml"))
        pr.overrides().objects.single().islands.single().key shouldBe "cap"
        val plan = pr.decide()
        val isl = plan.layout.techniques.single().islands.single()
        println("locked: $isl, steepest %.2f deg".format(java.util.Locale.ROOT, isl.maxSlopeDeg()))
        // its top is the cap's (12 + 3 mm), it starts below it, its layers no thinner than the flat ones nor thicker than the tool's
        // thickest, its top layer no steeper than the nozzle angle
        isl.topMm shouldBe (15.0 plusOrMinus 0.3)
        (isl.startMm < isl.topMm) shouldBe true
        (isl.layerMm >= 0.2 - 1e-9 && isl.layerMm <= 0.3 + 1e-9) shouldBe true
        (isl.maxSlopeDeg() <= 45.0 + 1e-9) shouldBe true
        // the plan file writes it, and reads back as the same island
        val file = pr.planFile(plan)
        file shouldContain "island.cap.x = \"15"
        file shouldContain "island.cap.start = "
        file shouldContain "island.cap.layer.height = "
        Files.writeString(d.resolve("plan.toml"), file)
        val back = product(d, listOf("hw.toml", "plan.toml")).decide().layout.techniques.single().islands.single()
        back.startMm shouldBe (isl.startMm plusOrMinus 1e-6)
        back.layerMm shouldBe (isl.layerMm plusOrMinus 1e-6)
        back.cells.toList() shouldBe isl.cells.toList()
        back.fdip.toList() shouldBe isl.fdip.toList()
        // control: without the lock, and no island wanted (job.faces.sloped_top.look; unset, the decision weighs the cap's island), none
        Files.writeString(d.resolve("steps.toml"), "[job]\nfaces.sloped_top.look = \"concentric_steps\"\n")
        product(d, listOf("hw.toml", "steps.toml")).decide().layout.techniques.single().islands.size shouldBe 0
        // a point on the flat plate: no cap holds it
        Files.writeString(d.resolve("plate.toml"), lock("island.cap.x = \"3 mm\"", "island.cap.y = \"3 mm\""))
        shouldThrow<IllegalStateException> { product(d, listOf("hw.toml", "plate.toml")).decide() }.message!! shouldContain "no cap holds that point"
        // layers thinner than the island needs where they dip most
        Files.writeString(d.resolve("thin.toml"), IslandPin.lock("island.cap.start = \"${isl.startMm} mm\"", "island.cap.layer.height = \"0.08 mm\""))
        shouldThrow<IllegalStateException> { product(d, listOf("hw.toml", "thin.toml")).decide() }.message!! shouldContain "thinner than the tool's thinnest"
        // an island beside a curved band
        Files.writeString(d.resolve("band.toml"), IslandPin.lock("band.1.technique = \"curved\""))
        shouldThrow<IllegalStateException> { product(d, listOf("hw.toml", "band.toml")).decide() }.message!! shouldContain "curved islands sit on flat layers"
        // x without y
        Files.writeString(d.resolve("half.toml"), lock("island.cap.x = \"15 mm\""))
        shouldThrow<UsageError> { product(d, listOf("hw.toml", "half.toml")).overrides() }.message!! shouldContain "has no y"
    }
})
