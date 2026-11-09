package plymeld.ui

import plymeld.commands.UpdateFeed
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the stage shows. */
enum class StageTab(val label: String) { MODEL("Model"), PLAN("Plan"), LAYERS("Layers") }

/**
 * The window: the path down the left (six steps, each with its state and what to do there),
 * the stage beside it (the model, later the plan drawn on it, the layers after a slice), the bar above, the console
 * folded under the stage. Every action is one of the commands': what a step's button does, the console shows
 * as the command line it ran.
 */
/**
 * What the window shows, as state the keyboard (Main) and the window share: the stage's tab, the drawer (open, and on
 * the decisions, on one setting's page, on a step's group of settings, or on what can be changed), the console, the
 * finder with what was typed, step 2's choice of machine. Per-viewer convenience only:
 * nothing here decides anything in a print.
 */
class WindowUi(startTab: StageTab = StageTab.MODEL, startWhy: Boolean = false, startConsole: Boolean = false, startFind: String? = null, startSetting: String? = null) {
    val startTab = startTab
    var tab by mutableStateOf(startTab)
    var consoleOpen by mutableStateOf(startConsole)
    var why by mutableStateOf(startWhy || startSetting != null)
    var finderOpen by mutableStateOf(startFind != null)
    var query by mutableStateOf(startFind ?: "")
    /** The drawer's page: a setting's path, `command:` and a command, [GROUP] and a group's name, [CHANGE], or null for the decisions. */
    var page by mutableStateOf(startSetting)
    /** One decision the stage picked (an index into the plan's decisions), or null for all of them. */
    var focus by mutableStateOf<Int?>(null)
    /** The tray's pane. */
    var trayTab by mutableStateOf(TrayTab.CONSOLE)
    /** Step 2's choice of machine ("Another printer"), shown under its links or not. */
    var choosingMachine by mutableStateOf(false)
    /** Step 3's Custom picked: a slider per goal shown, though the weights in force may still match a choice. */
    var customWeights by mutableStateOf(false)
    /** On the rail (a narrow window), the folded step opened by a click (its number), besides the lit one; null for none. */
    var railOpen by mutableStateOf<Int?>(null)

    /**
     * What a step's link does, for the links that open the drawer, the tray, or a choice (the steps call it, and so does
     * `--link WORDS` for a picture): "Turn or move", "Change what is loaded" and "More choices" the drawer on their
     * settings ([stepSettings]), "Change something" the drawer on the decisions that can be locked, "Open the report" the
     * tray on the check's problems, "Another printer" the choice of machine. False for any other words.
     */
    fun follow(link: String): Boolean {
        when (link) {
            "Turn or move" -> openPage(GROUP + "pose")
            "Change what is loaded" -> openPage(GROUP + "loadout")
            "More choices" -> openPage(GROUP + "priorities")
            "Change something" -> openPage(CHANGE)
            "Open the report" -> { trayTab = TrayTab.CHECK; consoleOpen = true }
            "Open the console" -> { trayTab = TrayTab.CONSOLE; consoleOpen = true }
            "Another printer" -> choosingMachine = !choosingMachine
            "Set up a printer" -> openPage(SETUP)
            else -> return false
        }
        return true
    }
    private fun openPage(p: String) { page = p; focus = null; why = true }

    /**
     * A machine picked in step 2's choice: the job opened again on it ([JobState.useMachine]); when the job cannot be read
     * or planned on it, it goes back to the one before and the tray's console opens on why.
     */
    fun pickMachine(j: JobState, key: String, done: () -> Unit = {}) {
        choosingMachine = false
        j.useMachine(key) { ok -> if (!ok) androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { trayTab = TrayTab.CONSOLE; consoleOpen = true }; done() }
    }

    /** Ctrl+K: the finder. */
    fun toggleFinder() { finderOpen = !finderOpen }
    /**
     * The chords, shown beside what they open: Ctrl+K the finder, Ctrl+J the tray, Ctrl+D
     * the drawer, Ctrl+Z and Ctrl+Shift+Z (or Ctrl+Y) undo and redo over the journal. False for any other key.
     */
    fun chord(e: KeyEvent, j: JobState): Boolean {
        if (!e.isCtrlPressed) return false
        when (e.key) {
            Key.K -> toggleFinder()
            Key.J -> consoleOpen = !consoleOpen
            Key.D -> { why = !why; page = null; focus = null }
            Key.Z -> if (e.isShiftPressed) { if (j.canRedo) j.redo() } else { if (j.canUndo) j.undo() }
            Key.Y -> if (j.canRedo) j.redo()
            else -> return false
        }
        return true
    }
    /** Escape closes what is open, the finder first, then the drawer; false when nothing was. */
    fun escape(): Boolean = when {
        finderOpen -> { finderOpen = false; true }
        why -> { why = false; page = null; focus = null; true }
        else -> false
    }

    companion object {
        /** The drawer's page for a step's group of settings: this, then the group's name (`pose`, `loadout`, `priorities`). */
        const val GROUP = "group:"
        /** The drawer's page for step 4's "Change something": the decisions with their lock actions. */
        const val CHANGE = "decisions:change"
        /** The drawer's page for the bar's settings: the window's own preferences. */
        const val SETTINGS = "window:settings"
        /** The drawer's page: the first-use setup ([SetupForm]). */
        const val SETUP = "window:setup"
    }
}

@Composable
fun WindowApp(j: JobState, ui: WindowUi, onOpen: (() -> Unit)? = null, onSlice: (() -> Unit)? = null, onSave: (() -> Unit)? = null, onSend: (() -> Unit)? = null,
              onAddConfig: (() -> Unit)? = null, onDrop: ((List<String>) -> Unit)? = null, onAddModel: (() -> Unit)? = null) {
    var tab by ui::tab
    var consoleOpen by ui::consoleOpen
    var why by ui::why
    var finderOpen by ui::finderOpen
    var query by ui::query
    var page by ui::page
    // a slice just written opens the Layers tab; the Model tab shows the parts alone, the Layers tab the lines through a see-through model
    LaunchedEffect(j.gcode) { if (j.gcode != null && ui.startTab == StageTab.MODEL) tab = StageTab.LAYERS }
    // a g-code opened to look at (JobState.previewGcode) shows in the Layers tab
    LaunchedEffect(j.previewing) { if (j.previewing != null) tab = StageTab.LAYERS }
    // the model stays as a faint ghost behind the lines (0.12: enough to place a layer in the shape, not enough to tint it)
    LaunchedEffect(tab) { j.viewer.usePaths(tab == StageTab.LAYERS); j.viewer.showMeshAt(if (tab == StageTab.LAYERS) JobState.GHOST else 1f) }
    MaterialTheme(colorScheme = (if (Ink.dark) darkColorScheme(primary = Ink.accent, onPrimary = Ink.onAccent, background = Ink.bg, surface = Ink.surface, onSurface = Ink.text) else lightColorScheme(primary = Ink.accent, onPrimary = Ink.onAccent, background = Ink.bg, surface = Ink.surface, onSurface = Ink.text))) {
        Column(Modifier.fillMaxSize().background(Ink.bg)) {
            Bar(j, onConsole = { consoleOpen = !consoleOpen }, onFind = { finderOpen = !finderOpen }, onDrop = onDrop, onSettings = { page = WindowUi.SETTINGS; ui.focus = null; why = true })
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // the folds: under 1280 dp the steps fold to a rail (the lit step's text stays), under
                // 1000 dp the drawer lies over the stage instead of beside it, and on a short window the open tray takes the stage's
                // height; the stage is always first
                val rail = maxWidth < 1280.dp
                val overlay = maxWidth < 1000.dp
                val short = maxHeight < 700.dp
                // the drawer: the plan decision by decision (Why), one decision (a mark on the stage), or one setting's chain (the finder); it closes, it never dialogs
                val drawer: @Composable (Modifier) -> Unit = { m -> Drawer(j, m, page = page, focus = ui.focus, onClose = { why = false; page = null; ui.focus = null }, onDecisions = { page = null; ui.focus = null },
                    onRan = { consoleOpen = true; ui.trayTab = TrayTab.CONSOLE }, onCommand = { line -> page = "command:$line"; ui.focus = null; why = true }) }
                Row(Modifier.fillMaxSize()) {
                    Steps(j, ui, Modifier.width(if (rail) 236.dp else 380.dp).fillMaxHeight(), rail, onOpen = onOpen, onSlice = onSlice, onSave = onSave, onSend = onSend, onAddConfig = onAddConfig, onAddModel = onAddModel,
                        onShowPlan = { tab = StageTab.PLAN }, onShowLayers = { tab = StageTab.LAYERS }, onWhy = { why = !why; page = null; ui.focus = null }, why = why)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            // models and config files dropped on the stage open or join the job
                            Stage(j, tab, (if (consoleOpen && short) Modifier.height(160.dp) else Modifier.weight(1f)).fillMaxWidth().dropFiles(onDrop), onTab = { tab = it }, onPick = { i -> ui.focus = i; page = null; why = true },
                                onOpen = onOpen, onCalibrate = { page = "command:plymeld calibrate"; ui.focus = null; why = true })
                            if (consoleOpen) Tray(j, ui.trayTab, onTab = { ui.trayTab = it }, onLayer = { l -> tab = StageTab.LAYERS; j.viewer.setLayers(0, l) },
                                modifier = (if (short) Modifier.weight(1f) else Modifier.height(240.dp)).fillMaxWidth())
                        }
                        if (why && overlay) drawer(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(400.dp))
                    }
                    if (why && !overlay) drawer(Modifier.width(460.dp).fillMaxHeight())
                }
                // the finder over everything: a hit opens where the thing lives
                if (finderOpen) FinderBox(j, query, onQuery = { query = it }, onClose = { finderOpen = false }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) { h ->
                    when (h.kind) {
                        Finder.Kind.SETTING -> { page = h.key; ui.focus = null; why = true }
                        // that decision, not all of them (and not a focus left from an earlier pick)
                        Finder.Kind.DECISION -> { page = null; ui.focus = j.marks?.decisions?.indexOfFirst { it.step == h.key }?.takeIf { it >= 0 }; why = true }
                        Finder.Kind.COMMAND -> { page = "command:" + h.key; ui.focus = null; why = true }
                        Finder.Kind.PROBLEM -> { ui.trayTab = TrayTab.CHECK; consoleOpen = true }
                        Finder.Kind.DOC -> j.openDoc(h.key)
                        // a part: the decisions of the object it belongs to (its layers, its support), where its print is decided
                        Finder.Kind.PART -> {
                            val obj = h.key.substringBefore('/')
                            j.note("part ${h.key}")
                            page = null; ui.focus = j.marks?.objectOf?.entries?.firstOrNull { it.value == obj }?.key; why = true
                        }
                    }
                    finderOpen = false
                }
            }
        }
    }
}

/**
 * The update Velopack's feed offers (Updates.kt), checked once as the window opens when the user has turned checks on
 * (`plymeld updates --set notify|install`, or the window's settings; off by default): shown in the bar as
 * "⬆ VERSION · install" and installed on the click (`install`: installed at once). The window exits for the updater,
 * which starts the new version.
 */
object UpdateState {
    var available by mutableStateOf<Updates.Available?>(null)
    var status by mutableStateOf("")
    private var install: Updates.Install? = null
    private var started = false
    fun checkOnStart() {
        if (started) return; started = true
        Thread {
            try {
                val pref = UpdateFeed.Pref.load()
                if (pref.mode == UpdateFeed.Pref.Mode.OFF) return@Thread
                val i = Updates.install() ?: return@Thread; install = i
                val feed = pref.feed ?: return@Thread
                val a = Updates.check(feed, i) ?: return@Thread
                available = a
                if (pref.mode == UpdateFeed.Pref.Mode.INSTALL) installNow()
            } catch (e: Exception) { status = "update check failed: ${e.message}" }
        }.apply { isDaemon = true; name = "update check" }.start()
    }
    fun installNow() {
        val a = available ?: return; val i = install ?: return
        if (status.startsWith("downloading") || status == "installing…") return
        Thread {
            try {
                val pkg = Updates.download(a, i) { done, total -> status = if (total > 0) "downloading ${100 * done / total} %" else "downloading" }
                status = "installing…"
                Updates.apply(i, pkg)
                kotlin.system.exitProcess(0)
            } catch (e: Exception) { status = "update failed: ${e.message}" }
        }.apply { name = "update install" }.start()
    }
}

@Composable
private fun Bar(j: JobState, onConsole: () -> Unit, onFind: () -> Unit, onDrop: ((List<String>) -> Unit)? = null, onSettings: () -> Unit = {}) {
    LaunchedEffect(Unit) { UpdateState.checkOnStart() }
    // files dropped on the bar join the job as on the stage
    Row(Modifier.fillMaxWidth().height(48.dp).background(Ink.surface).dropFiles(onDrop).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(18.dp)) { with(Logo) { drawMark(Ink.text) } }   // the mark (Logo.kt) in the text's ink
            Text("plymeld", color = Ink.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        // one line, cut with an ellipsis: several models or a long name never push the icons out
        Text(j.title, color = Ink.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(3f, fill = false))
        val state = when {
            j.running != null -> j.running!!.what + (if (j.running!!.total > 0) " ${100 * j.running!!.done / j.running!!.total} %" else "")
            j.send.status == JobState.Status.NOW -> "ready"
            j.slice.status == JobState.Status.BUSY -> "slicing"
            j.plan.status == JobState.Status.BUSY -> "planning"
            j.plan.status == JobState.Status.DONE -> "planned"
            else -> ""
        }
        if (state.isNotEmpty()) Text("· $state", color = Ink.muted, fontSize = 14.sp)
        // what the last edit re-ran and how long it took
        j.rerun?.let { Text("· $it", color = Ink.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
        // the loadout mismatch: the printer, when asked, reports another spool than the config says
        if (j.loadoutRows.any { it.second }) Text("· the ${j.printerName} has other filament loaded", color = Ink.hot, fontSize = 13.sp, maxLines = 1, modifier = Modifier.clickable { j.note("loadout: " + j.loadoutRows.filter { it.second }.joinToString(" | ") { it.first }) })
        // an undo or redo refused (the file changed since, nothing to take back): said beside the arrows, not only in the console
        j.refusal?.takeIf { it.first == JobState.REFUSED_IN_JOURNAL }?.let { Text(it.second, color = Ink.warn, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
        // an update on offer (UpdateState): the version, then what is happening to it; a click installs
        UpdateState.available?.let { a ->
            Text("⬆ ${a.release.version} · " + UpdateState.status.ifEmpty { "install" }, color = Ink.accent, fontSize = 13.sp,
                modifier = Modifier.clickable { UpdateState.installNow() }.padding(horizontal = 4.dp))
        } ?: UpdateState.status.takeIf { it.isNotEmpty() }?.let { Text(it, color = Ink.faint, fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(360.dp).height(30.dp).background(Ink.bg, RoundedCornerShape(8.dp)).border(1.dp, Ink.line, RoundedCornerShape(8.dp)).clickable { onFind() }.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("⌕ find a setting, a command, a part…", color = Ink.faint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("Ctrl K", color = Ink.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        // undo and redo over the journal of edits
        // the chords beside what they do: Ctrl+Z / Ctrl+Shift+Z undo and redo, Ctrl+J the tray, Ctrl+D the drawer, Ctrl+K the finder
        Chorded("↶", "Ctrl Z", if (j.canUndo) Ink.text else Ink.faint, j.canUndo) { j.undo() }
        Chorded("↷", "Ctrl Y", if (j.canRedo) Ink.text else Ink.faint, j.canRedo) { j.redo() }
        Chorded("›_", "Ctrl J", Ink.muted, true, mono = true) { onConsole() }
        // the window's own settings (the bar's "settings"), in the drawer
        Text("⚙", color = Ink.faint, fontSize = 16.sp, modifier = Modifier.clickable { onSettings() }.padding(horizontal = 4.dp))
        // the theme: dark or light; the stage follows
        Text(if (Ink.dark) "☀" else "☾", color = Ink.faint, fontSize = 16.sp, modifier = Modifier.clickable { Ink.toggle(); j.viewer.vp.gl { dark = Ink.dark }; j.viewer.requestFrame() }.padding(horizontal = 4.dp))
    }
}

/** An icon in the bar with its chord beside it, small and faint. */
@Composable
private fun Chorded(icon: String, chord: String, color: Color, enabled: Boolean, mono: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.clickable(enabled = enabled) { onClick() }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(icon, color = color, fontSize = if (mono) 15.sp else 16.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        Text(chord, color = Ink.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Steps(j: JobState, ui: WindowUi, modifier: Modifier, rail: Boolean, onOpen: (() -> Unit)?, onSlice: (() -> Unit)?, onSave: (() -> Unit)?, onSend: (() -> Unit)?, onAddConfig: (() -> Unit)?, onAddModel: (() -> Unit)? = null,
                  onShowPlan: () -> Unit, onShowLayers: () -> Unit, onWhy: () -> Unit, why: Boolean) {
    // a link that opens the drawer, the tray, or a choice: WindowUi.follow, the same path `--link WORDS` takes
    fun link(words: String): Pair<String, () -> Unit> = words to { ui.follow(words) }
    // on the rail a folded step opens on a click (the lit one is always open): every step's actions stay reachable
    val open: (Int) -> Boolean = { n -> ui.railOpen == n }
    val expand: (Int) -> Unit = { n -> ui.railOpen = if (ui.railOpen == n) null else n }
    Column(modifier.background(Ink.surface).verticalScroll(rememberScrollState()).padding(if (rail) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Step(1, "Model", j.model, rail, open(1), { expand(1) }) {
            // "Add a model…" joins the plate (JobState.addModel: the planner arranges it again, or refuses); so does a drop;
            // "Turn or move" opens the pose and placement settings in the drawer
            Actions(listOf((if (j.modelFilesOpen.isEmpty()) "Open…" else "Open another…") to onOpen, "Add a model…" to onAddModel, link("Turn or move")),
                mapOf("Open another…" to j.commandLine("plan").substringBefore(" -m ") + " -m MODEL", "Add a model…" to j.commandLine("plan") + " -m MODEL"))
            // a part on a filament the loadout lacks: print every part from the slot the refusal names (--slot N); once
            // printed from one slot, the way back to the model's own (JobState.useSlot)
            j.slotOffer?.let { n -> Actions(listOf("Print every part from slot $n" to { j.useSlot(n) }), mapOf("Print every part from slot $n" to j.commandLine("plan", "--slot", n.toString()))) }
            j.slot?.let { n ->
                Text("every part printed from slot $n (--slot $n)", color = Ink.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Actions(listOf("Print in the model's filaments" to { j.useSlot(null) }))
            }
            // the models on the plate, each with × to take it off (JobState.removeModel)
            if (j.modelFilesOpen.size > 1) FileRows(j.modelFilesOpen, "take this model off the plate") { j.removeModel(it) }
        }
        Step(2, "Printer", j.printer, rail, open(2), { expand(2) }) {
            if (j.loadout.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                for (s in j.loadout) Row(verticalAlignment = Alignment.CenterVertically) { Swatch(s.rgb); Spacer(Modifier.width(4.dp)); Text(s.filament, color = Ink.text, fontSize = 13.sp) }
            }
            // what the printer itself says, when asked: its state, the loadout against the config, a frame
            for (line in j.printerLive) Text(line, color = Ink.muted, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
            for ((line, off) in j.loadoutRows) Text(line, color = if (off) Ink.hot else Ink.muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
            j.cameraFrame?.let { androidx.compose.foundation.Image(it, "the printer's camera", Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))) }
            // `printer status` needs only the config files, not a model, so it is not dimmed in the empty window
            // no config yet: the first-use setup (plymeld init's questions as a form in the drawer) leads
            if (j.openConfigFiles.isEmpty()) Actions(listOf(link("Set up a printer")), mapOf("Set up a printer" to "plymeld init"))
            Actions(listOf((if (j.askingPrinter) "asking…" else "Ask the printer") to (if (j.askingPrinter || j.openConfigFiles.isEmpty()) null else { { j.askPrinter() } }),
                "Add a config file…" to onAddConfig, link("Another printer"), link("Change what is loaded")),
                mapOf("Ask the printer" to j.printerLine("printer", "status") + " ; " + j.printerLine("printer", "loadout") + " ; " + j.printerLine("printer", "camera", "-o", "FRAME.jpg"),
                    "Add a config file…" to j.commandLine("plan") + " -cf FILE"))
            // the job's config files, each with × to take it out (JobState.removeConfig), the plan file marked
            if (j.openConfigFiles.isNotEmpty()) FileRows(j.openConfigFiles, "take this config file out of the job", mark = { if (j.planFile?.toString() == it) "plan" else null }) { j.removeConfig(it) }
            // "Another printer": the machines the config files define, a pick opening the job again on it; with no other,
            // what would add one
            if (ui.choosingMachine) {
                val now = j.config?.machine
                val others = j.machines.filter { it != now }
                Text(if (others.isEmpty()) (if (j.machines.isEmpty()) "No machine is defined yet" else "The config defines only ${j.machines.joinToString()}") +
                    ": another printer is another hardware.machine.<key> table, in a config file of yours or one added here."
                    else "Print on" + (now?.let { " (now $it)" } ?: "") + ":", color = Ink.muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
                Actions(if (others.isEmpty()) listOf("Add a config file…" to onAddConfig) else others.map { m -> j.machineLabel(m) to { ui.pickMachine(j, m) } })
            }
        }
        Step(3, "What matters", j.matters, rail, open(3), { expand(3) }) {
            // Quality / Balanced / Speed / Custom (JobState.Preset): each choice writes every
            // goal's weight into the job's own file; Custom shows a slider per goal. The one in force marked: Custom when the
            // weights match no choice, or when it was picked
            if (j.config != null) {
                val file = j.targetFor("job.priorities.quality").file?.fileName ?: "JOB.toml"
                val custom = ui.customWeights || j.preset == null
                // eight choices: they wrap in the side panel (a choice per goal)
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in JobState.Preset.entries) {
                        val on = !custom && j.preset == p
                        Captioned("plymeld edit $file " + p.weights.entries.joinToString(" ") { (g, n) -> "job.priorities.${g.id}=$n" }) {
                            Choice(p.label, on) { ui.customWeights = false; if (j.preset != p) j.usePreset(p) }
                        }
                    }
                    Captioned("a weight from 0 to ${j.maxGoalWeight} for each goal") { Choice("Custom", custom) { ui.customWeights = true } }
                }
                Text(if (custom) "each goal's weight; weights are relative (0 = not counted)" else "Balanced: every goal at 1 · the others: that goal at 5, the rest at 1",
                    color = Ink.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                if (custom) j.goalWeights?.let { weights -> for ((g, w) in weights) GoalSlider(g, w, j.maxGoalWeight, "plymeld edit $file job.priorities.${g.id}=N") { n -> j.setGoalWeight(g, n) } }
            }
            // the priorities' settings in the drawer, every goal's weight
            Actions(listOf(link("More choices")))
        }
        Step(4, "Plan", j.plan, rail, open(4), { expand(4) }) {
            // "Change something": the drawer on the decisions that can be locked from it, with their actions
            if (j.plan.status == JobState.Status.DONE) Actions(listOf("Show it on the model" to onShowPlan, link("Change something"), (if (why) "Close why" else "Why") to onWhy))
            // refused: the ways out as buttons: how the objects sit, the machine,
            // what matters, a locked decision freed, and the console with the whole refusal
            if (j.plan.status == JobState.Status.FAILED) Actions(listOf(link("Turn or move"), link("Another printer"), link("More choices"), link("Change something"), link("Open the console")))
        }
        Step(5, "Slice & check", j.slice, rail, open(5), { expand(5) }) {
            // what is running, counted in layers (Progress): a bar that moves with real work, and Cancel
            j.running?.let { r -> ProgressRow(r, onCancel = { j.cancel() }) }
            when (j.slice.status) {
                JobState.Status.NOW -> Row(Modifier.padding(top = 8.dp)) { Primary("Slice & check", onSlice, caption = j.commandLine("slice", "-o", j.sliceOut().toString())) }
                // "Open the report": the tray on the check's problems; after a refusal too, when the check listed some
                JobState.Status.DONE -> Actions(listOf("Preview the layers" to onShowLayers, link("Open the report")))
                JobState.Status.FAILED -> if (j.checkProblems.isNotEmpty()) Actions(listOf(link("Open the report")))
                else -> {}
            }
        }
        Step(6, "Send", j.send, rail, open(6), { expand(6) }) {
            // the buttons as they will appear: Send to the printer first, saving the file beside it; after a
            // send too, to send again or keep the file
            if (j.send.status == JobState.Status.NOW || j.send.status == JobState.Status.FAILED || j.send.status == JobState.Status.DONE) Column(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Primary(if (j.send.status == JobState.Status.NOW) "Send to the ${j.printerName}" else "Send again", onSend, Modifier.fillMaxWidth(),
                    caption = j.gcode?.let { j.printerLine("printer", "send", it.toString()) })
                // the printer still shows its last print finished: the user's word that the bed is clear, then Send again
                if (j.sendNeedsClear) Secondary("The bed is clear: tell the ${j.printerName}", { j.clearBed() }, Modifier.fillMaxWidth())
                Secondary("Save the g-code…", onSave, Modifier.fillMaxWidth())
            }
        }
    }
}

/** One of step 3's choices: a pill, filled when [on]. */
@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) Ink.onAccent else Ink.text, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (on) Ink.accent else Ink.surface3).clickable(enabled = !on, onClick = onClick).padding(8.dp, 4.dp))
}

/**
 * Custom's slider for goal [g] (0..[max], whole numbers): it follows the drag and writes the weight once, when let go
 * ([onSet]: one `plymeld edit`, one undo), not at every step. A weight over [max] set in a file shows at the end of the
 * track with its own number.
 */
@Composable
private fun GoalSlider(g: plymeld.core.decide.Goal, weight: Double, max: Int, caption: String, onSet: (Int) -> Unit) {
    var drag by remember(g, weight) { mutableStateOf(weight.toFloat().coerceIn(0f, max.toFloat())) }
    var moved by remember(g, weight) { mutableStateOf(false) }
    Captioned(caption) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(g.id, color = Ink.muted, fontSize = 12.sp, modifier = Modifier.width(76.dp))
            Slider(value = drag, valueRange = 0f..max.toFloat(), onValueChange = { drag = Math.round(it).toFloat(); moved = true },
                onValueChangeFinished = { if (moved) { moved = false; onSet(Math.round(drag)) } },
                colors = SliderDefaults.colors(thumbColor = Ink.accent, activeTrackColor = Ink.accent, inactiveTrackColor = Ink.surface3), modifier = Modifier.weight(1f).height(26.dp))
            val shown = if (moved) Math.round(drag).toString() else if (weight == Math.floor(weight)) weight.toLong().toString() else weight.toString()
            Text(shown, color = Ink.text, fontSize = 12.sp, modifier = Modifier.width(36.dp).padding(start = 6.dp))
        }
    }
}

/** A step's files, one row each: the file's name (its folder on hover is not shown yet), a word for its kind, and × to remove it. */
@Composable
private fun FileRows(files: List<String>, removeWhat: String, mark: (String) -> String? = { null }, onRemove: (String) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        for (f in files) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(java.nio.file.Path.of(f).fileName.toString(), color = Ink.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            mark(f)?.let { Text(it, color = Ink.faint, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp)) }
            Text("×", color = Ink.faint, fontSize = 14.sp, modifier = Modifier.clickable(onClickLabel = removeWhat) { onRemove(f) }.padding(horizontal = 6.dp))
        }
    }
}

/** A step's mark: its number, or its state (done, failed). */
@Composable
private fun StepMark(n: Int, v: JobState.StepView) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(when (v.status) {
        JobState.Status.DONE -> Ink.okTint; JobState.Status.FAILED -> Ink.warnTint; JobState.Status.NOW, JobState.Status.BUSY -> Ink.accent; else -> Ink.surface3
    }), contentAlignment = Alignment.Center) {
        Text(when (v.status) { JobState.Status.DONE -> "✓"; JobState.Status.FAILED -> "!"; else -> "$n" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = when (v.status) { JobState.Status.DONE -> Ink.ok; JobState.Status.FAILED -> Ink.warn; JobState.Status.NOW, JobState.Status.BUSY -> Ink.onAccent; else -> Ink.muted })
    }
}

@Composable
private fun Step(n: Int, title: String, v: JobState.StepView, rail: Boolean, opened: Boolean = false, onExpand: (() -> Unit)? = null, extra: @Composable ColumnScope.() -> Unit) {
    val now = v.status == JobState.Status.NOW || v.status == JobState.Status.BUSY
    val dim = v.status == JobState.Status.WAIT
    val shape = RoundedCornerShape(12.dp)
    // the rail (a narrow window): every step but the lit one, and one opened by a click, is its mark and its name; the
    // click opens it (and a click on its title folds it again)
    if (rail && !now && !opened) {
        Row(Modifier.fillMaxWidth().background(Ink.surface2, shape).border(1.dp, Ink.line, shape).clickable(enabled = onExpand != null) { onExpand?.invoke() }.padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            StepMark(n, v)
            Spacer(Modifier.width(10.dp))
            Text(title.uppercase(), color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, softWrap = false, modifier = Modifier.weight(1f))
            Text("›", color = Ink.faint, fontSize = 13.sp)
        }
        return
    }
    Row(Modifier.fillMaxWidth().background(Ink.surface2, shape).border(if (now) 2.dp else 1.dp, if (now) Ink.accent else Ink.line, shape).padding(12.dp, 12.dp, 14.dp, 12.dp)) {
        StepMark(n, v)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = rail && opened && onExpand != null) { onExpand?.invoke() }, verticalAlignment = Alignment.CenterVertically) {
                Text("$n · ${title.uppercase()}", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, softWrap = false)
                v.note?.let { Text(it, color = Ink.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 10.dp)) }
            }
            for ((i, line) in v.lines.withIndex()) Text(line, color = if (dim) Ink.muted else if (i == 0) Ink.text else Ink.muted, fontSize = if (i == 0) 14.sp else 13.sp,
                fontWeight = if (i == 0 && !dim) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 19.sp, modifier = Modifier.padding(top = if (i == 0) 3.dp else 1.dp))
            extra()
        }
    }
}

/** The bar of a run in progress: the stage's name, its count (layers done of total, when it has one), and Cancel. */
@Composable
private fun ProgressRow(r: JobState.Running, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${r.what}: ${r.stage}" + (if (r.total > 0) " ${r.done} / ${r.total}" else ""), color = Ink.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("Cancel", color = Ink.hot, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onCancel() }.padding(start = 10.dp))
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.surface3)) {
            if (r.total > 0) Box(Modifier.fillMaxWidth(fraction = (r.done.toFloat() / r.total).coerceIn(0f, 1f)).fillMaxHeight().background(Ink.accent))
            else Box(Modifier.fillMaxWidth(0.15f).fillMaxHeight().background(Ink.accent.copy(alpha = 0.5f)))
        }
    }
}

/**
 * A step's links in the accent; one that cannot act at the moment (the printer being asked, no config open) is dimmed,
 * its words or its step's lines saying why.
 */
@Composable
private fun Actions(items: List<Pair<String, (() -> Unit)?>>, captions: Map<String, String> = emptyMap()) {
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((label, act) in items) Captioned(captions[label]) {
            Text(label, color = if (act != null) Ink.accent else Ink.faint, fontSize = 13.sp,
                modifier = if (act != null) Modifier.clickable { act() } else Modifier)
        }
    }
}

/**
 * The command line as a caption (every button's command appears when it is hovered or focused): [caption], when there
 * is one, shown in monospace under the pointer after a short hover. The stage and the drawer stay free of it otherwise.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Captioned(caption: String?, content: @Composable () -> Unit) {
    if (caption == null) { content(); return }
    androidx.compose.foundation.TooltipArea(tooltip = {
        Box(Modifier.background(Ink.glass, RoundedCornerShape(6.dp)).border(1.dp, Ink.line, RoundedCornerShape(6.dp)).padding(10.dp, 6.dp).widthIn(max = 560.dp)) {
            Text("$ $caption", color = Ink.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
        }
    }, delayMillis = 500, tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = androidx.compose.ui.unit.DpOffset(12.dp, 16.dp))) { content() }
}

@Composable
private fun Primary(label: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, caption: String? = null) {
    Captioned(caption) {
        Button(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = Ink.accent, contentColor = Ink.onAccent), shape = RoundedCornerShape(9.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Secondary(label: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, caption: String? = null) {
    Captioned(caption) {
        OutlinedButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = modifier, shape = RoundedCornerShape(9.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.text), border = androidx.compose.foundation.BorderStroke(1.dp, Ink.line),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Swatch(rgb: Int) = Box(Modifier.size(11.dp).background(Color(0xFF000000 or rgb.toLong()), RoundedCornerShape(3.dp)).border(1.dp, Ink.swatchLine, RoundedCornerShape(3.dp)))

@Composable
private fun EmptyStage(j: JobState, modifier: Modifier, onOpen: (() -> Unit)?, onCalibrate: (() -> Unit)?) {
    Column(modifier.widthIn(max = 520.dp).background(Ink.glass, RoundedCornerShape(14.dp)).border(1.dp, Ink.line, RoundedCornerShape(14.dp)).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Drop a model here (.stl, .3mf), a config file (.toml) or a sliced file (.gcode)", color = Ink.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Primary("Open…", onOpen)
            Secondary("Calibrate…", onCalibrate, caption = "plymeld calibrate")
        }
        if (j.recent.isNotEmpty()) {
            Text("RECENT JOBS", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 8.dp))
            for (r in j.recent) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { j.openRecent(r) }.padding(8.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.models.joinToString(", ") { java.nio.file.Path.of(it).fileName.toString() }, color = Ink.accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                r.machine?.let { Text(it, color = Ink.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp)) }
                Text("${r.configFiles.size} config file${if (r.configFiles.size == 1) "" else "s"}", color = Ink.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Stage(j: JobState, tab: StageTab, modifier: Modifier, onTab: (StageTab) -> Unit, onPick: (Int) -> Unit = {}, onOpen: (() -> Unit)? = null, onCalibrate: (() -> Unit)? = null) {
    Box(modifier.background(Ink.bg)) {
        // a click picks (off the UI thread: every triangle or line in view is projected): on the Model tab the part under it,
        // the drawer opening on its object's decision; on the Layers tab the nearest line, the view going to that move
        ViewArea(j.viewer, Modifier.fillMaxSize(), onTap = when (tab) {
            StageTab.MODEL -> { pos, size -> Thread({ j.pickDecisionAt(pos.x, pos.y, size.width, size.height)?.let { androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { onPick(it) } } }, "plymeld-pick").apply { isDaemon = true }.start() }
            StageTab.LAYERS -> { pos, size -> Thread({ j.pickMoveAt(pos.x, pos.y, size.width, size.height) }, "plymeld-pick").apply { isDaemon = true }.start() }
            else -> null
        })
        // the tabs: what the stage shows
        Row(Modifier.align(Alignment.TopStart).padding(16.dp, 14.dp).background(Ink.glass, RoundedCornerShape(9.dp)).border(1.dp, Ink.line, RoundedCornerShape(9.dp)).padding(2.dp)) {
            for (t in StageTab.entries) {
                val on = t == tab
                val can = t != StageTab.LAYERS || j.gcode != null || j.previewing != null || j.stale
                Text(t.label, color = if (on) Ink.text else if (can) Ink.muted else Ink.faint, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (on) Ink.surface3 else Color.Transparent).clickable(enabled = can) { onTab(t) }.padding(14.dp, 6.dp))
            }
        }
        // the Plan tab: the plan drawn on the model (support spots as boxes, pauses as planes, the bands on a height ruler)
        if (tab == StageTab.PLAN) PlanOverlay(j, Modifier.fillMaxSize(), onPick = onPick)
        // the view cube: which way the view faces, a click on a face turns to it; under it the view's own tools
        ViewCube(j.viewer, Modifier.align(Alignment.TopEnd).padding(16.dp, 60.dp).size(100.dp))
        ViewTools(j.viewer, Modifier.align(Alignment.TopEnd).padding(16.dp, 168.dp))
        if (tab == StageTab.PLAN && j.marks == null) Text("nothing decided yet",
            Modifier.align(Alignment.TopCenter).padding(top = 60.dp).background(Ink.glass, RoundedCornerShape(8.dp)).padding(10.dp, 6.dp), color = Ink.muted, fontSize = 12.sp)
        // the Layers tab: the layers, the moves, the coloring, and its legend, under the view
        if (tab == StageTab.LAYERS && (j.gcode != null || j.previewing != null || j.stale)) LayersBar(j, Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth())
        // first open (a drop target with Open, Calibrate, and the recent jobs)
        if (j.modelFilesOpen.isEmpty() && j.previewing == null) EmptyStage(j, Modifier.align(Alignment.Center), onOpen, onCalibrate)
        if (j.loadout.isNotEmpty() && tab != StageTab.LAYERS) Row(Modifier.align(Alignment.BottomStart).padding(16.dp).background(Ink.glass, RoundedCornerShape(9.dp)).border(1.dp, Ink.line, RoundedCornerShape(9.dp)).padding(12.dp, 7.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            for (s in j.loadout) Row(verticalAlignment = Alignment.CenterVertically) { Swatch(s.rgb); Spacer(Modifier.width(5.dp)); Text("${s.filament} · ${s.tool}", color = Ink.muted, fontSize = 12.sp) }
        }
        j.viewer.busy?.let { Text(it, Modifier.align(Alignment.Center).background(Ink.glass, RoundedCornerShape(8.dp)).padding(14.dp, 8.dp), color = Ink.text, fontSize = 14.sp) }
        if (tab != StageTab.LAYERS) Text("left-drag: turn · right-drag: move · wheel: zoom", Modifier.align(Alignment.BottomEnd).padding(16.dp), color = Ink.faint, fontSize = 11.sp)
    }
}

/** The view's tools in the window's style: the named views, fit, the projection; the keys are the viewer's. */
@Composable
private fun ViewTools(s: ViewerState, modifier: Modifier) {
    Row(modifier.background(Ink.glass, RoundedCornerShape(9.dp)).border(1.dp, Ink.line, RoundedCornerShape(9.dp)).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (v in listOf(Camera.View.ISO, Camera.View.TOP, Camera.View.FRONT, Camera.View.RIGHT)) ViewTool(v.label.lowercase(), false) { s.preset(v) }
        ViewTool("fit", false) { s.fit() }
        Text("·", color = Ink.faint, fontSize = 12.sp)
        ViewTool(if (s.orthographic) "ortho" else "persp", s.orthographic) { s.useOrthographic(!s.orthographic) }
    }
}

@Composable
private fun ViewTool(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) Ink.text else Ink.muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (on) Ink.surface3 else Color.Transparent).clickable { onClick() }.padding(9.dp, 5.dp))
}

/** The history: the job's journal of edits, each applied or undone, with undo and redo; a pane of the tray. */
@Composable
internal fun History(j: JobState, modifier: Modifier) {
    val jn = j.journal
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("HISTORY", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            Text("undo", color = if (j.canUndo) Ink.accent else Ink.faint, fontSize = 12.sp, modifier = Modifier.clickable(enabled = j.canUndo) { j.undo() }.padding(horizontal = 6.dp))
            Text("redo", color = if (j.canRedo) Ink.accent else Ink.faint, fontSize = 12.sp, modifier = Modifier.clickable(enabled = j.canRedo) { j.redo() }.padding(horizontal = 6.dp))
        }
        j.journalPath?.let { Text(it.fileName.toString(), color = Ink.faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
            if (jn == null || jn.entries.isEmpty()) Text("no edits yet", color = Ink.faint, fontSize = 11.sp)
            else for ((i, e) in jn.entries.withIndex()) {
                val applied = i < jn.done
                Text("${i + 1}. ${if (applied) "applied" else "undone"}  ${e.at.substringAfter('T').substringBefore('-').substringBefore('+')}  ${e.command.substringAfter("plymeld edit ").substringAfter(' ')}",
                    color = if (applied) Ink.text else Ink.faint, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp), maxLines = 1)
            }
        }
    }
}
