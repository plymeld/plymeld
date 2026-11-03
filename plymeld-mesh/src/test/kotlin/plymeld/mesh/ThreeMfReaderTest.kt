package plymeld.mesh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The two-part Benchy against the frame measured independently of plymeld (`test-models/MANIFEST.md`), and hand-written
 * files: refusals, and a project's per-object and per-part settings, subtypes and instances.
 */
class ThreeMfReaderTest : StringSpec({
    val models = TestModels.dir()

    "3DBenchy-dual.3mf: two parts, together in the frame measured independently (x -32.2..27.8, y -30.5..0.5, z 0..48)" {
        val model = ThreeMfReader.read(models.resolve("3DBenchy-dual.3mf"))
        model.parts.size shouldBe 2
        val bs = model.parts.map { it.soup.bounds() }
        val lo = DoubleArray(3) { k -> bs.minOf { it[k] } }; val hi = DoubleArray(3) { k -> bs.maxOf { it[k + 3] } }
        // the manifest's figures are to 0.1 mm
        lo[0] shouldBe (-32.2 plusOrMinus 0.06); hi[0] shouldBe (27.8 plusOrMinus 0.06)
        lo[1] shouldBe (-30.5 plusOrMinus 0.06); hi[1] shouldBe (0.5 plusOrMinus 0.06)
        lo[2] shouldBe (0.0 plusOrMinus 0.06); hi[2] shouldBe (48.0 plusOrMinus 0.06)
    }

    "control: a triangle index past the vertex count is rejected" {
        val bad = """<?xml version="1.0" encoding="utf-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
             <resources><object id="1" type="model"><mesh>
              <vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices>
              <triangles><triangle v1="0" v2="1" v3="3"/></triangles>
             </mesh></object></resources>
             <build><item objectid="1"/></build>
            </model>""".trimIndent()
        val tmp = Files.createTempFile("bad", ".3mf")
        try {
            Files.write(tmp, zip3mf(bad))
            shouldThrow<IllegalArgumentException> { ThreeMfReader.read(tmp) }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    "control: a build item pointing at a missing object is rejected" {
        val bad = """<?xml version="1.0" encoding="utf-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
             <resources/><build><item objectid="7"/></build></model>""".trimIndent()
        val tmp = Files.createTempFile("bad2", ".3mf")
        try {
            Files.write(tmp, zip3mf(bad))
            shouldThrow<IllegalStateException> { ThreeMfReader.read(tmp) }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    // the layout QIDIStudio writes: settings are <metadata key value> on <object> and <part>; <part id> is the mesh
    // object's resource id
    "project settings per object and per part, part subtype, one item per instance" {
        val tet = """<mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/><vertex x="0" y="0" z="1"/></vertices>
            <triangles><triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/><triangle v1="1" v2="2" v3="3"/><triangle v1="0" v2="3" v3="2"/></triangles></mesh>"""
        val model = """<?xml version="1.0" encoding="utf-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
             <resources>
              <object id="1" type="model">$tet</object>
              <object id="2" type="model">$tet</object>
              <object id="4" type="model">$tet</object>
              <object id="3" type="model"><components><component objectid="2"/><component objectid="4" transform="1 0 0 0 1 0 0 0 1 5 0 0"/></components></object>
             </resources>
             <build><item objectid="1"/><item objectid="1" transform="1 0 0 0 1 0 0 0 1 20 0 0"/><item objectid="3" transform="1 0 0 0 1 0 0 0 1 40 0 0"/></build>
            </model>""".trimIndent()
        val settings = """<?xml version="1.0" encoding="UTF-8"?>
            <config>
              <object id="1"><metadata key="name" value="bracket"/><metadata key="wall_loops" value="4"/><metadata face_count="4"/>
                <part id="1" subtype="normal_part"><metadata key="name" value="bracket"/><metadata key="matrix" value="1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1"/><metadata key="source_file" value="bracket.stl"/></part></object>
              <object id="3"><metadata key="name" value="block"/><metadata key="enable_support" value="1"/><metadata key="extruder" value="2"/>
                <part id="2" subtype="normal_part"><metadata key="name" value="body"/></part>
                <part id="4" subtype="modifier_part"><metadata key="name" value="dense"/><metadata key="sparse_infill_density" value="50%"/></part></object>
              <plate><metadata key="plater_id" value="1"/><model_instance><metadata key="object_id" value="1"/></model_instance></plate>
            </config>""".trimIndent()
        val tmp = Files.createTempFile("settings", ".3mf")
        try {
            Files.write(tmp, zip3mf(model, settings))
            val m = ThreeMfReader.read(tmp)
            m.plates.map { it.id to it.objectIds } shouldBe listOf(1 to setOf(1))
            val parts = m.parts
            parts.map { it.itemObjectId } shouldBe listOf(1, 1, 3, 3)
            parts.map { "${it.item}:${it.name}:${it.subtype}" } shouldBe listOf("0:bracket:normal_part", "1:bracket:normal_part", "2:body:normal_part", "2:dense:modifier_part")
            parts[0].objectSettings shouldBe mapOf("wall_loops" to "4")
            parts[1].objectSettings shouldBe mapOf("wall_loops" to "4")
            parts[0].partSettings shouldBe emptyMap()       // name, matrix, source_* are not settings
            parts[2].objectSettings shouldBe mapOf("enable_support" to "1", "extruder" to "2")
            parts[3].partSettings shouldBe mapOf("sparse_infill_density" to "50%")
            parts[3].soup.bounds()[0] shouldBe 45.0         // component transform composed with the item's
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
})

private fun zip3mf(model: String, modelSettings: String? = null): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        z.putNextEntry(ZipEntry("3D/3dmodel.model")); z.write(model.toByteArray()); z.closeEntry()
        if (modelSettings != null) { z.putNextEntry(ZipEntry("Metadata/model_settings.config")); z.write(modelSettings.toByteArray()); z.closeEntry() }
    }
    return bos.toByteArray()
}


