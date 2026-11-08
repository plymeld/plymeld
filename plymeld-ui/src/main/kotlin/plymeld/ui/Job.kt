package plymeld.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.toComposeImageBitmap
import plymeld.commands.Commands
import plymeld.commands.Product
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.UserDirs
import plymeld.core.PauseLock
import plymeld.core.decide.Decision
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.core.decide.TechniquePlan
import plymeld.mesh.TriangleSoup
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * The job behind the window's six steps: the model, the printer, what matters, the plan,
 * the slice and its check, and sending. Everything here comes from the same code the commands run: the
 * config stack and the model through [Product] as `plymeld plan` reads them; the slice through `plymeld slice` itself,
 * run in-process ([Commands.invoke]) with every line it prints kept in [console]. No decision is made here.
 */
class JobState(val viewer: ViewerState) {
    enum class Status { WAIT, NOW, BUSY, DONE, FAILED }
    /** One step as shown: its state, its lines (the first in bold), and a note under them (muted). */
    class StepView(val status: Status, val lines: List<String>, val note: String? = null)
    /**
     * A loaded filament: its config key, its color (0xRRGGBB), the tool holding it, its slot number (what a model's part
     * numbers name) and the tool's index (from 1; the g-code's T number is one less).
     */
    class Swatch(val filament: String, val rgb: Int, val tool: String, val slot: Int, val toolIndex: Int)

    var title by mutableStateOf("no job open"); private set
    var model by mutableStateOf(StepView(Status.NOW, listOf("Open a model, or drop one on the right."))); private set
    var printer by mutableStateOf(StepView(Status.WAIT, listOf("from the config"))); private set
    var matters by mutableStateOf(StepView(Status.WAIT, listOf("after a model is open"))); private set
    var plan by mutableStateOf(StepView(Status.WAIT, listOf("decided from the model, the printer, and what matters"))); private set
    var slice by mutableStateOf(StepView(Status.WAIT, listOf(SLICE_WAITS))); private set
    var send by mutableStateOf(StepView(Status.WAIT, listOf(SEND_WAITS))); private set
    var loadout by mutableStateOf(emptyList<Swatch>()); private set
    /**
     * The printing machine's key (hardware.machine.<key>): its name on the Send button and in the steps. The key, never the
     * model name: there may be more than one of a model.
     */
    var printerName by mutableStateOf("printer"); private set
    /** The plan's decisions in full (`plymeld plan`'s text), for the console. */
    var planText by mutableStateOf(""); private set
    /** The plan drawn on the model (the stage's Plan tab) and explained (the drawer): null until decided. */
    var marks by mutableStateOf<PlanMarks?>(null); private set

    /** What the Plan tab draws, in the plate's frame (mm): the height bands, the support spots, the pauses; and the decisions. */
    class PlanMarks(val bounds: DoubleArray, val bands: List<Band>, val spots: List<Box>, val pauses: List<Pair<Double, String>>, val decisions: List<Decision<*>>, val objects: List<String>,
                    /** The object each technique decision is for (by the decision's index), its band count, and its support spots' ids: what the drawer's actions edit. */
                    val objectOf: Map<Int, String> = emptyMap(), val bandsOf: Map<String, Int> = emptyMap(), val spotsOf: Map<String, List<String>> = emptyMap(),
                    /** The pauses at a layer, by its number (from 1): their height is known only once sliced. */
                    val layerPauses: List<Pair<Int, String>> = emptyList())

    /** The journal of edits on disk beside the plan file (`plymeld edit --journal`, `plymeld journal --undo/--redo`), as last read; what the bar's undo and redo stand on. */
    var journal by mutableStateOf<plymeld.commands.Journal?>(null); private set
    val canUndo: Boolean get() = journal?.canUndo == true
    val canRedo: Boolean get() = journal?.canRedo == true
    /** Where the job's journal is: beside its plan file, else beside the user's config file; null with neither. */
    val journalPath: Path? get() = (planFile ?: userConfig)?.let { plymeld.commands.Journal.beside(it) }
    private fun readJournal() { journalPath?.let { p -> Snapshot.withMutableSnapshot { journal = plymeld.commands.Journal.read(p) } } }

    /** What the printer itself said when asked (`printer status`): its lines, empty until asked or when it refused. */
    var printerLive by mutableStateOf(emptyList<String>()); private set
    /** The loadout comparison's rows (`printer loadout`), each with whether it disagrees. */
    var loadoutRows by mutableStateOf(emptyList<Pair<String, Boolean>>()); private set
    /** One frame from the printer's camera (`printer camera`), when it gave one. */
    var cameraFrame by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null); private set
    var askingPrinter by mutableStateOf(false); private set

    /** The command line of a command that takes the config but no models (`printer ...`). */
    private fun configArgs(vararg words: String): List<String> =
        words.toList() + configFiles.flatMap { listOf("-cf", it) } + (machine?.let { listOf("--machine", it) } ?: emptyList())

    /**
     * Step 2 live: `printer status`, `printer loadout` and `printer camera` on the machine, in-process, their lines kept
     * in the step (the state, the loadout against the config with disagreements marked, the frame). A refusal (no
     * connection in the config, the printer unreachable, no camera) is shown as the command prints it. Runs on a thread.
     */
    fun askPrinter(done: () -> Unit = {}) {
        Thread({
            try {
                Snapshot.withMutableSnapshot { askingPrinter = true; printerLive = emptyList(); loadoutRows = emptyList(); cameraFrame = null }
                fun run(vararg words: String): Pair<Int, List<String>> {
                    val a = configArgs(*words); sayLine(a)
                    val from = console.size; val status = Commands.invoke(a, ::say)
                    return status to console.drop(from)
                }
                val (st, statusLines) = run("printer", "status")
                Snapshot.withMutableSnapshot { printerLive = if (st == 0) statusLines.filter { it.startsWith("status:") || it.startsWith("job:") || it.startsWith("progress:") || it.startsWith("layer:") || it.startsWith("nozzles:") || it.startsWith("bed:") || it.startsWith("remaining:") }
                    else listOf(statusLines.lastOrNull { it.startsWith("plymeld: ") || it.startsWith("Error") }?.removePrefix("plymeld: ") ?: "the printer could not be asked") }
                if (st != 0) return@Thread
                val (lo, loadoutLines) = run("printer", "loadout")
                if (lo == 0) Snapshot.withMutableSnapshot { loadoutRows = loadoutLines.filter { " -> " in it }.map { line ->
                    val verdict = line.substringAfter(" -> ").substringBefore(':').trim()
                    line to (verdict !in setOf("agrees", "empty on both", "not reported")) } }
                val frame = sliceOut().resolveSibling("camera.jpg")
                val (cam, _) = run("printer", "camera", "-o", frame.toString())
                if (cam == 0 && Files.exists(frame)) Snapshot.withMutableSnapshot {
                    cameraFrame = org.jetbrains.skia.Image.makeFromEncoded(Files.readAllBytes(frame)).toComposeImageBitmap() }
            } catch (e: Exception) { say("plymeld: ${e.message}") } finally { Snapshot.withMutableSnapshot { askingPrinter = false }; done() }
        }, "plymeld-printer").apply { isDaemon = true }.start()
    }

    /** The config files, models, and machine the job is open with (what "Open another…", a dropped model and a command's form reuse). */
    val openConfigFiles: List<String> get() = configFiles
    val modelFilesOpen: List<String> get() = modelFiles
    val openMachine: String? get() = machine

    /**
     * Any command line, in-process, its lines into the console (a command's form in the drawer, a line typed in the
     * console). When it changed one of the job's files (`edit`, `journal --undo`, `plan -o` over the plan file) the job is
     * read again and the journal re-read, so the steps, the stage, and the history are not left showing the job before
     * Runs on a thread.
     */
    fun run(words: List<String>, done: () -> Unit = {}) {
        Thread({
            var changed = false
            try {
                sayLine(words)
                val before = jobFileTexts()
                val status = Commands.invoke(words, ::say); if (status != 0) say("(exit $status)")
                changed = jobFileTexts() != before
            }
            catch (e: Exception) { say("plymeld: ${e.message}") }
            finally {
                if (changed && modelFiles.isNotEmpty()) { say("(the job's files changed: read again)"); readJournal(); reopen(done) } else { readJournal(); done() }
            }
        }, "plymeld-run").apply { isDaemon = true }.start()
    }
    /** The texts of the job's config files (a file that cannot be read is ""): what [run] compares to see a change. */
    private fun jobFileTexts(): List<String> = configFiles.map { runCatching { Files.readString(Path.of(it)) }.getOrDefault("") }

    /**
     * A g-code file opened to look at (dropped, or opened by its .gcode name): read into the Layers tab as the viewer reads one, colored by tool when the job's loadout
     * names the tools. It is not this job's slice: it is not checked, nothing is sent from it, and the next slice replaces it.
     */
    var previewing by mutableStateOf<Path?>(null); private set
    fun previewGcode(file: Path, done: () -> Unit = {}) {
        Thread({
            try {
                say("previewing $file: shown in the Layers tab; not this job's slice, not checked, not sent")
                viewer.loadGcode(file)
                if (loadout.isNotEmpty()) colorByTool()
                viewer.showMeshAt(GHOST)
                Snapshot.withMutableSnapshot { previewing = file }
            } catch (e: Exception) { say("plymeld: $file could not be read: ${e.message}") } finally { done() }
        }, "plymeld-preview").apply { isDaemon = true }.start()
    }

    /** A user docs page opened online (a finder hit): the system's browser on its URL; the URL in the console either way. */
    fun openDoc(path: String) {
        val url = Finder.Docs.url(path)
        say("docs: $url")
        Thread({
            try {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                else say("no browser to open here: the address is above")
            } catch (e: Exception) { say("plymeld: the browser could not be opened (${e.message}); the address is above") }
        }, "plymeld-docs").apply { isDaemon = true }.start()
    }

    /** The lines typed in the console, oldest first (Up / Down step through them). */
    var typedLines by mutableStateOf(emptyList<String>()); private set
    /** A line typed in the console: split as a shell would (quotes group words; a leading `plymeld` is dropped), then [run]. */
    fun typed(line: String) {
        Snapshot.withMutableSnapshot { typedLines = (typedLines - line) + line }
        val words = splitWords(line).let { if (it.firstOrNull() == "plymeld") it.drop(1) else it }
        if (words.isEmpty()) return
        run(words)
    }

    /**
     * Opens a model alone: with the config files of the job open now, else of the last job this user opened (a per-viewer
     * convenience kept in `window-last-job.txt` in [UserDirs.state]), or with none, which the
     * Printer step then says. A plan file never comes along ([configsFor]): it is the plan of the job it was made for.
     */
    fun openModel(model: String, done: () -> Unit = {}) {
        val last = LastJob.read()
        val (files, notes) = configsFor(listOf(model), if (configFiles.isNotEmpty()) configFiles else last?.configFiles ?: emptyList())
        notes.forEach { say(it) }
        Snapshot.withMutableSnapshot { tries = emptyList(); slot = null }   // a try, and the one slot, are for the job they were made in
        open(files, listOf(model), machine ?: last?.machine, done)
    }

    /**
     * Every part printed from one slot of the loadout (`--slot N`, as `plan` and `slice` take it), or null for the slots the
     * model file and the config give: step 1 offers it when the model is refused for a filament the loadout lacks.
     */
    var slot by mutableStateOf<Int?>(null); private set
    /**
     * The slot the last refusal suggests printing every part from (its "--slot N"), when the job could not be read because a
     * part's filament is not loaded; null otherwise. Step 1's "Print every part from slot N".
     */
    var slotOffer by mutableStateOf<Int?>(null); private set

    /** The job opened again with every part on slot [n] (null: the model's own slots again); when it cannot be read so, as it was. */
    fun useSlot(n: Int?, done: () -> Unit = {}) {
        val before = slot
        say(if (n == null) "slots: as the model and the config give them" else "every part from slot $n")
        Snapshot.withMutableSnapshot { slot = n }
        open(configFiles, modelFiles, machine) {
            val err = openError
            if (err != null) { say("not changed: $err"); Snapshot.withMutableSnapshot { slot = before }; open(configFiles, modelFiles, machine, done) } else done()
        }
    }

    /**
     * A model added to the plate (dropped on the stage while a model is open, or "Add a model…"): the job read again
     * with it, and the plate arranged again by the planner, which lays the objects out in rows with the configured gap,
     * the whole arrangement centered on the bed (`Arrange.rows`; a placement kept as given stays as it is). When no
     * arrangement fits the bed the planner refuses ("objects do not fit on the bed"), the plate goes back to what it
     * was, and the console says why. The same file twice is refused: objects are keyed by their file, and copies of one
     * model are not supported yet.
     */
    fun addModel(model: String, done: () -> Unit = {}) {
        if (modelFiles.isEmpty()) { openModel(model, done); return }
        if (model in modelFiles) { say("not added: $model is already on the plate (copies of one model are not supported yet)"); done(); return }
        val before = modelFiles
        say("model added: $model")
        open(configFiles, before + model, machine) {
            val err = openError
            if (err != null) { say("not added, the plate is as it was: $err"); open(configFiles, before, machine, done) } else done()
        }
    }

    /** What the last open() failed with (the planner's refusal, a config problem), or null: addModel reads it to put the plate back. */
    @Volatile private var openError: String? = null

    /** A config file added to the job (dropped on the stage, or "Add a config file…"): the job read again with it. */
    fun addConfig(file: String, done: () -> Unit = {}) {
        if (file in configFiles) { say("not added: $file is already in the job"); done(); return }
        say("config file added: $file")
        open(withConfig(configFiles, file), modelFiles, machine, done)
    }

    /**
     * A model taken off the plate (step 1's ×): the job read again without it, the planner arranging what is left; the
     * last model off leaves the job waiting for one. When the rest cannot be read or planned, the plate goes back to what
     * it was and the console says why.
     */
    fun removeModel(model: String, done: () -> Unit = {}) {
        if (model !in modelFiles) { done(); return }
        val before = modelFiles
        say("model taken off: $model")
        open(configFiles, before - model, machine) {
            val err = openError
            if (err != null) { say("not taken off, the plate is as it was: $err"); open(configFiles, before, machine, done) } else done()
        }
    }

    /**
     * A config file taken out of the job (step 2's ×): the job read again without it; when the job cannot be read without
     * it (the machine it defined, a filament it named), it goes back in and the console says why.
     */
    fun removeConfig(file: String, done: () -> Unit = {}) {
        if (file !in configFiles) { done(); return }
        val before = configFiles
        say("config file taken out: $file")
        open(before - file, modelFiles, machine) {
            val err = openError
            if (err != null) { say("not taken out, the job is as it was: $err"); open(before, modelFiles, machine, done) } else done()
        }
    }

    /**
     * The machines the job's config files define (`hardware.machine.<key>`), in the files' order: step 2's "Another
     * printer" offers them. Read from the stack, so a config that cannot say which machine prints (several, none named)
     * still lists them.
     */
    var machines by mutableStateOf(emptyList<String>()); private set
    /** A machine as the choice shows it: its key, and its model's name where the config gives one. */
    fun machineLabel(key: String): String =
        stack?.effective?.get("hardware.machine.$key.model")?.value?.toString()?.let { "$key · ${modelName(it)}" } ?: key
    /** A model's name by its key (hardware.machine.<key>.model): the config's own model, else a shipped one, else the key as given. */
    private fun modelName(id: String): String = stack?.effective?.get("hardware.model.$id.name")?.value?.toString()
        ?: plymeld.config.Models.bundled.all.firstOrNull { it.id == id }?.name ?: id

    /**
     * The job opened again on machine [key] (step 2's "Another printer"): the same config files and models with
     * `--machine KEY`, as `plymeld plan` takes it. What the last printer said when asked is dropped. When the job cannot be
     * read or planned on [key] (the config's refusal, the planner's) it is opened again on the machine before, the console
     * saying why, and [done] gets false.
     */
    fun useMachine(key: String, done: (Boolean) -> Unit = {}) {
        val before = machine
        say("machine: $key")
        Snapshot.withMutableSnapshot { printerLive = emptyList(); loadoutRows = emptyList(); cameraFrame = null }
        open(configFiles, modelFiles, key) {
            val err = openError
            if (err != null) { say("not changed, the job is on ${before ?: "the machine the config names"} again: $err"); open(configFiles, modelFiles, before) { done(false) } } else done(true)
        }
    }

    /**
     * The last job's config files and machine, kept for opening a model alone next time: a plain text file,
     * `# comment` lines, `machine=<key>` and one config file path per line.
     */
    object LastJob {
        class Saved(val configFiles: List<String>, val machine: String?)
        val file: Path get() = UserDirs.current().state.resolve("window-last-job.txt")
        fun read(): Saved? = try {
            if (!Files.exists(file)) null else {
                val lines = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                Saved(lines.filter { !it.startsWith("machine=") }, lines.firstOrNull { it.startsWith("machine=") }?.removePrefix("machine=")?.takeIf { it.isNotEmpty() })
            }
        } catch (_: Exception) { null }   // an unreadable memory of a convenience is no memory, not a refusal
        fun write(configFiles: List<String>, machine: String?) = try {
            Files.createDirectories(file.parent)
            Files.writeString(file, "# the config files of the last job the window opened, reused when a model is opened alone; a convenience, nothing decided from it\n" +
                "machine=${machine ?: ""}\n" + configFiles.joinToString("") { "$it\n" })
        } catch (_: Exception) { }
    }

    /**
     * The jobs opened recently (the empty stage offers them),
     * newest first, at most [KEEP]: a per-viewer convenience in `window-recent.txt` in [UserDirs.state], one job a
     * line, its models, config files, and machine separated by tabs (`m=`, `c=`, `machine=`). Nothing is decided from it.
     */
    object RecentJobs {
        class Job(val models: List<String>, val configFiles: List<String>, val machine: String?) { val name: String get() = jobName(models) }
        const val KEEP = 8
        val file: Path get() = UserDirs.current().state.resolve("window-recent.txt")
        fun read(): List<Job> = try {
            if (!Files.exists(file)) emptyList() else Files.readAllLines(file).filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val f = line.split('\t')
                Job(f.filter { it.startsWith("m=") }.map { it.removePrefix("m=") }, f.filter { it.startsWith("c=") }.map { it.removePrefix("c=") }, f.firstOrNull { it.startsWith("machine=") }?.removePrefix("machine=")?.ifEmpty { null })
            }.filter { it.models.isNotEmpty() }
        } catch (_: Exception) { emptyList() }   // an unreadable memory of a convenience is no memory
        fun add(models: List<String>, configFiles: List<String>, machine: String?) = try {
            val line = (models.map { "m=$it" } + configFiles.map { "c=$it" } + "machine=${machine ?: ""}").joinToString("\t")
            val rest = read().filterNot { it.models == models }.map { j -> (j.models.map { "m=$it" } + j.configFiles.map { "c=$it" } + "machine=${j.machine ?: ""}").joinToString("\t") }
            Files.createDirectories(file.parent)
            Files.writeString(file, "# the jobs the window opened, newest first; a convenience, nothing decided from it\n" + (listOf(line) + rest).take(KEEP).joinToString("\n") + "\n")
        } catch (_: Exception) { }
    }
    var recent by mutableStateOf(RecentJobs.read()); private set
    /** The recent jobs forgotten (the window's settings): the list's file removed. */
    fun forgetRecent() { runCatching { Files.deleteIfExists(RecentJobs.file) }; Snapshot.withMutableSnapshot { recent = emptyList() } }

    /** A recent job opened again: its models, its config files that still exist (the others said and left out), its machine. */
    fun openRecent(r: RecentJobs.Job, done: () -> Unit = {}) {
        val (files, notes) = configsFor(r.models, r.configFiles.filterNot { isPlanFile(Path.of(it)) })
        notes.filterNot { it.startsWith("plan file:") }.forEach { say(it) }
        Snapshot.withMutableSnapshot { tries = emptyList() }
        open(files, r.models, r.machine, done)
    }

    /** The job's plan file (the config file carrying `plan.source_fingerprint`), where decisions are locked and kept; null when the job was opened without one. */
    val planFile: Path? get() = configFiles.map { Path.of(it) }.lastOrNull { isPlanFile(it) }
    /** The user's own config file the job was opened with (the last .toml that is not a plan file): where a setting's value goes. */
    val userConfig: Path? get() = configFiles.map { Path.of(it) }.lastOrNull { Files.isRegularFile(it) && it.toString().endsWith(".toml") && !Files.readString(it).contains("[plan.source_fingerprint") }
    /** A band of layers from [lo] to [hi] mm above the plate, with its words, and the decision it belongs to (an index into [PlanMarks.decisions]). */
    class Band(val lo: Double, val hi: Double, val label: String, val decision: Int,
               /** The footprint of the object the band is of, in the plate's frame (x0, y0, x1, y1): where its slab is drawn; null for the plate's. */
               val footprint: DoubleArray? = null)
    /** A support spot's box in the plate's frame, with its name, whether the user locked it, and the decision it belongs to. */
    class Box(val x0: Double, val x1: Double, val y0: Double, val y1: Double, val z0: Double, val z1: Double, val label: String, val locked: Boolean, val decision: Int)
    /** Every line the commands printed, in order (the console). */
    var console by mutableStateOf(emptyList<String>()); private set
    /** The sliced file, once `plymeld slice` wrote it. */
    var gcode by mutableStateOf<Path?>(null); private set

    private var configFiles: List<String> = emptyList()
    private var modelFiles: List<String> = emptyList()
    private var machine: String? = null
    /** The job as last read: null while one is being read and after a read failed, so nothing slices or sends an old job. */
    @Volatile private var product: Product? = null
    @Volatile private var decided: Plan? = null

    /** Where a slice of the job as open now is written: named after its models ([outFor]). */
    fun sliceOut(): Path = outFor(modelFiles)
    /** The config stack the job was opened with (every entry, the overridden ones included) and the config it adds up to: the finder's settings. */
    var stack by mutableStateOf<ConfigStack?>(null); private set
    var config by mutableStateOf<plymeld.config.Config?>(null); private set
    /** The model's parts by object, for the finder. */
    var partNames by mutableStateOf(emptyList<Pair<String, String>>()); private set

    init { viewer.vp.gl { dark = Ink.dark }; viewer.requestFrame() }

    /** A line into the console; a multi-line message (a check's refusal with its problems) is one line each. */
    private fun say(line: String) = Snapshot.withMutableSnapshot { console = console + line.split('\n') }
    /** The command line the window is about to run, into the console as a terminal would show it: `$ plymeld WORDS`, a word with a space quoted; then [tail]. */
    private fun sayLine(words: List<String>, tail: String = "") = say("$ plymeld " + words.joinToString(" ") { if (' ' in it) "'$it'" else it } + tail)

    /** The check's problems from the last slice (`check: N problem(s) ...` and its indented lines), empty when it passed. */
    var checkProblems by mutableStateOf(emptyList<String>()); private set

    /** The slicer kept across edits (SliceSession): the same plate's unchanged stages are not computed again. */
    private val session = plymeld.commands.SliceSession()
    /** When the last slice reached the view (System.nanoTime), before its check: the benchmark reads it. */
    @Volatile var shownNanos = 0L; private set
    /** When the last slice's first layers reached the view while it was being written (0 when it was too quick to stream). */
    @Volatile var firstShownNanos = 0L; private set
    /** Where the last slice went; an edit slices there again by itself. */
    private var lastOut: Path? = null
    /** What is running now (the step's bar): the stage, its count, and whether a cancel was asked. */
    class Running(val what: String, val stage: String, val done: Int, val total: Int)
    var running by mutableStateOf<Running?>(null); private set
    private val cancelAsked = java.util.concurrent.atomic.AtomicBoolean(false)
    /** The bar's Cancel: the work stops at its next layer; the step says what was left undone. */
    fun cancel() { cancelAsked.set(true) }
    private fun progressFor(what: String) = object : plymeld.program.Progress {
        // the stages count from every core at once: one writer at a time into the snapshot state
        override fun at(stage: String, done: Int, total: Int) { synchronized(cancelAsked) { Snapshot.withMutableSnapshot { running = Running(what, stage, done, total) } } }
        override val canceled: Boolean get() = cancelAsked.get()
    }
    /** A line of the window's own in the console (what the finder picked, a part named). */
    fun note(line: String) = say(line)

    /** A button's command as its caption shows it: `plymeld COMMAND` with the job's files, tries, models, and machine. */
    fun commandLine(command: String, vararg more: String): String = "plymeld " + args(command, *more).joinToString(" ") { if (' ' in it) "'$it'" else it }
    /** `plymeld printer ... ` on the job's config (a command that takes no models), as its caption shows it. */
    fun printerLine(vararg words: String): String = "plymeld " + configArgs(*words).joinToString(" ") { if (' ' in it) "'$it'" else it }

    /** The command line that would do what the window does, for the console (every action is a command). */
    private fun args(command: String, vararg more: String): List<String> =
        listOf(command) + configFiles.flatMap { listOf("-cf", it) } + tries.flatMap { listOf("-cv", it) } + modelFiles.flatMap { listOf("-m", it) } + (machine?.let { listOf("--machine", it) } ?: emptyList()) +
            (slot?.let { listOf("--slot", it.toString()) } ?: emptyList()) + more

    /**
     * Opens a job: the config files and models as `plymeld plan -cf ... -m ...` takes them, then decides the plan. Runs
     * on a thread of its own; the steps fill in as it goes. [done] is called at the end (screenshots wait on it).
     */
    fun open(configFiles: List<String>, modelFiles: List<String>, machine: String?, done: () -> Unit = {}) {
        // a glob among the config files (-cf 'DIR/*.toml') is the files it names, in its order, as the command line reads it:
        // the job holds files, so its plan file, its own config file, and its edits find theirs (a glob is no path on
        // Windows); one that names none is kept as given, and refused with the reason when the job is read
        this.configFiles = configFiles.flatMap { f -> if (f.none { it in "*?[{" }) listOf(f) else runCatching { ConfigFiles.expand(listOf(f)).map { it.toString() } }.getOrDefault(listOf(f)) }
        this.modelFiles = modelFiles; this.machine = machine
        val configFiles = this.configFiles
        openError = null
        Snapshot.withMutableSnapshot { slotOffer = null }
        Thread({
            try {
                // a job opened again (an edit, undo, redo, another model): what was sliced is of the old plan, and the old job
                // itself is forgotten until this one is read, so a failed open leaves nothing old to slice
                product = null; decided = null
                Snapshot.withMutableSnapshot { gcode = null; send = StepView(Status.WAIT, listOf(SEND_WAITS)); marks = null; rerun = null }
                // no model yet (the window opened from a shortcut, with no arguments): the steps wait for one
                if (modelFiles.isEmpty()) {
                    // the machines for step 2's choice; a config that cannot be read yet lists none (it is read, and refused, with a model)
                    val defined = if (configFiles.isEmpty()) emptyList() else runCatching { machinesOf(ConfigStack.load(configFiles, emptyList())) }.getOrDefault(emptyList())
                    Snapshot.withMutableSnapshot {
                        machines = defined
                        title = ""
                        model = StepView(Status.NOW, listOf("Open a model, or drop one on the right."))
                        printer = if (configFiles.isEmpty()) StepView(Status.NOW, listOf("No printer yet: set one up (Set up a printer), or add a config file naming one (hardware.machine.<key>) and its filaments, or drop it on the stage."), "no config file open")
                            else StepView(Status.WAIT, listOf("${configFiles.size} config file${if (configFiles.size == 1) "" else "s"} open: " + configFiles.joinToString { Path.of(it).fileName.toString() },
                                // the machine chosen under Another printer, kept for the model to come and shown here
                                (machine?.let { "to print on $it; " } ?: "") + "read with the model"))
                        matters = StepView(Status.WAIT, listOf("after a model is open")); plan = StepView(Status.WAIT, listOf("decided from the model, the printer, and what matters"))
                    }
                    return@Thread
                }
                // a plan file among the config files is a lock, as `slice` reads it (the window re-plans only when asked to)
                sayLine(args("plan"), "   (plan files as locks, as slice reads them)")
                val t0 = System.nanoTime()
                Snapshot.withMutableSnapshot {
                    title = modelFiles.joinToString(", ") { Path.of(it).fileName.toString() }
                    model = StepView(Status.BUSY, listOf(title, "reading…"))
                }
                // no config at all: the model is shown as it is, and the Printer step says what is missing (nothing is decided
                // without a machine: the machine's facts are settings)
                if (configFiles.isEmpty()) {
                    Snapshot.withMutableSnapshot { machines = emptyList() }
                    val inputs = modelFiles.flatMap { plymeld.commands.loadModel(Path.of(it), null) }
                    val parts = inputs.flatMap { it.parts }
                    val b = union(parts)
                    Snapshot.withMutableSnapshot {
                        partNames = inputs.flatMap { i -> i.partNames.map { i.objectName to it } }
                        model = StepView(Status.DONE, listOf(title, "${inputs.size} object${if (inputs.size == 1) "" else "s"} · ${parts.size} part${if (parts.size == 1) "" else "s"} · ${mm(b[3] - b[0])} × ${mm(b[4] - b[1])} × ${mm(b[5] - b[2])} mm · ${"%,d".format(parts.sumOf { it.count })} triangles"))
                        printer = StepView(Status.NOW, listOf("No printer: set one up (Set up a printer), or add a config file naming one (hardware.machine.<key>), or drop it on the stage."), "no config file open")
                        matters = StepView(Status.WAIT, listOf("after a printer"))
                        plan = StepView(Status.WAIT, listOf("decided from the model, the printer, and what matters"))
                    }
                    showParts(parts, parts.map { Renderer.DEFAULT_MESH_RGB })
                    return@Thread
                }
                // the values tried for this run (-cv) over every file, then the one slot (--slot), as the command line orders them
                val st = ConfigStack.load(configFiles, tries).let { s -> slot?.let { ConfigStack(s.all + plymeld.commands.slotEntry(it)) } ?: s }
                Snapshot.withMutableSnapshot { machines = machinesOf(st) }
                val pr = Product(st, modelFiles.map { Path.of(it) }, machine, null, rePlan = false) { say(it) }
                product = pr
                // plan files are not remembered: each belongs to its own job, and a model opened alone gets its own ([configsFor])
                if (configFiles.isNotEmpty()) LastJob.write(configFiles.filterNot { isPlanFile(Path.of(it)) }, machine)
                RecentJobs.add(modelFiles, configFiles.filterNot { isPlanFile(Path.of(it)) }, machine)
                Snapshot.withMutableSnapshot { recent = RecentJobs.read() }
                Snapshot.withMutableSnapshot { stack = st; config = pr.config; partNames = pr.plate.flatMap { (o, i) -> i.partNames.map { o.key to it } } }
                readJournal()
                // what the window reads to show the job is not what the print reads: kept out of the source block's count (Config.reading)
                pr.config.reading { fillModel(pr); fillPrinter(pr); fillMatters(pr) }
                // each part's slot as the config resolves it (an override in model.object.<key>.part.<part>.slot wins over the
                // model's own number: the parts were drawn in the default color when a config re-assigned them)
                val slots = pr.config.reading { resolvedSlots(pr) }.first
                fun colors() = slots.map { slot -> loadout.firstOrNull { it.slot == slot }?.rgb ?: Renderer.DEFAULT_MESH_RGB }
                showParts(pr.plate.flatMap { it.second.parts }, colors(), pr.plate.flatMap { (o, i) -> List(i.parts.size) { o.key } })
                Snapshot.withMutableSnapshot { plan = StepView(Status.BUSY, listOf("deciding…")) }
                val p = pr.decide()
                decided = p
                val text = p.describe().trimEnd()
                text.lines().forEach { say(it) }
                pr.firstLayerLines().forEach { (step, why) -> say("[$step] $why") }
                val ms = (System.nanoTime() - t0) / 1_000_000
                lastPlanMs = ms
                Snapshot.withMutableSnapshot {
                    planText = text
                    marks = pr.config.reading { planMarks(pr, p) }.first
                    plan = StepView(Status.DONE, planSentences(pr, p), "decided in ${"%.1f".format(Locale.ROOT, ms / 1000.0)} s")
                    slice = StepView(Status.NOW, listOf("Slice the plan and read the file back."))
                }
                // the plate as planned: each part where it was placed (the placed objects' parts keep the model's part order), in its filament's color
                showParts(p.parts, colors(), p.layout.placed.flatMapIndexed { k, pl -> List(pl.parts.size) { pr.plate.getOrNull(p.layout.objectIndex[k])?.first?.key ?: pl.name } })
            } catch (e: Exception) {
                say("plymeld: ${e.message}")
                openError = e.message ?: e.toString()
                // a part on a filament the loadout lacks: the refusal's own suggestion, --slot N, offered in step 1
                Snapshot.withMutableSnapshot { slotOffer = if (slot == null) SLOT_SUGGESTION.find(openError!!)?.groupValues?.get(1)?.toIntOrNull() else null }
                Snapshot.withMutableSnapshot {
                    if (product == null) {
                        // the job could not be read: nothing of the job before stays on the steps (it is not what the title names)
                        model = StepView(Status.FAILED, listOf(title, e.message ?: e.toString()))
                        printer = StepView(Status.WAIT, listOf("after the model and config are read"))
                        matters = StepView(Status.WAIT, listOf("after a model is open"))
                        plan = StepView(Status.WAIT, listOf("decided from the model, the printer, and what matters"))
                        slice = StepView(Status.WAIT, listOf(SLICE_WAITS))
                        stack = null; config = null; partNames = emptyList(); checkProblems = emptyList(); planText = ""
                    } else {
                        plan = StepView(Status.FAILED, listOf(e.message ?: e.toString()))
                        slice = StepView(Status.WAIT, listOf(SLICE_WAITS))
                    }
                }
            } finally { done() }
        }, "plymeld-job").apply { isDaemon = true }.start()
    }

    /**
     * The Send button: `plymeld printer send FILE` on the machine, in-process: an upload, confirmed by the
     * printer's own file list, and nothing started (`--print` starts it). Its lines go to the console; its refusal (no
     * connection in the config, a credential's variable unset, the printer unreachable or busy) is shown in the step, as
     * the command prints it. Runs on a thread of its own.
     */
    fun send(done: () -> Unit = {}) {
        val g = gcode ?: return
        Thread({
            try {
                val a = listOf("printer", "send", g.toString()) + configFiles.flatMap { listOf("-cf", it) } + (machine?.let { listOf("--machine", it) } ?: emptyList())
                sayLine(a)
                Snapshot.withMutableSnapshot { send = StepView(Status.BUSY, listOf("sending to the $printerName…")) }
                val before = console.size
                val status = Commands.invoke(a, ::say)
                val lines = console.drop(before + 1)
                Snapshot.withMutableSnapshot {
                    send = if (status == 0) StepView(Status.DONE, listOf("Sent to the $printerName.") + lines.filter { it.startsWith("uploaded ") }, g.fileName.toString())
                    else StepView(Status.FAILED, listOf("Not sent.") + lines.filter { it.startsWith("plymeld: ") || it.startsWith("Error") || it.startsWith("Usage") }.map { it.removePrefix("plymeld: ") }.take(2), g.fileName.toString())
                }
            } catch (e: Exception) {
                say("plymeld: ${e.message}")
                Snapshot.withMutableSnapshot { send = StepView(Status.FAILED, listOf("Not sent.", e.message ?: e.toString())) }
            } finally { done() }
        }, "plymeld-send").apply { isDaemon = true }.start()
    }

    /**
     * Step 6's "The bed is clear" button, shown where a send was refused because the printer still shows its last print
     * finished: `plymeld printer clear` on the machine, in-process, the user's word that the print is off the bed; its
     * lines to the console, the outcome in the step. Runs on a thread of its own.
     */
    fun clearBed(done: () -> Unit = {}) {
        Thread({
            try {
                val a = listOf("printer", "clear") + configFiles.flatMap { listOf("-cf", it) } + (machine?.let { listOf("--machine", it) } ?: emptyList())
                sayLine(a)
                val before = console.size
                val status = Commands.invoke(a, ::say)
                val lines = console.drop(before + 1)
                Snapshot.withMutableSnapshot {
                    send = if (status == 0) StepView(Status.NOW, listOf("The ${printerName} was told its bed is clear.") + lines.filter { it.startsWith("status now: ") })
                    else StepView(Status.FAILED, listOf("Not cleared.") + lines.filter { it.startsWith("plymeld: ") || it.startsWith("Error") }.map { it.removePrefix("plymeld: ") }.take(2))
                }
            } catch (e: Exception) {
                say("plymeld: ${e.message}")
                Snapshot.withMutableSnapshot { send = StepView(Status.FAILED, listOf("Not cleared.", e.message ?: e.toString())) }
            } finally { done() }
        }, "plymeld-clear").apply { isDaemon = true }.start()
    }

    /** Whether the last send was refused because the printer still shows its last print finished (it asks for `printer clear`). */
    val sendNeedsClear: Boolean get() = send.status == Status.FAILED && send.lines.any { "plymeld printer clear" in it }

    // ---- editing: every edit is `plymeld edit`, journaled; the job is opened again after each ----

    /**
     * `plymeld edit [--keep] FILE PATH=VALUE...` on the job, in-process, then the job opened again from its files (the
     * plan's locks changed, so the plan is read again; a slice made before is dropped). The transaction (the file's text
     * before and after) goes on the journal for undo. Runs on a thread of its own; [done] at the end.
     */
    fun edit(file: Path, assignments: List<String>, keep: Boolean = false, at: String = REFUSED_IN_PLAN, done: () -> Unit = {}) =
        editArgs(file, (if (keep) listOf("--keep") else emptyList()) + assignments, at, done)

    /**
     * The last refusal of an edit, an undo, or a redo, shown where it was asked (a refusal is shown where it
     * bites): where ([REFUSED_IN_PLAN], [REFUSED_IN_JOURNAL], or the
     * setting's path) and what the command said. Null after one that went through.
     */
    var refusal by mutableStateOf<Pair<String, String>?>(null); private set
    /** The command's own words for its refusal: its last `plymeld:` or `Error` line since [from], else its exit status. */
    private fun refusalSince(from: Int, status: Int): String =
        console.drop(from).lastOrNull { it.startsWith("plymeld: ") || it.startsWith("Error") }?.removePrefix("plymeld: ")?.removePrefix("Error: ") ?: "refused (exit $status)"

    /** `plymeld edit --journal J FILE <args>`, in-process; the job opened again when the file changed. */
    private fun editArgs(file: Path, args: List<String>, at: String, done: () -> Unit) {
        Thread({
            try {
                val before = Files.readString(file)
                val a = listOf("edit") + (journalPath?.let { listOf("--journal", it.toString()) } ?: emptyList()) + listOf(file.toString()) + args
                sayLine(a)
                val from = console.size
                val status = Commands.invoke(a, ::say)
                Snapshot.withMutableSnapshot { refusal = if (status == 0) null else at to refusalSince(from, status) }
                val after = Files.readString(file)
                readJournal()
                if (status == 0 && after != before) reopen(done) else done()
            } catch (e: Exception) { say("plymeld: ${e.message}"); Snapshot.withMutableSnapshot { refusal = at to (e.message ?: e.toString()) }; done() }
        }, "plymeld-edit").apply { isDaemon = true }.start()
    }

    /**
     * The job read again after an edit, undo, or redo; when it had been sliced, sliced again where that went (the change
     * on screen without a click). Meanwhile the old layers stay in the stage, gray ([stale]), and
     * afterwards the bar says what re-ran and how long it took ([rerun]).
     */
    private fun reopen(done: () -> Unit) {
        val again = lastOut?.takeIf { gcode != null }
        if (again != null) grayLayers()
        open(configFiles, modelFiles, machine) {
            val planned = if (product != null) "re-planned in ${"%.1f".format(Locale.ROOT, lastPlanMs / 1000.0)} s" else null
            if (again != null && product != null) slice(again) { Snapshot.withMutableSnapshot { rerun = listOfNotNull(planned, lastSliceWords).joinToString(", "); stale = false }; done() }
            else { Snapshot.withMutableSnapshot { rerun = planned; stale = false }; done() }
        }
    }

    /** The layers shown are of the job before an edit, being sliced again: grayed until the new ones replace them. */
    var stale by mutableStateOf(false); private set
    /** What the last edit re-ran and how long each took, for the bar ("re-planned in 3.3 s, re-sliced in 2.1 s (unchanged stages kept)"). */
    var rerun by mutableStateOf<String?>(null); private set
    @Volatile private var lastPlanMs = 0L
    @Volatile private var lastSliceWords: String? = null
    private fun grayLayers() {
        Snapshot.withMutableSnapshot { stale = true }
        viewer.paths?.let { p -> viewer.useColors(IntArray(p.segments) { STALE_RGB }, Legend(ColorBy.TOOL, listOf("the layers before the change: slicing again" to STALE_RGB))) }
    }

    /** `plymeld journal J --undo`: the last applied edit taken back, the job opened again. */
    fun undo(done: () -> Unit = {}) = journalStep("--undo", done)

    /** `plymeld journal J --redo`: the last undone edit done again, the job opened again. */
    fun redo(done: () -> Unit = {}) = journalStep("--redo", done)

    private fun journalStep(flag: String, done: () -> Unit) {
        val jp = journalPath ?: run { done(); return }
        Thread({
            try {
                val a = listOf("journal", jp.toString(), flag)
                sayLine(a)
                val from = console.size
                val status = Commands.invoke(a, ::say)
                Snapshot.withMutableSnapshot { refusal = if (status == 0) null else REFUSED_IN_JOURNAL to "${flag.removePrefix("--")} refused: " + refusalSince(from, status) }
                readJournal()
                if (status == 0) reopen(done) else done()
            } catch (e: Exception) { say("plymeld: ${e.message}"); Snapshot.withMutableSnapshot { refusal = REFUSED_IN_JOURNAL to (e.message ?: e.toString()) }; done() }
        }, "plymeld-journal").apply { isDaemon = true }.start()
    }

    /**
     * The plan file to lock decisions in: the job's, or one written now by `plymeld plan -o` beside the job's g-code and
     * added to the job's files (the window then re-reads the job with it, so what is shown is what the file says).
     */
    fun ensurePlanFile(done: (Path?) -> Unit) {
        planFile?.let { done(it); return }
        Thread({
            try {
                // kept in the user's data folder, one per job (not the temp folder: cleaned away, the file would lock
                // the window out at its next start, and its locks would be lost)
                val out = planFileFor(modelFiles)
                Files.createDirectories(out.parent)
                val a = args("plan", "-o", out.toString())
                sayLine(a)
                Snapshot.withMutableSnapshot { plan = StepView(Status.BUSY, listOf("writing the plan file…")) }
                val status = Commands.invoke(a, ::say)
                if (status != 0 || !Files.exists(out)) { say("plymeld: no plan file written"); done(null); return@Thread }
                configFiles = configFiles + out.toString()
                open(configFiles, modelFiles, machine) { done(out) }
            } catch (e: Exception) { say("plymeld: ${e.message}"); done(null) }
        }, "plymeld-plan-file").apply { isDaemon = true }.start()
    }

    /** Lock object [obj]'s flat layers at [mm]: every band one height, the plan file's lines for it. */
    fun lockHeight(obj: String, mm: String, done: () -> Unit = {}) = ensurePlanFile { f ->
        if (f == null) { done(); return@ensurePlanFile }
        val n = marks?.bandsOf?.get(obj) ?: 1
        val a = ArrayList<String>()
        a += "plan.object.$obj.technique.flat.layer.height=$mm mm"
        for (i in 1..n) { a += "plan.object.$obj.band.$i.heights=one"; a += "plan.object.$obj.band.$i.layer.height=$mm mm" }
        edit(f, a, done = done)
    }

    /**
     * `plymeld edit --unset-under ...` on the plan file: the decision's lines removed, so it is decided again when the
     * job is read (the window reads a plan file as locks, so only a removed line is decided again).
     */
    fun decideAgain(obj: String, what: String, done: () -> Unit = {}) {
        val f = planFile ?: run { done(); return }
        val a = when (what) {
            "technique" -> listOf("--unset", "plan.object.$obj.technique.flat.layer.height", "--unset-under", "plan.object.$obj.band")
            else -> listOf("--unset-under", "plan.object.$obj.support.spot.$what")
        }
        editArgs(f, a, REFUSED_IN_PLAN, done)
    }

    /** Whether spot [id] of [obj] is kept (its box lines marked `!` in the stack). */
    fun spotKept(obj: String, id: String): Boolean = stack?.all?.any { it.key == "plan.object.$obj.support.spot.$id.min" && it.keep } == true

    /** Keep spot [id] of [obj] across re-plans (its box lines, min and max, marked, and the pose with them), or let it be decided again. */
    fun keepSpot(obj: String, id: String, keep: Boolean, done: () -> Unit = {}) = ensurePlanFile { f ->
        if (f == null) { done(); return@ensurePlanFile }
        val text = Files.readString(f)
        val sides = listOf("min", "max")
        val a = ArrayList<String>()
        for (s in sides) { val v = plymeld.commands.TomlEdit.get(text, "plan.object.$obj.support.spot.$id.$s") ?: continue; a += "plan.object.$obj.support.spot.$id.$s=${v.removePrefix("!")}" }
        if (keep) plymeld.commands.TomlEdit.get(text, "plan.object.$obj.pose.turn")?.let { a += "plan.object.$obj.pose.turn=${it.removePrefix("!")}" }
        if (a.isEmpty()) { say("plymeld: spot '$id' of '$obj' has no lines in ${f.fileName}"); done(); return@ensurePlanFile }
        edit(f, a, keep = keep, done = done)
    }

    /**
     * Where the drawer writes a value for [path]: the plan file when the value in force is its line, else the user's own
     * config file (the job's last one that is not a plan file); null with neither ([writeTarget]).
     */
    fun fileFor(path: String): Path? = targetFor(path).file

    /** Where a value for [path] is written, and why there ([target]); a null file: the job has none of its own yet ([setValue] makes one). */
    fun targetFor(path: String): Target =
        target(path, config?.values?.get(path)?.source, planFile, userConfig, definingFile(path), configFiles.map { Path.of(it).toAbsolutePath().normalize() }.toSet())

    /** The job file that defines the entity a hardware path is under (`hardware.machine.<key>`, tool, filament, plate): the first job file with a line under it. */
    private fun definingFile(path: String): Path? {
        val segs = path.split('.')
        if (segs.size < 4 || segs[0] != "hardware" || segs[1] !in setOf("machine", "tool", "filament", "plate")) return null
        val jobFiles = configFiles.map { Path.of(it).toAbsolutePath().normalize() }
        val defining = stack?.all?.filter { it.path.size > 3 && it.path[0] == "hardware" && it.path[1] == segs[1] && it.path[2] == segs[2] }
            ?.mapNotNull { e -> runCatching { Path.of(e.source.substringBeforeLast(':')).toAbsolutePath().normalize() }.getOrNull() }?.toSet() ?: return null
        return jobFiles.firstOrNull { it in defining }
    }

    /**
     * A setting's value into the file [targetFor] names (`plymeld edit`, journaled). With no config file of the job's own to
     * write into, one is made first in the user's data folder ([jobFileFor]) and added to the job, so a value can always
     * be set.
     */
    fun setValue(path: String, value: String, done: () -> Unit = {}) = setValues(listOf(path to value), done)

    /**
     * Step 3's choices (Quality / Balanced / Speed and the priorities they stand for; a choice
     * for every goal): Balanced puts every goal at 1, each other choice its own [goal] at 5
     * and the rest at 1. Every goal's weight is
     * written (`job.priorities.<goal>`, into the job's own file), so a choice never inherits another's; the
     * measures' own weights (`job.priorities.measure.layer_lines`, ...) are left as they are. Custom is a slider per goal ([setGoalWeight]).
     */
    enum class Preset(val label: String, val goal: plymeld.core.decide.Goal?) {
        BALANCED("Balanced", null), QUALITY("Quality", plymeld.core.decide.Goal.QUALITY), ACCURACY("Accuracy", plymeld.core.decide.Goal.ACCURACY),
        SPEED("Speed", plymeld.core.decide.Goal.SPEED), MATERIAL("Material", plymeld.core.decide.Goal.MATERIAL),
        STRENGTH("Strength", plymeld.core.decide.Goal.STRENGTH), RELIABILITY("Reliability", plymeld.core.decide.Goal.RELIABILITY);
        /** Each goal's weight under this choice. */
        val weights: Map<plymeld.core.decide.Goal, Int> get() = plymeld.core.decide.Goal.entries.associateWith { if (it == goal) 5 else 1 }
    }
    /** The highest weight Custom's sliders set. */
    val maxGoalWeight = 100
    /** Each goal's weight in force (a goal no config sets weighs 1, the catalog's equal weights); null with no job open. */
    val goalWeights: Map<plymeld.core.decide.Goal, Double>? get() {
        val v = config?.values ?: return null
        return plymeld.core.decide.Goal.entries.associateWith { g -> v["job.priorities.${g.id}"]?.value?.toString()?.toDoubleOrNull() ?: 1.0 }
    }
    /** The choice the weights in force match, or null (weights of the user's own: Custom). */
    val preset: Preset? get() {
        val w = goalWeights ?: return null
        return Preset.entries.firstOrNull { p -> p.weights.all { (g, n) -> w[g] == n.toDouble() } }
    }
    /** A step 3 choice taken: every goal's weight written in one edit (one entry in the journal, so one undo). */
    fun usePreset(p: Preset, done: () -> Unit = {}) = setValues(p.weights.map { (g, n) -> "job.priorities.${g.id}" to n.toString() }, done)
    /** Custom: one goal's weight (0..[maxGoalWeight]) written into the job's own file. */
    fun setGoalWeight(g: plymeld.core.decide.Goal, weight: Int, done: () -> Unit = {}) =
        setValue("job.priorities.${g.id}", weight.coerceIn(0, maxGoalWeight).toString(), done)

    /** Several values, one file, one `plymeld edit` (one journal entry): the file [targetFor] names for the first path. */
    fun setValues(values: List<Pair<String, String>>, done: () -> Unit = {}) {
        val path = values.first().first
        val t = targetFor(path)
        val f = t.file ?: run {
            val made = jobFileFor(modelFiles)
            try {
                Files.createDirectories(made.parent)
                if (!Files.exists(made)) Files.writeString(made, "# the job's own config file, made by the window to hold values set in it (${jobName(modelFiles)})\n")
            } catch (e: Exception) { say("plymeld: no job config file could be made at $made: ${e.message}"); Snapshot.withMutableSnapshot { refusal = path to "no job config file could be made: ${e.message}" }; done(); return }
            say("job config file made: $made")
            configFiles = withConfig(configFiles, made.toString())
            made
        }
        if (t.why.isNotEmpty()) say("$path goes to ${f.fileName}: ${t.why}")
        Snapshot.withMutableSnapshot { tries = tries.filterNot { tr -> values.any { it.first == tr.substringBefore('=') } } }   // a value made a file value is no longer tried
        edit(f, values.map { (p, v) -> "$p=$v" }, at = path, done = done)
    }

    /**
     * The values tried for this run only ("a try to `-cv` for this run, one click from a file value"), as
     * `PATH=VALUE`: given to the stack as `plymeld -cv` takes them, over every file, and on every command line the window
     * shows; not remembered, not written anywhere. Cleared when another model is opened.
     */
    var tries by mutableStateOf(emptyList<String>()); private set
    /** [path] tried at [value] for this run: the job read again with it. */
    fun tryValue(path: String, value: String, done: () -> Unit = {}) {
        Snapshot.withMutableSnapshot { tries = tries.filterNot { it.substringBefore('=') == path } + "$path=$value" }
        say("trying $path=$value for this run (-cv); nothing written")
        reopen(done)
    }
    /** A try dropped: the job read again without it. */
    fun dropTry(path: String, done: () -> Unit = {}) {
        Snapshot.withMutableSnapshot { tries = tries.filterNot { it.substringBefore('=') == path } }
        reopen(done)
    }

    /**
     * `plymeld slice` on the job, in-process, to [out]: the file written (SliceSession.write, the slicer kept from the last
     * run so unchanged stages are skipped) and shown in the stage's Layers at once, then read back (SliceSession.check) while
     * it is on screen; the step's bar shows each stage's layers and takes a Cancel. Runs on a thread of its own.
     */
    fun slice(out: Path, done: () -> Unit = {}) {
        val pr = product ?: run { say("plymeld: no job open"); done(); return }
        Thread({
            val before = console.size
            try {
                val a = args("slice", "-o", out.toString())
                sayLine(a)
                cancelAsked.set(false)
                Snapshot.withMutableSnapshot { slice = StepView(Status.BUSY, listOf("slicing…")); checkProblems = emptyList(); previewing = null }
                // the file goes into the stage as it is written (StreamingLoad): the first layers show while the rest are laid
                val stream = StreamingLoad(viewer, out.fileName.toString())
                val ts = System.nanoTime()
                val w = session.write(pr, out, ::say, progressFor("slicing"), tap = stream)
                lastSliceWords = "re-sliced in ${"%.1f".format(Locale.ROOT, (System.nanoTime() - ts) / 1e9)} s" + (if (w.reused) " (unchanged stages kept)" else " (every stage again)")
                lastOut = out
                val predicted = w.prediction.toString().substringBefore(" (motion limits")
                val pauses = console.drop(before).count { it.startsWith("pause ") }
                // the file is whole: on screen now, read back while it is looked at
                Snapshot.withMutableSnapshot {
                    gcode = out
                    slice = StepView(Status.BUSY, listOfNotNull("checking the file…", "predicted $predicted") + (if (pauses > 0) listOf("$pauses pause(s)") else emptyList()), out.fileName.toString())
                }
                stream.finish()   // the whole file as read, in the view
                firstShownNanos = stream.firstShownNanos
                colorByTool()
                viewer.showMeshAt(GHOST)   // loading the file set the viewer's 0.25; the window's Layers tab keeps the model a faint ghost
                shownNanos = System.nanoTime()   // the toolpaths are on screen from here; the check runs while they are looked at
                val c = session.check(w, ::say, progressFor("checking"))
                Snapshot.withMutableSnapshot {
                    checkProblems = c.problems
                    if (c.refusal == null) {
                        slice = StepView(Status.DONE, listOfNotNull("✓ checked: the g-code reads back as the config and the model say", "predicted $predicted") + (if (pauses > 0) listOf("$pauses pause(s)") else emptyList()), out.fileName.toString())
                        send = StepView(Status.NOW, listOf("Ready for the $printerName."), "an upload; printing starts on the printer")
                    } else {
                        slice = StepView(Status.FAILED, listOf((c.refusal ?: "").substringBefore(";"), "${c.problems.size} problem${if (c.problems.size == 1) "" else "s"}: the tray's Check pane lists them"))
                    }
                }
                c.refusal?.let { say("plymeld: $it") }
            } catch (e: plymeld.program.Progress.Cancelled) {
                // stopped by the user: a half-written file is removed; a file written but not read back is kept, and said so
                val written = gcode == out
                if (!written) Files.deleteIfExists(out)
                say("plymeld: canceled" + (if (written) " during the check: $out is written but not read back; do not print it unchecked" else ": $out not written"))
                Snapshot.withMutableSnapshot {
                    slice = if (written) StepView(Status.FAILED, listOf("check canceled: the file is not read back; do not print it", "Slice & check again to read it"), out.fileName.toString())
                        else StepView(Status.NOW, listOf("Slice the plan and read the file back.", "(the last slice was canceled)"))
                }
            } catch (e: Exception) {
                say("plymeld: ${e.message}")
                Snapshot.withMutableSnapshot { slice = StepView(Status.FAILED, listOf(e.message ?: e.toString())) }
            } finally { Snapshot.withMutableSnapshot { running = null }; done() }
        }, "plymeld-slice").apply { isDaemon = true }.start()
    }

    /** Each line in the color of the filament its tool holds (a tool fed by several slots shows the first's): the Layers tab's "tool" coloring. */
    fun colorByTool() {
        val p = viewer.paths ?: return
        val byT = HashMap<Int, Int>()
        for (s in loadout) byT.putIfAbsent(s.toolIndex - 1, s.rgb)
        val rgb = IntArray(p.segments) { byT[p.tool[it].toInt() and 0xFF] ?: 0x9AA3B0 }
        viewer.useColors(rgb, Legend(ColorBy.TOOL, p.tools.map { t -> (loadout.firstOrNull { it.toolIndex - 1 == t }?.let { "${it.filament} · ${it.tool}" } ?: "T$t") to (byT[t] ?: 0x9AA3B0) }))
    }

    private fun fillModel(pr: Product) {
        val parts = pr.plate.flatMap { it.second.parts }
        val b = union(parts)
        val tris = parts.sumOf { it.count }
        val objects = pr.plate.size
        Snapshot.withMutableSnapshot {
            model = StepView(Status.DONE, listOf(title,
                "${if (objects == 1) "1 object" else "$objects objects"} · ${parts.size} part${if (parts.size == 1) "" else "s"} · ${mm(b[3] - b[0])} × ${mm(b[4] - b[1])} × ${mm(b[5] - b[2])} mm · ${"%,d".format(tris)} triangles"))
        }
    }

    private fun fillPrinter(pr: Product) {
        val c = pr.config
        val m = c.machine ?: return
        val desc = c.entry(listOf("hardware", "machine", m, "model"))?.value?.toString()?.let(::modelName) ?: m
        val slots = ConfigSetup.slots(c, m, ArrayList())
        val sw = slots.map { s ->
            val col = c.entry(listOf("hardware", "filament", s.filament, "color"))?.value?.toString()?.removePrefix("#")?.toIntOrNull(16) ?: 0x9AA3B0
            Swatch(s.filament, col, s.tool, s.number, s.toolIndex)
        }
        // whether the loadout matches the model: the slot each part prints
        // with, as the config resolves it (the model's, or model.object.<key>.part.<part>.slot), is loaded. A slot that is
        // not loaded is refused when the config is read, so a job that got here matches; the line says which slots it uses.
        val used = resolvedSlots(pr)
        val unloaded = used.filter { slot -> sw.none { it.slot == slot } }.distinct()
        val match = if (unloaded.isEmpty()) "matches the model: its parts print from slot${if (used.distinct().size == 1) "" else "s"} ${used.distinct().sorted().joinToString()}, all loaded"
            else "does not match the model: slot ${unloaded.joinToString()} is not loaded"
        Snapshot.withMutableSnapshot {
            loadout = sw
            printerName = m
            printer = StepView(if (unloaded.isEmpty()) Status.DONE else Status.FAILED, listOf("$m · $desc", "loaded: " + sw.joinToString(", ") { "${it.tool} ${it.filament}" }, match),
                "from the config; the printer itself is not asked yet (printer loadout reads it)")
        }
    }

    /** Every part's slot on the plate, in the plate's part order, as the config resolved it (ConfigSetup's part setups: `spool N`). */
    private fun resolvedSlots(pr: Product): List<Int> =
        pr.result.setup.objects.flatMap { o -> o.parts.map { it.spool.removePrefix("spool ").toInt() } }

    private fun fillMatters(pr: Product) {
        val pri = pr.config.values.values.filter { it.key.startsWith("job.priorities.") }.map { "${it.key.removePrefix("job.priorities.")} ${it.value}" }
        Snapshot.withMutableSnapshot { matters = StepView(Status.DONE, listOf(if (pri.isEmpty()) "the bundled priorities" else pri.joinToString(" · "))) }
    }

    /** The plan in plain sentences, one per object, then the pauses: what step 4 shows. */
    private fun planSentences(pr: Product, p: Plan): List<String> {
        val poses = p.decisions.filter { it.value is Pose }
        val techs = p.decisions.filter { it.value is TechniquePlan }
        val out = ArrayList<String>()
        for (k in pr.plate.indices) {
            val pose = poses.getOrNull(k)?.value as Pose?
            val tp = techs.getOrNull(k)?.value as TechniquePlan?
            val name = pr.plate[k].first.key
            val layers = when {
                tp == null -> "layers undecided"
                tp.oneHeight && tp.layerHeightMm != null -> "flat layers of ${mm(tp.layerHeightMm!!)} mm"
                tp.followsThroughout -> "flat layers following the surfaces"
                else -> tp.label
            }
            val support = tp?.support?.let { s -> if (s.any) ", support under ${s.spots.size} spot${if (s.spots.size == 1) "" else "s"}" else ", no support" } ?: ""
            out += (if (pr.plate.size > 1) "$name: " else "") + layers + (pose?.let { ", ${it.label}" } ?: "") + support
        }
        for (pl in pr.pauses()) out += when (pl) {
            is PauseLock.AtZ -> "a pause at ${mm(pl.zMm)} mm"
            is PauseLock.Above -> "a pause above ${mm(pl.zMm)} mm"
            is PauseLock.AtLayer -> "a pause at layer ${pl.n}"
            is PauseLock.Before -> "a pause before ${pl.name}"
            is PauseLock.After -> "a pause after ${pl.name}"
        }
        return out
    }

    /**
     * The plan's marks in the plate's frame: each placed object's bands (from its bottom) and support spots (from its
     * posed corner, as the plan keeps them), the pauses at a height, and the decisions as the plan made them.
     */
    private fun planMarks(pr: Product, p: Plan): PlanMarks {
        val bands = ArrayList<Band>(); val spots = ArrayList<Box>()
        val techs = p.decisions.filter { it.value is TechniquePlan }
        val objectOf = HashMap<Int, String>(); val bandsOf = HashMap<String, Int>(); val spotsOf = HashMap<String, List<String>>()
        for ((j, pl) in p.layout.placed.withIndex()) {
            val tp = p.layout.techniques[j]
            // the technique decision of this object: what a band or a spot opens in the drawer
            val dec = p.decisions.indexOf(techs.getOrNull(p.layout.objectIndex[j]) ?: techs.getOrNull(j))
            val objKey = pr.plate.getOrNull(p.layout.objectIndex[j])?.first?.key ?: pl.name
            objectOf[dec] = objKey; bandsOf[objKey] = tp.bands.size; spotsOf[objKey] = tp.support?.spots?.map { it.id } ?: emptyList()
            val bx = pl.parts.minOf { it.bounds()[0] }; val by = pl.parts.minOf { it.bounds()[1] }; val bz = pl.parts.minOf { it.bounds()[2] }
            val top = pl.parts.maxOf { it.bounds()[5] }
            val prefix = if (p.layout.placed.size > 1) "${pl.name}: " else ""
            for (b in tp.bands) {
                val hi = if (b.hi.isInfinite()) top else bz + b.hi
                val words = if (b.oneHeight && tp.layerHeightMm != null) "flat layers of ${mm(tp.layerHeightMm!!)} mm" else if (b.follow) "flat layers following the surfaces" else b.technique.short
                bands += Band(bz + b.lo, hi, prefix + words, dec, doubleArrayOf(bx, by, pl.parts.maxOf { it.bounds()[3] }, pl.parts.maxOf { it.bounds()[4] }))
            }
            tp.support?.spots?.forEach { s ->
                val r = s.region
                spots += Box(bx + r.x0, bx + r.x1, by + r.y0, by + r.y1, bz + r.fromMm, bz + r.toMm, prefix + s.id + (if (s.locked) " (locked)" else ""), s.locked, dec)
            }
        }
        // every pause step 4 lists, of every kind: at a height where it is one, before
        // an object at its bottom, after one at its top; at a layer by its number, placed on the timeline once sliced
        fun placedOf(name: String) = p.layout.placed.withIndex().firstOrNull { (k, pl) -> pl.name == name || pr.plate.getOrNull(p.layout.objectIndex[k])?.first?.key == name }?.value
        val pauses = pr.pauses().mapNotNull { pl -> when (pl) {
            is PauseLock.AtZ -> pl.zMm to "pause at ${mm(pl.zMm)} mm"
            is PauseLock.Above -> pl.zMm to "pause above ${mm(pl.zMm)} mm"
            is PauseLock.Before -> placedOf(pl.name)?.let { o -> o.parts.minOf { it.bounds()[2] } to "pause before ${pl.name}" }
            is PauseLock.After -> placedOf(pl.name)?.let { o -> o.parts.maxOf { it.bounds()[5] } to "pause after ${pl.name}" }
            is PauseLock.AtLayer -> null
        } }
        val layerPauses = pr.pauses().filterIsInstance<PauseLock.AtLayer>().map { it.n to "pause at layer ${it.n}" }
        return PlanMarks(union(p.parts), bands, spots, pauses, p.decisions, pr.plate.map { it.first.key }, objectOf, bandsOf, spotsOf, layerPauses)
    }

    // ---- picking on the stage (everything in the stage is selectable) ----

    /** The parts in the stage and the object key of each, as last shown: what a click on the Model tab picks from. */
    @Volatile private var shownParts: List<TriangleSoup> = emptyList()
    @Volatile private var shownObjects: List<String> = emptyList()

    /** A screen point's frame: the camera the shown frame was drawn with, its basis, and the click in -1..1 each way (y up). */
    internal class Pick(cam: Camera, val nx: Double, val ny: Double, val aspect: Double) {
        val e = cam.eye(); val r = cam.right(); val u = cam.up(); val b = cam.back(); val hh = cam.halfHeight(); val ortho = cam.orthographic; val dist = cam.distance
        /** World point (x, y, z) on the screen: (ndc x, ndc y, depth), depth > 0 in front of the eye. */
        fun at(x: Double, y: Double, z: Double, out: DoubleArray) {
            val dx = x - e[0]; val dy = y - e[1]; val dz = z - e[2]
            val sx = dx * r[0] + dy * r[1] + dz * r[2]; val sy = dx * u[0] + dy * u[1] + dz * u[2]; val d = -(dx * b[0] + dy * b[1] + dz * b[2])
            val s = if (ortho) 1.0 else dist / d
            out[0] = sx * s / (hh * aspect); out[1] = sy * s / hh; out[2] = d
        }
    }

    /**
     * The decision of the part under a click on the Model tab ([px], [py] in a [w] × [h] view): the nearest triangle whose
     * picture holds the point, its part's object, that object's technique decision (an index into the plan's decisions,
     * what the drawer opens); null when the click is on nothing or nothing is decided yet.
     */
    fun pickDecisionAt(px: Float, py: Float, w: Int, h: Int): Int? {
        val parts = shownParts; val objects = shownObjects
        if (parts.isEmpty() || w <= 0 || h <= 0) return null
        val k = Pick(viewer.frameCamera, px / w * 2.0 - 1, 1 - py / h * 2.0, w.toDouble() / h)
        val a = DoubleArray(3); val b = DoubleArray(3); val c = DoubleArray(3)
        var best = Double.MAX_VALUE; var bestPart = -1
        for ((pi, s) in parts.withIndex()) {
            val v = s.coords
            for (t in 0 until s.count) {
                val o = t * 9
                k.at(v[o].toDouble(), v[o + 1].toDouble(), v[o + 2].toDouble(), a)
                k.at(v[o + 3].toDouble(), v[o + 4].toDouble(), v[o + 5].toDouble(), b)
                k.at(v[o + 6].toDouble(), v[o + 7].toDouble(), v[o + 8].toDouble(), c)
                if (a[2] <= 0 || b[2] <= 0 || c[2] <= 0) continue
                // inside the projected triangle: the three edge signs agree
                fun edge(p: DoubleArray, q: DoubleArray) = (q[0] - p[0]) * (k.ny - p[1]) - (q[1] - p[1]) * (k.nx - p[0])
                val e0 = edge(a, b); val e1 = edge(b, c); val e2 = edge(c, a)
                if (!((e0 >= 0 && e1 >= 0 && e2 >= 0) || (e0 <= 0 && e1 <= 0 && e2 <= 0))) continue
                val depth = (a[2] + b[2] + c[2]) / 3
                if (depth < best) { best = depth; bestPart = pi }
            }
        }
        val obj = objects.getOrNull(bestPart) ?: return null
        note("picked $obj")
        return marks?.objectOf?.entries?.firstOrNull { it.value == obj }?.key
    }

    /**
     * The line under a click on the Layers tab: among the layers shown, the top-most with a line within 10 px of the
     * point, its nearest line; the view goes to it (that layer on top, the move slider at that line, so the Layers bar
     * says what the move is). False when no line is near.
     */
    fun pickMoveAt(px: Float, py: Float, w: Int, h: Int): Boolean {
        val p = viewer.paths ?: return false
        if (w <= 0 || h <= 0) return false
        val k = Pick(viewer.frameCamera, px / w * 2.0 - 1, 1 - py / h * 2.0, w.toDouble() / h)
        val reach = 10.0 * 2 / h   // 10 px, in the -1..1 of the view's height
        val a = DoubleArray(3); val b = DoubleArray(3)
        for (l in viewer.layerHi downTo viewer.layerLo) {
            var best = Double.MAX_VALUE; var bestSeg = -1
            for (s in p.layerStart[l] until p.layerStart[l + 1]) {
                val o = s * 6
                k.at(p.xyz[o].toDouble(), p.xyz[o + 1].toDouble(), p.xyz[o + 2].toDouble(), a)
                k.at(p.xyz[o + 3].toDouble(), p.xyz[o + 4].toDouble(), p.xyz[o + 5].toDouble(), b)
                if (a[2] <= 0 || b[2] <= 0) continue
                // distance from the click to the segment on the screen (x scaled by the aspect so it is in the height's units)
                val ax = a[0] * k.aspect; val bx = b[0] * k.aspect; val cx = k.nx * k.aspect
                val vx = bx - ax; val vy = b[1] - a[1]; val len = vx * vx + vy * vy
                val t = if (len == 0.0) 0.0 else (((cx - ax) * vx + (k.ny - a[1]) * vy) / len).coerceIn(0.0, 1.0)
                val dx = ax + t * vx - cx; val dy = a[1] + t * vy - k.ny
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d < best) { best = d; bestSeg = s }
            }
            if (bestSeg >= 0 && best <= reach) {
                viewer.setLayers(viewer.layerLo, l)
                viewer.moveTo(bestSeg - p.layerStart[l] + 1)
                return true
            }
        }
        return false
    }

    /** The parts as one mesh in the stage, each triangle in its part's color; the same parts in the same colors (a job read again after an edit) are not uploaded again. */
    private var shown: String? = null
    private fun showParts(parts: List<TriangleSoup>, rgbPerPart: List<Int>, objects: List<String> = emptyList()) {
        shownParts = parts; shownObjects = objects
        val key = parts.joinToString("+") { "${it.count}:${it.coords.contentHashCode()}" } + "|" + rgbPerPart.joinToString(",")
        if (key == shown) return
        shown = key
        val total = parts.sumOf { it.count }
        val coords = FloatArray(total * 9); val rgb = IntArray(total)
        var at = 0
        for ((i, s) in parts.withIndex()) {
            System.arraycopy(s.coords, 0, coords, at * 9, s.count * 9)
            java.util.Arrays.fill(rgb, at, at + s.count, rgbPerPart.getOrElse(i) { Renderer.DEFAULT_MESH_RGB })
            at += s.count
        }
        viewer.setMeshNow(TriangleSoup(coords, total), rgb, title)
    }

    companion object {
        /**
         * [files] with [file] added where it belongs (a dropped file lands in its group by its number; file order decides
         * which value wins): before every plan
         * file, and among files whose names start with a number (`01_filaments.toml`) after the last one numbered no higher;
         * an unnumbered file after the numbered ones.
         */
        fun withConfig(files: List<String>, file: String): List<String> {
            fun number(f: String): Int? = Regex("^(\\d+)").find(Path.of(f).fileName.toString())?.groupValues?.get(1)?.toIntOrNull()
            val plans = files.filter { isPlanFile(Path.of(it)) }
            val rest = files - plans.toSet()
            val n = number(file)
            val at = if (n == null) rest.size else rest.indexOfLast { number(it)?.let { m -> m <= n } == true } + 1
            return rest.take(at) + file + rest.drop(at) + plans
        }

        /**
         * A typed line split into words as a POSIX shell would for plain words and quotes: spaces separate, '...' keeps
         * everything inside, "..." keeps everything but a backslash-escaped " or \, a backslash outside quotes escapes the
         * next character. No variables, globs, or pipes: it is a command line for `plymeld`, not a shell.
         */
        fun splitWords(line: String): List<String> {
            val out = ArrayList<String>(); val w = StringBuilder(); var inWord = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\'' -> { inWord = true; val e = line.indexOf('\'', i + 1).let { if (it < 0) line.length else it }; w.append(line, i + 1, e); i = e }
                    c == '"' -> { inWord = true; i++
                        while (i < line.length && line[i] != '"') { if (line[i] == '\\' && i + 1 < line.length && line[i + 1] in "\"\\") i++; w.append(line[i]); i++ } }
                    c == '\\' && i + 1 < line.length -> { inWord = true; w.append(line[i + 1]); i++ }
                    c.isWhitespace() -> { if (inWord) { out += w.toString(); w.clear(); inWord = false } }
                    else -> { inWord = true; w.append(c) }
                }
                i++
            }
            if (inWord) out += w.toString()
            return out
        }
        /** What steps 5 and 6 say while they wait: what unlocks them, so a waiting step is never dimmed without a word. */
        /** Where a refusal ([refusal]) was asked: the plan's actions in the drawer, or the bar's undo and redo (else a setting's path). */
        const val REFUSED_IN_PLAN = "plan"
        const val REFUSED_IN_JOURNAL = "journal"
        /** The gray of layers shown while they are sliced again after an edit ([stale]). */
        const val STALE_RGB = 0x6B7280
        const val SLICE_WAITS = "after the plan is decided"
        /** The fix the refusal of an unloaded filament names (ConfigSetup.notLoaded): every part from one slot, its number. */
        val SLOT_SUGGESTION = Regex("print every part from one with --slot (\\d+)")
        const val SEND_WAITS = "after a slice whose check passes"
        /** The model's see-through strength behind the lines in the Layers tab: enough to place a layer in the shape, not enough to tint it. plymeld's choice. */
        const val GHOST = 0.12f
        /** The machines a config stack defines (the keys under `hardware.machine`), in the order the files give them. */
        fun machinesOf(st: ConfigStack): List<String> =
            st.effective.values.filter { it.path.size > 3 && it.path[0] == "hardware" && it.path[1] == "machine" }.map { it.path[2] }.distinct()
        /**
         * The file a value for one path is written into from the drawer: the plan file when the value in force is one of its
         * lines ([inForceSource], `FILE:LINE` as the stack keeps it: a decision is changed where it is locked, since the
         * plan file, read after the user's own file, would otherwise hide the new value), else the user's own config file;
         * null with neither.
         */
        /** Where a value is written: the file (null: the job has no file of its own yet), and why there when it is not obvious. */
        class Target(val file: Path?, val why: String = "")

        /**
         * Where a UI edit of [path] lands, by what the value is: a decision taken over, its value in force a line of the plan file, in the plan file; a
         * hardware fact in the job file that defines its machine, tool, filament, or plate ([definingFile]); a job preference
         * (`job.`) and anything else in the job's own config file. A value whose line the UI cannot write (bundled, a file
         * outside the job) is overridden in the job's own file, and the reason given.
         */
        fun target(path: String, inForceSource: String?, planFile: Path?, userConfig: Path?, definingFile: Path?, jobFiles: Set<Path>): Target {
            writeTarget(inForceSource, planFile, null)?.let { return Target(it) }   // the plan file's own line
            if (definingFile != null) return Target(definingFile)
            val from = inForceSource?.substringBeforeLast(':')
            val outside = from != null && runCatching { Path.of(from).toAbsolutePath().normalize() }.getOrNull()?.let { it !in jobFiles } ?: true
            val why = when {
                from == null -> ""
                from.startsWith(plymeld.config.ConfigFiles.BUNDLED_SOURCE) -> "the value in force is bundled ($inForceSource): overridden in your job file"
                outside -> "the value in force comes from $inForceSource, not one of the job's files: overridden in your job file"
                else -> ""
            }
            return Target(userConfig, why)
        }

        /** The job config file the window makes when the job has none of its own: `jobs/<job name>-<8 hex>.toml` in [userDataDir]. */
        fun jobFileFor(models: List<String>): Path = planFileFor(models).let { p -> userDataDir().resolve("jobs").resolve(p.fileName.toString().removeSuffix(".plan.toml") + ".toml") }

        fun writeTarget(inForceSource: String?, planFile: Path?, userConfig: Path?): Path? {
            if (planFile != null && inForceSource != null) {
                val file = runCatching { Path.of(inForceSource.substringBeforeLast(':')).toAbsolutePath().normalize() }.getOrNull()
                if (file == planFile.toAbsolutePath().normalize()) return planFile
            }
            return userConfig
        }
        fun mm(v: Double): String = "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
        fun union(parts: List<TriangleSoup>): DoubleArray {
            val bs = parts.filter { it.count > 0 }.map { it.bounds() }
            if (bs.isEmpty()) return DoubleArray(6)
            return DoubleArray(6) { k -> if (k < 3) bs.minOf { it[k] } else bs.maxOf { it[k] } }
        }
        /** Where the window writes a job's g-code: the system's temporary directory, named after the model. */
        fun gcodeFor(modelFile: String): Path = outFor(listOf(modelFile))

        /**
         * Where the window writes the g-code of a job on [models]: the system's temporary directory, named after the job
         * ([jobName]), which is also the name `printer send` uploads it under, so each job reaches the printer under its
         * own models' name.
         */
        fun outFor(models: List<String>): Path {
            val dir = Path.of(System.getProperty("java.io.tmpdir"), "plymeld-ui")
            Files.createDirectories(dir)
            return dir.resolve(jobName(models) + ".gcode")
        }

        /** A job's name: its first model's file name without its extension, "_and_N_more" for more models; "job" with none. */
        fun jobName(models: List<String>): String =
            if (models.isEmpty()) "job" else Path.of(models.first()).fileName.toString().substringBeforeLast('.') + (if (models.size > 1) "_and_${models.size - 1}_more" else "")

        /** The user's data folder for what the window writes and keeps (plan files and their journals): [UserDirs.data]. */
        fun userDataDir(): Path = UserDirs.current().data

        /**
         * The plan file the window keeps for a job on [models]: `plans/<job name>-<8 hex>.plan.toml` in [userDataDir], the hex
         * from the models' absolute paths, so two models of one name in different folders keep separate plans.
         */
        fun planFileFor(models: List<String>): Path {
            val key = models.joinToString("\n") { Path.of(it).toAbsolutePath().normalize().toString() }
            return userDataDir().resolve("plans").resolve(jobName(models) + "-" + "%08x".format(key.hashCode()) + ".plan.toml")
        }

        /** Whether [p] is a plan file: a .toml carrying `plan.source_fingerprint`, as `plymeld plan -o` writes it. */
        fun isPlanFile(p: Path): Boolean =
            Files.isRegularFile(p) && p.toString().endsWith(".toml") && runCatching { Files.readString(p).contains("[plan.source_fingerprint") }.getOrDefault(false)

        /**
         * The config files a job on [models] opens with, from [base] (the job before, or the last job's): files that no longer
         * exist left out (so one missing file does not fail every later start), every plan file left out (it is the
         * plan of the job it was made for: its locks and object keys would apply to another model, and its lines be written
         * by this job), and the plan file the window kept for these models ([planFileFor]) added when there is one. Returns
         * the files and what was left out or added, for the console.
         */
        fun configsFor(models: List<String>, base: List<String>): Pair<List<String>, List<String>> {
            val kept = ArrayList<String>(); val notes = ArrayList<String>()
            for (f in base) {
                val p = Path.of(f)
                when {
                    !Files.isRegularFile(p) -> notes += "config file left out: $f no longer exists"
                    isPlanFile(p) -> notes += "plan file left out: ${p.fileName} is the plan of another job"
                    else -> kept += f
                }
            }
            if (models.isNotEmpty()) planFileFor(models).takeIf { Files.isRegularFile(it) }?.let { kept += it.toString(); notes += "plan file: $it (kept by the window for this model)" }
            return kept to notes
        }
    }
}
