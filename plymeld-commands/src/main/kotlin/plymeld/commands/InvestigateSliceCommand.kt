package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import plymeld.model.withObjects
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.OrcaImport
import plymeld.model.SliceSettings
import plymeld.core.IncrementalSlicer
import plymeld.core.decide.Plan
import java.nio.file.Files

/**
 * `plymeld investigate slice MODEL -o OUT.gcode --settings SRC` — INVESTIGATION ONLY: slices with another slicer's settings, to compare with that slicer.
 *
 * MODEL: .stl or .3mf, one or more (a plate). A 3MF's parts keep their names, filaments, and
 *        own settings. plymeld decides how each object sits and where (see `plymeld plan`) unless
 *        --pose / --placement say otherwise.
 * SRC:   settings imported from another slicer: a QIDIStudio/Orca project .3mf (its resolved
 *        project_settings.config), a g-code file with a settings block, or a
 *        machine.json,process.json pair (comma-separated, later wins). --set overrides any setting.
 */
class InvestigateSliceCommand : CliktCommand(name = "slice") {
    private val models by argument(help = "STL or 3MF, one or more (a plate of objects)").path(mustExist = true, canBeDir = false).multiple(required = true)
    private val copies by option("--copies", help = "print each model this many times (a plate of copies; default: 1)").int().default(1)
    private val output by option("-o", "--output", metavar = "FILE", help = "g-code file to write").path().required()
    private val settings by option("--settings", help = InvestigateCommand.SETTINGS_HELP).required()
    private val repeat by option("--repeat", help = "benchmark: slice N times from scratch in this process (default: 1)").int().default(1)
    private val pose by option("--pose", help = "derive (default): choose how the object sits; as-given: keep the model's own orientation").default("derive")
    private val technique by option("--technique", help = "derive (default: chosen with the pose), one (flat layers of one height), or follow (flat layers following the surfaces)").default("derive")
    private val placement by option("--placement", help = "center: bottom on the bed, footprint centered; as-given: keep the model's own position (default: as-given with --pose as-given, else center)")
    private val plate by option("--plate", help = "which plate of a 3MF project to slice (needed when several have objects)").int()
    private val watch by option("--watch", help = "keep running: re-slice when a settings file or the model changes, re-running only affected stages").flag()
    private val sets by option("--set", help = "override an intent setting for this slice: id=value, repeatable (e.g. --set plan.mechanism.purge.tower.layout=banded); a project's per-object overrides still win").multiple()
    private val pauseAtLayer by option("--pause-at-layer", help = "pause before layer N prints (the first layer is 1); repeatable").int().multiple()
    private val pauseAtZ by option("--pause-at-z", help = "pause before the first layer whose top is at or above Z mm; repeatable").double().multiple()
    private val pauseBefore by option("--pause-before", help = "pause before the first layer an object or part (by name) prints on; repeatable").multiple()
    private val pauseAfter by option("--pause-after", help = "pause after the last layer an object or part (by name) prints on; repeatable").multiple()

    /** The pauses asked for: the model project's own (stored pauses), then the command line's. */
    private fun pauseLocks(): List<plymeld.core.PauseLock> {
        val (zs, notes) = models.map { projectPauses(it, plate) }.fold(emptyList<Double>() to emptyList<String>()) { a, b -> (a.first + b.first) to (a.second + b.second) }
        notes.forEach { echo(it) }
        return zs.map { plymeld.core.PauseLock.AtZ(it) } + pauseAtLayer.map { plymeld.core.PauseLock.AtLayer(it) } +
            pauseAtZ.map { plymeld.core.PauseLock.AtZ(it) } + pauseBefore.map { plymeld.core.PauseLock.Before(it) } + pauseAfter.map { plymeld.core.PauseLock.After(it) }
    }

    /** Applies --set to every intent of [setup] (plymeld ids and values; the setting's kind decides the type). */
    private fun applySets(setup: plymeld.model.Setup): plymeld.model.Setup = applySetOverrides(setup, sets)

    override fun help(context: com.github.ajalt.clikt.core.Context) = "INVESTIGATION ONLY: slice with the settings in a project or g-code file (--settings), to compare plymeld's result with that file. Klipper flavor."

    /**
     * Step 8 on the file just written: predicted time and filament, and the layers that still
     * print faster than the material's minimum layer time (the slow-down stops at its minimum
     * speed, so small layers can stay short; nothing else acts on it yet).
     */
    private fun predicted(s: SliceSettings, setup: plymeld.model.Setup) {
        val changeS = (plymeld.model.Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val p = plymeld.core.Predict.gcode(Files.readString(output), s.machineDescription, changeS)
        echo("predicted: $p")
        val text = Files.readString(output)
        val changes = plymeld.core.Predict.toolChanges(text)
        if (changes > 0) {
            val tower = plymeld.core.Predict.filamentByFeature(text)["Prime tower"] ?: 0.0
            echo("multi-color: %d tool changes; purge tower %.0f mm of filament (%.0f%% of the print)".format(java.util.Locale.ROOT, changes, tower, 100 * tower / p.filamentMm))
        }
        if (s.slowDownForCooling && s.slowDownLayerTimeS > 0) p.shortLayers(s.slowDownLayerTimeS)?.let {
            echo("cooling: $it — less cooling time than the material asks for; the slow-down does not go below %.0f mm/s and nothing else acts on it yet".format(java.util.Locale.ROOT, s.slowDownMinSpeed))
        }
    }

    override fun run() {
        val files = if (settings.endsWith(".3mf") || settings.endsWith(".gcode")) listOf(java.nio.file.Path.of(settings)) else settings.split(',').map { java.nio.file.Path.of(it.trim()) }
        fun loadCfg() = loadSettings(settings)
        var cfg = loadCfg()
        var setup = applySets(OrcaImport.setup(cfg, java.nio.file.Path.of(settings.split(',').last().trim()).fileName.toString()))
        setup.importReport?.notes?.forEach { echo("settings: $it") }
        val s = SliceSettings.of(setup)
        if (s.gcodeFlavor != "klipper") throw UsageError("only gcode_flavor=klipper is supported so far (got '${s.gcodeFlavor}')")
        val inputs = models.flatMap { m -> List(copies) { loadModel(m, plate) }.flatten() }
        inputs.mapNotNull { it.note }.distinct().forEach { echo(it) }
        val model = models.first()
        val reported = HashSet<String>()
        setup = withInputs(setup, cfg, inputs) { if (reported.add(it)) echo(it) }
        // decisions (today: a pose per object, then the build order, derived unless locked)
        val plan = Plan.makeFor(inputs.map { it.objectName to it.parts }, setup, overrides(pose, placement, technique))
        plan.decisions.forEach { echo("decided ${it.step}: ${if (it.overridden) "(locked) " else ""}${it.why}") }
        echo(plan.timeLine())
        output.parent?.let { Files.createDirectories(it) }
        val pauses = pauseLocks()
        if (inputs.size > 1) {
            // a plate: the build order puts the objects together
            if (repeat > 1) throw UsageError("--repeat benchmarks one object; this is a plate of ${inputs.size}")
            var job = plymeld.core.Job(plan.layout, setup, PlymeldVersion.value)
            fun oncePlate() {
                val t0 = System.nanoTime()
                job.pauses = pauses
                Files.newBufferedWriter(output).use { w -> job.write(w) }
                job.pausesLanded.forEach { echo("pause $it") }
                val stages = job.stageMs.flatMap { it.keys }.groupingBy { it }.eachCount()
                echo("${inputs.size} objects: ${(System.nanoTime() - t0) / 1_000_000} ms, stages run: " +
                    stages.entries.joinToString { "${it.key} x${it.value}" }.ifEmpty { "none (only the writer)" } + " -> $output")
                val findings = plymeld.check.PrePrintCheck.check(output, cfg)
                if (findings.isEmpty()) echo("pre-print check: PASS") else findings.forEach { echo("pre-print check: $it", err = true) }
                predicted(SliceSettings.of(setup), setup)
            }
            oncePlate()
            if (!watch) return
            watchLoop(files) {
                cfg = loadCfg()
                setup = withInputs(applySets(OrcaImport.setup(cfg, files.last().fileName.toString())), cfg, inputs) {}
                // the decisions may move objects (e.g. a wider brim changes the spacing): same layout
                // keeps every object's cached stages, a new layout starts a new job
                val again = Plan.makeFor(inputs.map { it.objectName to it.parts }, setup, overrides(pose, placement, technique))
                if (sameLayout(again.layout, job.layout)) job.update(setup)
                else { echo("layout changed: " + plymeld.core.decide.Arrange.describe(again.layout.placed)); job = plymeld.core.Job(again.layout, setup, PlymeldVersion.value) }
                oncePlate()
            }
        }
        val objectName = inputs.single().objectName
        val soups = plan.parts
        val technique = plan.layout.techniques.single()
        val inc = IncrementalSlicer(soups, PlymeldVersion.value, objectName, technique = technique)
        inc.pauses = pauses
        // part names resolve by each part's Z range in the placed model
        // (from the object's bottom, as layer tops are measured)
        val zBase = soups.minOf { it.bounds()[2] }
        inc.partRangesMm = inputs.single().partNames.zip(soups).associate { (n, soup) -> soup.bounds().let { b -> n to doubleArrayOf(b[2] - zBase, b[5] - zBase) } }
        fun once() {
            val t0 = System.nanoTime()
            val ran = Files.newBufferedWriter(output).use { w -> inc.slice(setup, w) }
            inc.pausesLanded.forEach { echo("pause $it") }
            // a decision made when sliced (the in-island retraction): said here as `slice` says it
            inc.retractionDecision?.let { echo("decided ${it.step}: ${if (it.overridden) "(locked) " else ""}${it.why}") }
            echo("${model.fileName}: ${(System.nanoTime() - t0) / 1_000_000} ms, stages run: ${inc.stageMs.entries.joinToString { "${it.key} ${it.value} ms" }} -> $output")
            val findings = plymeld.check.PrePrintCheck.check(output, cfg)
            if (findings.isEmpty()) echo("pre-print check: PASS") else findings.forEach { echo("pre-print check: $it", err = true) }
            predicted(SliceSettings.of(setup), setup)
        }
        once()
        for (r in 2..repeat) {
            val fresh = IncrementalSlicer(soups, PlymeldVersion.value, objectName, technique = technique)
            val t0 = System.nanoTime()
            Files.newBufferedWriter(output).use { w -> fresh.slice(setup, w) }
            echo("repeat $r: ${(System.nanoTime() - t0) / 1_000_000} ms (${fresh.stageMs.entries.joinToString { "${it.key} ${it.value}" }})")
        }
        setup.importReport?.let { echo("settings: ${it.mapped.size} of ${it.mapped.size + it.unmapped.size} keys in ${it.source} mapped onto plymeld settings") }
        if (!watch) return
        watchLoop(files) { cfg = loadCfg(); setup = withInputs(applySets(OrcaImport.setup(cfg, files.last().fileName.toString())), cfg, inputs) {}; once() }
    }

    /** Runs [reslice] whenever one of the settings [files] changes, until Ctrl-C; a failed re-slice is reported and watching goes on. */
    private fun watchLoop(files: List<java.nio.file.Path>, reslice: () -> Unit) {
        echo("watching ${files.joinToString()} (Ctrl-C to stop)")
        var stamps = files.map { Files.getLastModifiedTime(it) }
        while (true) {
            Thread.sleep(300)
            val now = files.map { Files.getLastModifiedTime(it) }
            if (now == stamps) continue
            stamps = now
            try { reslice() } catch (e: Exception) { echo("re-slice failed: ${e.message}", err = true) }
        }
    }

    /** Same order, same objects in the same places (a new layout needs a new job). */
    private fun sameLayout(a: plymeld.core.decide.Layout, b: plymeld.core.decide.Layout): Boolean =
        a.order == b.order && a.objectIndex == b.objectIndex && a.placed.size == b.placed.size &&
            a.placed.zip(b.placed).all { (p, q) -> p.name == q.name && Math.abs(p.cx - q.cx) < 1e-6 && Math.abs(p.cy - q.cy) < 1e-6 && Math.abs(p.heightMm - q.heightMm) < 1e-6 }
}
