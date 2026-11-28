package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
 * allows where they dip and an island beside a band are refused, each saying why. The nozzle's cone it is found with: the
 * tool's own, else its machine model's toolhead's, the strictest of an object's tools; refused with neither. The model: [IslandPin].
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

    // The nozzle's cone where the tool sets none: its machine model's toolhead's (the bundled Creator 5's are 45 deg and 4 mm on
    // all four, measured), as every curved technique reads it (NozzleCone, Plan.nozzleCone).
    /**
     * [IslandPin]'s machine as a Creator 5, tool t1 (index 1, the C5's first toolhead) with [t1Cone] for its own cone lines
     * (empty: none); with [t2], a second tool (index 2) with those cone lines, both loaded.
     */
    fun c5(t1Cone: String, t2: String? = null): String {
        val cone = "head.nozzle.angle = \"45 deg\"\nhead.nozzle.height = \"4 mm\"\n"
        require(cone in IslandPin.hardware)
        var h = IslandPin.hardware.replace("model = \"qidi-q2\"", "model = \"flashforge-c5\"")
            .replace("gcode.start = \"M109 S{first_layer_nozzle_temperature}\"", "gcode.start = \"M83\\nT{initial_tool}\\nM109 S{first_layer_nozzle_temperature} T{initial_tool}\"")
            .replace("[hardware.tool.t1]\nmachine = \"m\"\n", "[hardware.tool.t1]\nmachine = \"m\"\nindex = \"1\"\n")
            .replace(cone, t1Cone.let { if (it.isEmpty()) "" else it.trimEnd('\n') + "\n" })
        if (t2 != null) h = h.replace("tool.t1.filament = \"pla-generic\"", "tool.t1.filament = \"pla-generic\"\ntool.t2.filament = \"pla-generic\"") +
            "\n[hardware.tool.t2]\nmachine = \"m\"\nindex = \"2\"\nbore = \"0.4 mm\"\nlayer.height.min = \"= tool.bore / 5\"\nlayer.height.max = \"= tool.bore * 0.75\"\n" + t2 + "\n"
        return h
    }
    /** The plan's fact stating the cone a locked island was found with. */
    fun coneFact(plan: plymeld.core.decide.Plan): String = plan.decisions.flatMap { it.facts }.single { it.startsWith("the nozzle's cone: ") }
    /** The pre-print check's problems with the plan's g-code. */
    fun checked(pr: Product, d: Path): List<String> {
        val session = SliceSession()
        val w = session.write(pr, d.resolve("pin.gcode"), { })
        return session.check(SliceSession.Written(w.pr, w.plan, w.setup, w.output, w.text, w.partTools, w.prediction, w.reused), { }).problems
    }

    "a locked island on a machine whose model gives the nozzle's cone, the tool none: planned with the model's, its check clean; the tool's own wins; control: neither, refused naming both places" {
        val d = dir("hw.toml" to c5(""), "pin.stl" to IslandPin.stl(), "isl.toml" to IslandPin.lock())
        val pr = product(d, listOf("hw.toml", "isl.toml"))
        val plan = pr.decide()
        val isl = plan.layout.techniques.single().islands.single()
        val fact = coneFact(plan)
        println("model's cone: $fact; $isl, steepest %.2f deg".format(java.util.Locale.ROOT, isl.maxSlopeDeg()))
        fact shouldContain "45 deg (hardware.tool.<key>.head.nozzle.angle: bundled 03_models.toml:513 via the machine's model flashforge-c5, toolhead 0)"
        fact shouldContain "its tip 4 mm below the head (hardware.tool.<key>.head.nozzle.height: bundled 03_models.toml:512 via the machine's model flashforge-c5, toolhead 0)"
        // the pre-print check finds the island again with the same cone, and judges the head's clearance over it
        withClue("the check") { checked(pr, d).shouldBeEmpty() }
        // the tool's own values win over the model's; one set alone takes the other from the model
        Files.writeString(d.resolve("own.toml"), "[hardware.tool.t1]\nhead.nozzle.angle = \"40 deg\"\nhead.nozzle.height = \"3 mm\"\n")
        val own = product(d, listOf("hw.toml", "isl.toml", "own.toml")).decide()
        coneFact(own) shouldContain "40 deg (hardware.tool.<key>.head.nozzle.angle: "
        coneFact(own) shouldContain "its tip 3 mm below the head (hardware.tool.<key>.head.nozzle.height: "
        (own.layout.techniques.single().islands.single().maxSlopeDeg() <= 40.0 + 1e-9) shouldBe true
        Files.writeString(d.resolve("angle.toml"), "[hardware.tool.t1]\nhead.nozzle.angle = \"40 deg\"\n")
        val half = coneFact(product(d, listOf("hw.toml", "isl.toml", "angle.toml")).decide())
        half shouldContain "40 deg (hardware.tool.<key>.head.nozzle.angle: "
        half shouldContain "its tip 4 mm below the head (hardware.tool.<key>.head.nozzle.height: bundled 03_models.toml:512 via the machine's model flashforge-c5, toolhead 0)"
        // control: a model whose toolhead gives no cone (the Q2's), the tool none: refused, naming where to set it
        Files.writeString(d.resolve("bare.toml"), IslandPin.hardware.replace("head.nozzle.angle = \"45 deg\"\nhead.nozzle.height = \"4 mm\"\n", ""))
        val e = shouldThrow<IllegalStateException> { product(d, listOf("bare.toml", "isl.toml")).decide() }.message!!
        e shouldContain "a curved island needs the nozzle's angle and height"
        e shouldContain "hardware.tool.<key>.head.nozzle.angle"
        e shouldContain "hardware.model.<key>.toolhead.<index>.head.nozzle.angle"
    }

    // (its g-code is not checked here: an island is laid with the object's own tool yet, which the pre-print check refuses
    // where the island's part prints with another; the check's cone is read through curvedLimits instead)
    "an object printed with two tools: its island found with the strictest of their cones, each the tool's own or its model's, the check's the same; control: the stricter on the other tool" {
        // the pin's plate on tool 1, its column (the island's) on tool 2; tool 2 sets no cone (the C5's toolhead 1: 45 deg, 4 mm)
        val d = Files.createTempDirectory("plymeld-island-tools")
        TechniqueSamples.threeMf(d.resolve("pin.3mf"), "pin", IslandPin.parts())
        Files.writeString(d.resolve("parts.toml"), "[model.object.pin.part.pin_plate]\nslot = \"1\"\n[model.object.pin.part.pin_column]\nslot = \"2\"\n" +
            "[plan.object.pin]\nisland.cap.x = \"15 mm\"\nisland.cap.y = \"15 mm\"\n")
        fun run(t1Cone: String): Pair<Product, plymeld.core.decide.Plan> {
            Files.writeString(d.resolve("hw.toml"), c5(t1Cone, t2 = ""))
            val pr = Product(ConfigStack.load(listOf("hw.toml", "parts.toml"), emptyList(), d), listOf(d.resolve("pin.3mf")), null) { }
            return pr to pr.decide()
        }
        // tool 1's own 40 deg, 3 mm is the stricter: the island is held to it, though tool 2 lays it
        val (pr, plan) = run("head.nozzle.angle = \"40 deg\"\nhead.nozzle.height = \"3 mm\"")
        plan.parts.size shouldBe 2
        coneFact(plan) shouldContain "40 deg (tool 1's, the strictest of the object's tools: hardware.tool.<key>.head.nozzle.angle: "
        coneFact(plan) shouldContain "its tip 3 mm below the head (tool 1's, the strictest"
        // the pre-print check holds the island to the same cone
        curvedLimits(plan, pr.result.setup, 0).tip!!.let { listOf(it.coneDeg, it.protrusionMm) } shouldBe listOf(40.0, 3.0)
        // control: tool 1's own 50 deg, 5 mm: tool 2's model values (45 deg, 4 mm) are now the stricter
        val (pr2, other) = run("head.nozzle.angle = \"50 deg\"\nhead.nozzle.height = \"5 mm\"")
        coneFact(other) shouldContain "45 deg (tool 2's, the strictest of the object's tools: hardware.tool.<key>.head.nozzle.angle: bundled 03_models.toml:530 via the machine's model flashforge-c5, toolhead 1)"
        coneFact(other) shouldContain "its tip 4 mm below the head (tool 2's, the strictest"
        curvedLimits(other, pr2.result.setup, 0).tip!!.let { listOf(it.coneDeg, it.protrusionMm) } shouldBe listOf(45.0, 4.0)
    }
})
