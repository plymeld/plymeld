package plymeld.commands

import plymeld.core.IncrementalSlicer
import plymeld.core.PauseLock
import plymeld.core.Predict
import plymeld.core.decide.Plan
import plymeld.model.Setup
import plymeld.program.Progress
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld slice`'s work in two halves, so the window can show the file before it is checked: [write] slices the plan to
 * the file (the objects' slicers, the prediction, the machine's header and pictures), [check] reads the file back as the
 * printer would (against the config and the model). The command runs both in a row and refuses the file on problems; the
 * window runs [write], loads the toolpaths, then [check] in the background, both with a [Progress] it draws and can cancel.
 *
 * A session kept across runs keeps each object's slicer (its cached stages: incremental re-slicing) while the plate is the same: the
 * same meshes, placed the same, with the same technique plan and pauses. Then a run after a change re-runs only the stages
 * whose settings changed (a speed: the g-code alone). A changed mesh, pose, or technique plan starts the slicer afresh.
 */
class SliceSession {
    private class Kept(val signature: String, val inc: IncrementalSlicer?, val job: plymeld.core.Job?)
    private var kept: Kept? = null

    /** What [write] produced, for [check]. */
    class Written(val pr: Product, val plan: Plan, val setup: Setup, val output: Path, val text: String, val partTools: List<Int>, val prediction: Predict.Prediction,
                  /** Whether the objects' slicers were the session's from before (their unchanged stages skipped). */
                  val reused: Boolean)

    private fun signature(plan: Plan, pauses: List<PauseLock>): String = plan.layout.placed.indices.joinToString("|") { k ->
        val pl = plan.layout.placed[k]; val t = plan.layout.techniques[k]
        val mesh = pl.parts.joinToString("+") { "${it.count}:${it.coords.contentHashCode()}" }
        val bands = t.bands.joinToString(",") { "${it.lo}-${it.hi}:${it.short}" }
        val support = t.support?.spots?.joinToString(",") { s -> "${s.id}:${s.locked}:${s.order}:${s.region.x0},${s.region.x1},${s.region.y0},${s.region.y1},${s.region.fromMm},${s.region.toMm}" } ?: "-"
        val islands = t.islands.joinToString(",") { "${it.key}:${it.seedXMm},${it.seedYMm}:${it.startMm}:${it.layerMm}:${it.capId}" }
        "${pl.name}@${pl.cx},${pl.cy}:$mesh;bands=$bands;h=${t.layerHeightMm};cd=${t.curvedDepthAuto};sup=$support;ins=${t.inserts.size};isl=$islands"
    } + "|order=${plan.layout.order}|pauses=" + pauses.joinToString(",") { it.toString() }

    /**
     * The objects that stand on a small footprint with no brim, one line each, said as a note (nothing is refused or
     * changed): its first layer's area under plan.mechanism.adhesion.small_footprint.area, or the object taller than
     * small_footprint.height_ratio times the narrower side of the box round its first layer. The first layer is the placed
     * parts cut half a first layer up; of a section left open (model.object.<key>.section), what lies inside its box, and
     * its height the box's top.
     */
    private fun smallFootprints(pr: Product, plan: plymeld.core.decide.Plan, setup: plymeld.model.Setup): List<String> =
        plan.layout.placed.withIndex().mapNotNull { (j, pl) ->
            val o = pr.plate[j].first
            val s = plymeld.model.SliceSettings.of(setup, plymeld.model.Context(j))
            if (s.brimType != "no_brim") return@mapNotNull null
            val bs = pl.parts.map { it.bounds() }
            val z0 = bs.minOf { it[2] }; val x0 = bs.minOf { it[0] }; val y0 = bs.minOf { it[1] }
            val open = plan.layout.techniques[j].regions.firstOrNull { it.section }?.region
            val rect = open?.let { g -> listOf(plymeld.geom.Polygon(longArrayOf(Math.round((x0 + g.x0) * 1000), Math.round((y0 + g.y0) * 1000), Math.round((x0 + g.x1) * 1000), Math.round((y0 + g.y0) * 1000),
                Math.round((x0 + g.x1) * 1000), Math.round((y0 + g.y1) * 1000), Math.round((x0 + g.x0) * 1000), Math.round((y0 + g.y1) * 1000)))) }
            val loops = pl.parts.flatMap { p -> plymeld.mesh.Slicer.slice(p, doubleArrayOf(z0 + s.firstLayerHeightUm / 2000.0))[0].loops
                .let { if (rect == null || it.isEmpty()) it else plymeld.geom.Booleans.intersection(it, rect) } }
            if (loops.isEmpty()) return@mapNotNull null
            val area = Math.abs(loops.sumOf { it.signedAreaMm2() })
            var lx = Long.MAX_VALUE; var hx = Long.MIN_VALUE; var ly = Long.MAX_VALUE; var hy = Long.MIN_VALUE
            for (p in loops) for (k in 0 until p.size) { lx = minOf(lx, p.x(k)); hx = maxOf(hx, p.x(k)); ly = minOf(ly, p.y(k)); hy = maxOf(hy, p.y(k)) }
            val narrow = minOf(hx - lx, hy - ly) / 1000.0
            val height = open?.toMm ?: (bs.maxOf { it[5] } - z0)
            fun f(v: Double) = plymeld.core.decide.Control.fmt(v)
            val why = listOfNotNull(
                if (area < s.smallFootprintAreaMm2) "its first layer is ${f(area)} mm², under plan.mechanism.adhesion.small_footprint.area (${f(s.smallFootprintAreaMm2)} mm²)" else null,
                if (narrow > 0 && height > s.smallFootprintHeightRatio * narrow) "it is ${f(height)} mm tall on a first layer ${f(narrow)} mm across its narrower side, over plan.mechanism.adhesion.small_footprint.height_ratio (${f(s.smallFootprintHeightRatio)})" else null)
            if (why.isEmpty()) null
            else "'${o.key}' stands on a small footprint with no brim: ${why.joinToString("; and ")}. A brim (plan.mechanism.adhesion.brim.enabled) may help it hold the bed"
        }

    /** Slices [pr]'s plan to [output]: the decisions echoed as `slice` prints them, the file written with its header. */
    fun write(pr: Product, output: Path, echo: (String) -> Unit, progress: Progress = Progress.NONE,
              /** A second writer every byte of the g-code also goes to, as it is written (the window's stage), closed with the file. */
              tap: java.io.Writer? = null): Written {
        pr.checkPlan()
        val plan = pr.decide()
        plan.decisions.forEach { echo("decided ${it.step}: ${if (it.overridden) "(locked) " else ""}${it.why}") }
        pr.firstLayerLines().forEach { (step, why) -> echo("decided $step: $why") }
        echo(plan.timeLine())
        plan.warnings.forEach { echo("warning: $it") }
        output.parent?.let { Files.createDirectories(it) }
        val setup = pr.result.setup
        smallFootprints(pr, plan, setup).forEach { echo("note: $it") }
        // nozzle temperatures against each filament's range and each tool's limit: warned about, not refused
        plymeld.core.check.TemperatureLimits.warnings(setup).forEach { echo("warning: $it") }
        // the pauses asked for, and one above each planned insert's top: the insert is placed before the layer over it
        val pauses = pr.pauses() + plan.layout.techniques.flatMap { t -> t.inserts.map { PauseLock.Above(it.topMm) } }.distinct()
        val t0 = System.nanoTime()
        val sig = signature(plan, pauses)
        val reused = kept?.signature == sig
        progress.check()
        // the seam measure of the layers written (SeamScar), echoed with the prediction
        var seamScar: plymeld.core.SeamScar.Result? = null
        var strings: plymeld.gcode.StringRisk.Result? = null
        if (pr.plate.size > 1) {
            val job = kept?.takeIf { reused }?.job?.also { it.update(setup) } ?: plymeld.core.Job(plan.layout, setup, PlymeldVersion.value)
            kept = Kept(sig, null, job)
            job.progress = progress
            job.pauses = pauses
            sink(output, tap).use { w -> job.write(w) }
            job.pausesLanded.forEach { echo("pause $it") }
            job.swapsLanded.forEach { echo("swap $it") }
            seamScar = job.seamScar(); strings = job.strings
            job.retractionDecisions.forEach { echo("decided ${it.step}: ${if (it.overridden) "(locked) " else ""}${it.why}") }
        } else {
            val (o, input) = pr.plate.single()
            val soups = plan.parts
            val inc = kept?.takeIf { reused }?.inc ?: IncrementalSlicer(soups, PlymeldVersion.value, o.key, technique = plan.layout.techniques.single())
            kept = Kept(sig, inc, null)
            inc.progress = progress
            inc.pauses = pauses
            val zBase = soups.minOf { it.bounds()[2] }
            inc.partRangesMm = input.partNames.zip(soups).associate { (n, soup) -> soup.bounds().let { b -> n to doubleArrayOf(b[2] - zBase, b[5] - zBase) } }
            sink(output, tap).use { w -> inc.slice(setup, w) }
            inc.pausesLanded.forEach { echo("pause $it") }
            inc.swapsLanded.forEach { echo("swap $it") }
            seamScar = inc.seamScar(); strings = inc.lastStrings
            inc.retractionDecision?.let { echo("decided ${it.step}: ${if (it.overridden) "(locked) " else ""}${it.why}") }
        }
        echo("${pr.plate.size} object(s): ${(System.nanoTime() - t0) / 1_000_000} ms -> $output" + (if (reused) " (the slicer kept from the last run: unchanged stages skipped)" else ""))
        progress.check()
        progress.at("prediction", 0, 0)
        val changeS = (plymeld.model.Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val s = plymeld.model.SliceSettings.of(setup)
        val prediction = Predict.gcode(Files.readString(output), s.machineDescription, changeS, Predict.limits(s)).withSeamScar(seamScar).withStrings(strings)
        // which tool prints each part: the config's loadout, read on its own (not the setup the slice was made from)
        val partTools = pr.partTools(plan)
        // the machine's preview pictures (hardware.machine.gcode.pictures) and its own file header (the Creator 5's screen), from the
        // finished file and its prediction
        val pictured = withPictures(Files.readString(output), plan.parts, partTools, setup, s, echo)
        // what the file was made from (version, models, config, plan), at its end, for anyone reproducing it (SourceBlock)
        val text = SourceBlock.append(withMachineHeader(pictured, pr.config, setup, s, prediction), PlymeldVersion.value, pr.usedStack, pr.modelPaths, pr.planFile(plan), pr.config.machine, pr.usedKeys)
        Files.writeString(output, text)
        echo("predicted: $prediction")
        // where the time goes, by feature (a gyroid's infill traced finer than the machine runs it showed only in the total)
        prediction.byFeature()?.let { echo("predicted by feature: $it") }
        echo(seamScar?.let { "seams: ${it.words()} (seam_scar)" } ?: "seams: not measured (a multi-filament object: its outer walls are laid per filament)")
        echo(strings?.let { "${it.words()} (string_risk, restart_starved; the restart lag hardware.filament.retract.restart_lag is a placeholder)" } ?: "strings: not measured (an exact program)")
        return Written(pr, plan, setup, output, text, partTools, prediction, reused)
    }

    /**
     * Reads the file back as the printer would: against the config (machine, retraction, travels, tools, header, start and
     * end) and against the model (nothing printed outside it, its outline walled). The problems, empty when it passed; the
     * check's line echoed either way (the command refuses the file on any).
     */
    class Checked(val problems: List<String>, /** The refusal as `slice` prints it (its message), or null when the file passed. */ val refusal: String?)

    fun check(w: Written, echo: (String) -> Unit, progress: Progress = Progress.NONE): Checked {
        val (pr, plan, setup, output, text, partTools) = w
        val t1 = System.nanoTime()
        progress.at("check: reading the model", 0, 0)
        val zBase = plan.parts.minOf { it.bounds()[2] }
        // a plate of several objects: each object's layers read against that object alone (PlateCheck), its curved band in
        // its own warp: one at a time, each object's run of layers; together, each object's lines by the file's object
        // markers (its own heights, merged with the others' by height). A lone object: the whole file against it, its curved
        // band re-warped.
        val several = plan.layout.placed.size > 1
        val sequential = several && plan.layout.order == plymeld.core.decide.ObjectOrder.ONE_AT_A_TIME
        val lifted = plan.layout.placed.indices.any { curvedSpec(plan, setup, it)?.follows == plymeld.core.check.CurvedCheck.Follows.LIFT }
        // Z anti-aliasing: lines below their flat layer only on the model's own top (the rule from the model and the config)
        val zaa = plymeld.core.check.Expectations.forZaa(setup, plan.parts, zBase, lifted)
        // a section left open: the box object j's lines are laid in, in the file's frame (its sides, and its top's height)
        fun cutOf(j: Int): DoubleArray? = plan.layout.techniques[j].regions.firstOrNull { it.section }?.let { r ->
            val pl = plan.layout.placed[j]; val g = r.region
            val bx = pl.parts.minOf { it.bounds()[0] }; val by = pl.parts.minOf { it.bounds()[1] }
            doubleArrayOf(bx + g.x0, bx + g.x1, by + g.y0, by + g.y1, pl.parts.minOf { it.bounds()[2] } + g.toMm) }
        // ... and its parts cut to that box, each cut face closed (SectionCut): inside the box their cross-sections are the
        // whole model's, and the check reads a mesh the size of what prints (six copies of a 3.9-million-triangle model, read
        // whole, ran out of memory). A part with nothing in the box is left out, its tool with it. A model the cut refuses
        // (not closed where the box cuts it) is read whole.
        fun cutParts(j: Int, parts: List<plymeld.mesh.TriangleSoup>, tools: List<Int>): Pair<List<plymeld.mesh.TriangleSoup>, List<Int>> {
            val c = cutOf(j) ?: return parts to tools
            val z0 = parts.minOf { it.bounds()[2] }
            val cut = try { parts.map { plymeld.mesh.SectionCut.cut(it, doubleArrayOf(c[0], c[1], c[2], c[3], z0 - 1.0, c[4])) } }
                catch (x: IllegalArgumentException) { return parts to tools }
            val keep = cut.indices.filter { cut[it].count > 0 }
            return if (keep.isEmpty()) parts to tools else keep.map { cut[it] } to keep.map { tools[it] }
        }
        val plate = if (!several) null else {
            var at = 0
            val objs = plan.layout.placed.withIndex().map { (j, pl) ->
                val tools = partTools.subList(at, at + pl.parts.size).also { at += pl.parts.size }
                val (ps, ts) = if (curvedSpec(plan, setup, j) == null) cutParts(j, pl.parts, tools) else pl.parts to tools
                plymeld.core.check.PlateCheck.Obj(pl.name, ps, ts, curvedSpec(plan, setup, j), cutOf(j))
            }
            if (sequential) plymeld.core.check.PlateCheck.oneAtATime(text, objs, zBase, Path.of("$output.check"), zaa)
            else plymeld.core.check.PlateCheck.together(text, objs, zBase, Path.of("$output.check"), zaa)
        }
        val curved = if (several) null else curvedRead(text, plan, setup, zBase)
        val checkText = plate?.text ?: curved?.text ?: text
        val lone = if (!several && curved == null) cutParts(0, plan.parts, partTools) else null
        val checkParts = plate?.parts ?: curved?.parts ?: lone?.first ?: plan.parts
        val checkTools = lone?.second ?: partTools
        // several lifted bridges in a lone object: each frame (what stands on the bed, each bridge) read against its own parts
        val frames = curved?.frames
        val frameSurfaces = frames?.map { plymeld.core.check.ModelCheck.Surface(it.parts, zBase, it.text, zaaMinMm = if (it.flat) zaa?.minMm else null, clip = it.clip) }
        val surface = if (frames == null) plymeld.core.check.ModelCheck.Surface(checkParts, zBase, checkText, zaaMinMm = zaa?.minMm, cut = if (several) null else cutOf(0)) else null
        val onSurface: (Double, Double, Double, Double) -> Boolean = when {
            plate != null -> plymeld.core.check.ModelCheck.Surfaces(plate.surfaces)::at
            frameSurfaces != null -> plymeld.core.check.ModelCheck.Surfaces(frameSurfaces)::at
            else -> surface!!::at
        }
        val tCut = (System.nanoTime() - t1) / 1_000_000
        progress.check()
        progress.at("check: the g-code", 0, 0)
        val allowedTools = partTools.toSet() + plymeld.config.LoadoutTools.supportTools(pr.config, setup)
        val gProblems = plymeld.core.check.GcodeCheck.check(checkText, plymeld.core.check.Expectations.forSetup(setup, onSurface, partTools.toSet(), allowedTools, lifted, zaa))
        val tG = (System.nanoTime() - t1) / 1_000_000 - tCut
        progress.check()
        // the plan's support spots must have support printed in them (SupportCheck): x and y from the placed object's corner
        val spotProblems = plan.layout.placed.indices.flatMap { j ->
            val sp = plan.layout.techniques[j].support ?: return@flatMap emptyList()
            val pl = plan.layout.placed[j]
            val bx = pl.parts.minOf { it.bounds()[0] }; val by = pl.parts.minOf { it.bounds()[1] }
            plymeld.core.check.SupportCheck.check(text, sp.spots.map { s -> plymeld.core.check.SupportCheck.Spot(s.id, bx + s.region.x0, bx + s.region.x1, by + s.region.y0, by + s.region.y1) })
                .map { "'${pl.name}': $it" }
        }
        val problems = spotProblems + (plate?.problems ?: emptyList()) + (curved?.problems ?: emptyList()) + gProblems +
            (if (plate != null) emptyList()
            else if (frames != null) frames.flatMapIndexed { i, fr ->
                plymeld.core.check.ModelCheck.check(fr.text, fr.parts, zBase, imagesDir = Path.of("$output.check", fr.name.replace(' ', '_')).also { Files.createDirectories(it) },
                    surface = frameSurfaces!![i], partTools = fr.partIndex.map { partTools[it] }, zaa = if (fr.flat) zaa else null, progress = progress).map { "${fr.name}: $it" } }
            else plymeld.core.check.ModelCheck.check(checkText, checkParts, zBase, imagesDir = Path.of("$output.check"), surface = surface, partTools = checkTools, zaa = zaa, progress = progress))
        plate?.let { echo("check: ${plan.layout.placed.size} objects, ${if (sequential) "one at a time: each object's run of layers" else "together: each object's lines, by the file's object markers,"} read against that object alone") }
        (plate?.curvedLayers ?: curved?.curvedLayers)?.takeIf { it > 0 }?.let { echo("check: $it curved layer(s) read in warped space (the check's own warp, from the model)") }
        val took = (System.nanoTime() - t1) / 1_000_000
        val times = "$took ms: model cut $tCut, g-code check $tG, model check ${took - tCut - tG}"
        if (problems.isEmpty()) { echo("check: the g-code reads back as the config and the model say ($times)"); return Checked(problems, null) }
        return Checked(problems, "check: ${problems.size} problem(s) in $output ($times); do not print it before they are understood:\n" + problems.joinToString("\n") { "  $it" })
    }

    /** The file's writer, with [tap] written to as well when given. */
    private fun sink(output: Path, tap: java.io.Writer?): java.io.Writer {
        val file = Files.newBufferedWriter(output)
        return if (tap == null) file else object : java.io.Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) { file.write(cbuf, off, len); tap.write(cbuf, off, len) }
            override fun flush() { file.flush(); tap.flush() }
            override fun close() { file.close(); tap.close() }
        }
    }

    private operator fun Written.component1() = pr
    private operator fun Written.component2() = plan
    private operator fun Written.component3() = setup
    private operator fun Written.component4() = output
    private operator fun Written.component5() = text
    private operator fun Written.component6() = partTools
}
