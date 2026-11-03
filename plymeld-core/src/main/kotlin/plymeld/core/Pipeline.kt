package plymeld.core

import plymeld.mesh.TriangleSoup
import plymeld.model.Setup
import java.io.Writer

/** Timings of one end-to-end slice, ms. */
data class SliceStats(val layers: Int, val sliceMs: Long, val planMs: Long, val writeMs: Long) {
    val totalMs get() = sliceMs + planMs + writeMs
}

/**
 * One-shot slice, mesh to g-code. A thin wrapper over [IncrementalSlicer] so there is exactly
 * one code path (a second path once silently skipped the exclude-object markers).
 */
object Pipeline {
    fun slice(soup: TriangleSoup, setup: Setup, out: Writer, version: String, objectName: String = "model"): SliceStats {
        val inc = IncrementalSlicer(soup, version, objectName)
        inc.slice(setup, out)
        val ms = inc.stageMs
        val layers = inc.layerCount
        return SliceStats(layers, ms["slice"] ?: 0, (ms["walls"] ?: 0) + (ms["regions"] ?: 0) + (ms["paths"] ?: 0), ms["gcode"] ?: 0)
    }
}
