package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The incremental re-slicing contract: a stage re-runs when and only when a key it declared changed.
 * Modelled on the real chain mesh -> layers -> walls -> infill.
 */
class StageTest : StringSpec({

    "changing a setting a stage does not read must not re-run it" {
        val s = Settings(mapOf("layer_height" to "0.2", "wall_loops" to "2", "infill_density" to "15"))
        var meshHash = "mesh-A"
        val mesh = Stage("mesh", { meshHash }) { "mesh($meshHash)" }
        val layers = Stage("layers", { mesh.key() + "|" + s.keyOf("layer_height") }) { "layers(${mesh.get()},${s["layer_height"]})" }
        val walls = Stage("walls", { layers.key() + "|" + s.keyOf("wall_loops") }) { "walls(${layers.get()},${s["wall_loops"]})" }
        val infill = Stage("infill", { walls.key() + "|" + s.keyOf("infill_density") }) { "infill(${walls.get()},${s["infill_density"]})" }

        infill.get() shouldBe "infill(walls(layers(mesh(mesh-A),0.2),2),15)"
        listOf(mesh.runs, layers.runs, walls.runs, infill.runs) shouldBe listOf(1, 1, 1, 1)

        // infill density only -> only infill re-runs
        s["infill_density"] = "40"
        infill.get() shouldBe "infill(walls(layers(mesh(mesh-A),0.2),2),40)"
        listOf(mesh.runs, layers.runs, walls.runs, infill.runs) shouldBe listOf(1, 1, 1, 2)

        // wall loops -> walls and infill, not layers
        s["wall_loops"] = "3"
        infill.get()
        listOf(mesh.runs, layers.runs, walls.runs, infill.runs) shouldBe listOf(1, 1, 2, 3)

        // layer height -> everything but the mesh
        s["layer_height"] = "0.16"
        infill.get()
        listOf(mesh.runs, layers.runs, walls.runs, infill.runs) shouldBe listOf(1, 2, 3, 4)

        // new mesh -> everything
        meshHash = "mesh-B"
        infill.get() shouldBe "infill(walls(layers(mesh(mesh-B),0.16),3),40)"
        listOf(mesh.runs, layers.runs, walls.runs, infill.runs) shouldBe listOf(2, 3, 4, 5)

        // no change at all -> nothing
        infill.get()
        listOf(mesh.runs, layers.runs, walls.runs, infill.runs) shouldBe listOf(2, 3, 4, 5)
    }

    "control: a stage that forgets to declare a setting silently serves stale results" {
        // This documents the failure mode the key discipline exists to prevent: the test
        // asserts the stale behavior so a future 'auto-tracking' change is a visible event.
        val s = Settings(mapOf("a" to "1"))
        val bad = Stage("bad", { "constant" }) { "value(${s["a"]})" }
        bad.get() shouldBe "value(1)"
        s["a"] = "2"
        bad.get() shouldBe "value(1)"
        bad.runs shouldBe 1
    }

    "reading an undefined setting is an error, not an empty string" {
        val s = Settings()
        runCatching { s["nope"] }.isFailure shouldBe true
    }
})
