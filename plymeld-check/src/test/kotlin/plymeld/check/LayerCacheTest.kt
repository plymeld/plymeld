package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import plymeld.core.IncrementalSlicer
import plymeld.core.LayerCache
import plymeld.core.PauseLock
import plymeld.mesh.StlReader
import java.io.StringWriter

/**
 * The per-layer cache (LayerCache, incremental re-slicing at the layer): a new slicer on the same inputs comes back from the cache, byte for
 * byte what a cold slice gives; a change that moves only the upper layers (a pause's landing height) reuses the layers
 * below it and still matches a cold slice; a change that moves every layer matches too. The control: the cache was used.
 */
class LayerCacheTest : StringSpec({
    val soup by lazy { StlReader.read(testModelsDir().resolve("holeblock.stl")) }
    /** The test values, and changes over them (config path to value). */
    val base = emptyList<Pair<String, Any>>()

    fun slice(cfg: List<Pair<String, Any>>, pauses: List<PauseLock> = emptyList()): String =
        StringWriter().also { IncrementalSlicer(soup, "test", "holeblock").apply { this.pauses = pauses }.slice(testSetup(*cfg.toTypedArray()), it) }.toString()
    fun cold(cfg: List<Pair<String, Any>>, pauses: List<PauseLock> = emptyList()): String { LayerCache.clear(); return slice(cfg, pauses) }

    "a second slicer on the same inputs is served from the cache and writes the same file" {
        val a = cold(base)
        val hitsBefore = LayerCache.hits
        val b = slice(base)
        (b == a) shouldBe true
        (LayerCache.hits > hitsBefore) shouldBe true   // control: the cache was used, not just consistent
    }

    "a pause that lands the layers above it reuses the layers below and matches a cold slice" {
        cold(base)
        val pauses = listOf(PauseLock.AtZ(2.0))   // the hole block is 4 mm tall
        val before = LayerCache.hits
        val warm = slice(base, pauses)
        val hits = LayerCache.hits - before
        warm shouldBe cold(base, pauses)
        (hits > 0) shouldBe true
        (warm == cold(base)) shouldBe false   // control: the pause changed the file
    }

    "a layer height change moves every layer: nothing stale is served, the file matches a cold slice" {
        cold(base)
        val changed = listOf("plan.technique.flat.layer.height" to "0.16 mm")
        val warm = slice(changed)
        warm shouldBe cold(changed)
    }

    "a wall count change matches a cold slice" {
        cold(base)
        val changed = listOf("plan.layer.walls.thickness" to "1.32 mm")
        slice(changed) shouldBe cold(changed)
    }
})
