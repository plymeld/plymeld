package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.model.Context
import plymeld.model.Entity
import plymeld.model.ObjectSetup
import plymeld.model.Resolver
import plymeld.model.Setup
import plymeld.model.SliceSettings
import plymeld.model.Sourced
import plymeld.model.Values
import plymeld.model.withObjectSetups

/**
 * Import onto the concept model, from a hand-written project settings file of test values
 * (`src/test/resources/orca/mock-project.json`: nozzle 0.4, layers 0.24, three walls, retraction 0.7, z hop 0.3,
 * travel 400, outer wall 180, four filaments on one nozzle ...): every value checked against the file it came from;
 * structure against the file's own arrays.
 */
class OrcaImportTest : StringSpec({
    val mock = OrcaImportTest::class.java.getResource("/orca/mock-project.json")!!.readText()
    fun project() = OrcaConfig.parse(mock)

    "a QIDIStudio-format project imports onto plymeld settings with the right values" {
        val c = project()
        c.keys.size shouldBe 114                                     // the file's keys
        val s = SliceSettings.of(OrcaImport.setup(c, "mock"))
        s.layerHeightUm shouldBe 240
        s.wallLoops shouldBe 3
        s.outerWallWidthUm shouldBe 440
        s.elephantFootUm shouldBe 100
        s.innerWallsFirst shouldBe true
        s.topLayers shouldBe 4                                       // four layers, no thickness
        s.sparseDensity shouldBe (0.25 plusOrMinus 1e-12)
        s.sparsePattern shouldBe "grid"
        s.retractMm shouldBe (0.7 plusOrMinus 1e-12)
        s.zHopUm shouldBe 300
        s.travelSpeed shouldBe (400.0 plusOrMinus 1e-12)
        s.visibleSpeed shouldBe (180.0 plusOrMinus 1e-12)
        s.filamentDiameterMm shouldBe (1.75 plusOrMinus 1e-12)
        s.flowRatio shouldBe (0.97 plusOrMinus 1e-12)
        s.gcodeFlavor shouldBe "klipper"
        s.visibleAccel shouldBe (2500.0 plusOrMinus 1e-9)
    }

    "one nozzle fed by four filaments: one tool, four spools; a filament's own retraction reaches its material x tool" {
        val setup = OrcaImport.setup(project(), "mock")
        setup.tools.size shouldBe 1
        setup.spools.size shouldBe 4
        setup.spools.map { it.tool }.toSet() shouldBe setOf(0)
        setup.spools.map { it.values["hardware.filament.color"]!!.value } shouldBe listOf("#FFFFFF", "#FF0000", "#00FF00", "#0000FF")
        // filament_retraction_length = [nil, 0.5, nil, nil]: slot 2 overrides the extruder's 0.7
        val mt = setup.spools.map { sp -> setup.materialTool[sp.material to sp.tool]!!["hardware.filament.retract.length"]!! }
        mt.map { it.value } shouldBe listOf(0.7, 0.5, 0.7, 0.7)
        mt[1].from shouldContain "filament_retraction_length"
        mt[0].from shouldBe "imported: mock, key retraction_length [filament 1]"   // the filament's "nil" supplied nothing
    }

    "every value says where it came from" {
        val setup = OrcaImport.setup(project(), "mock")
        val r = Resolver.resolve(setup, "plan.layer.walls.count")
        r.entityName shouldBe setup.objects[0].intent
        r.from shouldBe "imported: mock, key wall_loops"
        Resolver.resolve(setup, "hardware.filament.melt.flow.max").toString() shouldContain "material x tool"
    }

    "object overrides win for intent settings only" {
        val base = OrcaImport.setup(project(), "mock")
        val o = Values(Entity.INTENT, "override").also { it["plan.layer.walls.count"] = Sourced(4.0, "test") }
        val setup = Setup(base.machine, base.tools, base.materials, base.spools, base.materialTool, base.intents,
            listOf(ObjectSetup("mock", base.objects[0].intent, "spool 1", o)))
        SliceSettings.of(setup, Context(0)).wallLoops shouldBe 4
        // control: a machine setting cannot be put on an object
        shouldThrow<IllegalArgumentException> { o["hardware.machine.motion.travel.speed"] = Sourced(100.0, "test") }
    }

    "a project object's own keys become its overrides, read through the same rows" {
        val c = project()
        val base = OrcaImport.setup(c, "mock")
        val oi = OrcaImport.objectSetup(c, "mock", "part", mapOf("wall_loops" to "4", "sparse_infill_density" to "35%", "extruder" to "1", "no_such_key" to "x"), base.objects[0].intent)
        oi.unmapped shouldBe setOf("no_such_key")
        oi.setup.spool shouldBe "spool 1"
        val setup = base.withObjectSetups(listOf(base.objects[0], oi.setup))
        SliceSettings.of(setup, Context(1)).wallLoops shouldBe 4
        SliceSettings.of(setup, Context(1)).sparseDensity shouldBe (0.35 plusOrMinus 1e-9)
        Resolver.resolve(setup, "plan.layer.walls.count", Context(1)).from shouldBe "imported: mock, object 'part' key wall_loops"
        // control: the first object keeps the project's values, and an unchanged key is no override
        Resolver.resolve(setup, "plan.layer.walls.count", Context(0)).from shouldBe "imported: mock, key wall_loops"
        ("plan.layer.infill.pattern" in oi.setup.overrides.ids) shouldBe false
        // control: a filament slot the project does not have is refused, not mapped to spool 1
        val bad = OrcaImport.objectSetup(c, "mock", "x", mapOf("extruder" to "9"), base.objects[0].intent)
        shouldThrow<IllegalStateException> { base.withObjectSetups(listOf(bad.setup)) }
    }

    // a project with a nozzle per filament can save filament_map 1,1,1,1 with map mode "Auto For Flush" while the slicer
    // that saved it prints filament N on nozzle N. Reproduced on the mock reshaped into a 4-nozzle machine.
    "automatic map with a nozzle per filament: filament N is in tool N, whatever map was saved" {
        val base = project()
        fun c5(map: String, mode: String, single: String) = OrcaConfig(base.keys.associateWith { base.peek(it)!! } + mapOf(
            "nozzle_diameter" to List(4) { "0.4" }, "filament_diameter" to List(4) { "1.75" },
            "filament_map" to map.split(","), "filament_map_mode" to listOf(mode), "single_extruder_multi_material" to listOf(single)))
        val auto = OrcaImport.setup(c5("1,1,1,1", "Auto For Flush", "0"), "c5")
        auto.spools.map { it.tool } shouldBe listOf(0, 1, 2, 3)
        auto.importReport!!.notes.single() shouldContain "filament_map 1,1,1,1 ignored"
        // control: a manual map is followed as saved
        OrcaImport.setup(c5("2,1,4,3", "Manual", "0"), "c5").spools.map { it.tool } shouldBe listOf(1, 0, 3, 2)
        // control: one nozzle fed by several filaments (the Q2 + Box) keeps its map
        OrcaImport.setup(c5("1,1,1,1", "Auto For Flush", "1"), "q2box").spools.map { it.tool } shouldBe listOf(0, 0, 0, 0)
    }

    "foreign keys nothing maps are reported, not guessed" {
        val setup = OrcaImport.setup(project(), "mock")
        val rep = setup.importReport!!
        ("no_such_key_anywhere" in rep.unmapped) shouldBe true          // the mock's one made-up key
        ("layer_height" in rep.mapped) shouldBe true
        ("layer_height" in rep.unmapped) shouldBe false
    }

    "control: a missing key fails loudly instead of defaulting" {
        shouldThrow<IllegalStateException> { OrcaImport.setup(OrcaConfig.parse("""{"layer_height":"0.2"}"""), "broken") }
    }
})
