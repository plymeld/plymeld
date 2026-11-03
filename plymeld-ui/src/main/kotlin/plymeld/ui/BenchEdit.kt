package plymeld.ui

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `plymeld-ui --bench-edit -cf ... -m ... [--height MM]`: the edit cycle timed through the window's own code path
 * (JobState, no Compose): open the job, slice it, lock the first object's flat layers at MM mm through the drawer's path,
 * and time until the new toolpaths are in the view. The figures are what the user waits for after "Lock", stage by
 * stage, so each optimization of the edit cycle is measured the same way. The config files are
 * put back as they were afterwards (the lock is written into the plan file).
 */
object BenchEdit {
    fun run(configFiles: List<String>, modelFiles: List<String>, machine: String?, platform: String?, mm: String) {
        // the edit writes the lock into the job's plan file; the benchmark leaves every config file as it found it
        // (it left a 0.16 mm lock in the dev job's plan once, and the next run of the window planned from it)
        val found = configFiles.map { Path.of(it) }.filter { Files.isRegularFile(it) }.associateWith { Files.readAllBytes(it) }
        try { Viewport3D(platform).use { vp ->
            val j = JobState(ViewerState(vp))
            j.viewer.resize(1200, 800)   // the stage's size (no Compose here): frames are drawn at it, so the upload is timed too
            fun timed(label: String, body: ((() -> Unit)) -> Unit): Double {
                val l = CountDownLatch(1); val t = System.nanoTime()
                body { l.countDown() }
                if (!l.await(20, TimeUnit.MINUTES)) error("$label: no answer in 20 min")
                return (System.nanoTime() - t) / 1e9
            }
            val out = Files.createTempFile("plymeld-bench-edit", ".gcode")
            val tOpen = timed("open") { done -> j.open(configFiles, modelFiles, machine, done) }
            val tSlice = timed("slice") { done -> j.slice(out, done) }
            j.viewer.awaitFrames()
            val layersBefore = j.viewer.paths?.layers ?: 0
            val obj = j.marks?.objectOf?.values?.firstOrNull() ?: error("no object in the plan's marks")
            val slicesBefore = j.console.count { it.startsWith("$ slice ") }
            // the edit: the plan file written, the job read again, and sliced again by itself where it went
            val tEditStart = System.nanoTime()
            val tEdit = timed("lock $mm mm") { done -> j.lockHeight(obj, mm, done) }
            val tShown = (j.shownNanos - tEditStart) / 1e9   // the toolpaths on screen, the check still running
            val tFirst = if (j.firstShownNanos > tEditStart) (j.firstShownNanos - tEditStart) / 1e9 else Double.NaN   // the first layers, while the file was written
            val auto = j.console.count { it.startsWith("$ slice ") } > slicesBefore
            val tReslice = if (auto) 0.0 else timed("slice again") { done -> j.slice(out, done) }
            j.viewer.awaitFrames()
            val layersAfter = j.viewer.paths?.layers ?: 0
            fun f(v: Double) = String.format(Locale.ROOT, "%6.2f s", v)
            println("bench-edit: ${modelFiles.joinToString { Path.of(it).fileName.toString() }}, flat layers locked at $mm mm on '$obj'")
            println("  open (read, plan as lock)      ${f(tOpen)}")
            println("  first slice, to the view        ${f(tSlice)}   $layersBefore layers")
            println("  edit + the job read again       ${f(tEdit)}" + (if (auto) "   (with the slice re-run by the edit itself, to the view)" else ""))
            println("  slice again, to the view        ${f(tReslice)}   $layersAfter layers" + (if (auto) " (no click needed)" else ""))
            println("  edit -> toolpaths on screen     ${f(if (auto) tShown else tEdit + tReslice)}" + (if (auto) "   (the check finished at ${f(tEdit)})" else ""))
            if (auto && !tFirst.isNaN()) println("  edit -> first layers on screen  ${f(tFirst)}   (streamed while the file was written)")
            println("  slice step: ${j.slice.lines.joinToString(" · ")}")
            Files.deleteIfExists(out)
        } } finally {
            for ((p, bytes) in found) if (!Files.readAllBytes(p).contentEquals(bytes)) Files.write(p, bytes)
        }
    }
}
