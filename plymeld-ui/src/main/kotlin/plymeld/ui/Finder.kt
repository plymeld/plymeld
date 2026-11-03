package plymeld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plymeld.commands.CommandModel
import plymeld.config.Schema

/**
 * The finder: one box that finds anything by what was typed: a command (the command
 * model, `plymeld commands`), a setting (the config's own entries with their value and source, and the schema's paths
 * not set), a part of the model, a decision of the plan. A hit opens where the thing lives: a setting in the drawer
 * with its chain, a decision in the drawer, a command as its line in the console, a part named in the console.
 * The matching is plain: every word typed must appear in the hit's words, in any order, case ignored.
 */
object Finder {
    enum class Kind(val label: String) { COMMAND("command"), SETTING("setting"), PART("part"), DECISION("decision"), PROBLEM("problem"), DOC("docs") }
    class Hit(val kind: Kind, val title: String, val detail: String, val key: String, val entry: plymeld.config.ConfigFiles.Entry? = null)

    /**
     * The user docs' pages (the build's `plymeld-docs-index.txt`: the online base, then each page's path and title), read
     * once; a hit opens the page online.
     */
    object Docs {
        class Page(val path: String, val title: String)
        private val index: Pair<String, List<Page>> by lazy {
            val text = Finder::class.java.getResourceAsStream("/plymeld-docs-index.txt")?.bufferedReader()?.readText() ?: ""
            val lines = text.lines().filter { it.isNotBlank() }
            (lines.firstOrNull { it.startsWith("base=") }?.removePrefix("base=") ?: "") to
                lines.filter { '\t' in it }.map { Page(it.substringBefore('\t'), it.substringAfter('\t')) }
        }
        val base: String get() = index.first
        val pages: List<Page> get() = index.second
        fun url(path: String): String = base + path
    }

    /** The command model, read once (it walks the Clikt tree). */
    val commands: List<CommandModel.Command> by lazy { CommandModel.read().root.all() }
    /** The other slicer's names for plymeld's paths (the import map), so a user finds `initial_layer_speed`. */
    private val otherNames: Map<String, List<String>> by lazy { plymeld.config.OrcaImport.namesOf() }
    private fun others(path: String): String = otherNames[path]?.let { " · also: ${it.joinToString(", ")}" } ?: ""
    private fun othersOfKey(key: String): String = others(Schema.find(key.split('.'))?.path ?: key)

    fun search(query: String, j: JobState, limit: Int = 14): List<Hit> = search(query, j.config, j.partNames, j.marks?.decisions ?: emptyList(), limit, j.checkProblems)

    /** The search on its inputs (the job's config, its parts, the plan's decisions): what the window and the parity test run. */
    fun search(query: String, config: plymeld.config.Config?, partNames: List<Pair<String, String>>, decisions: List<plymeld.core.decide.Decision<*>>, limit: Int = 14, problems: List<String> = emptyList()): List<Hit> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        fun matches(vararg texts: String): Boolean { val all = texts.joinToString(" ").lowercase(); return words.all { it in all } }
        val out = ArrayList<Hit>()
        // settings set in this job's config, value and source
        config?.let { c -> for (e in c.values.values) if (matches(e.key, e.value.toString(), Schema.find(e.path)?.meaning ?: "", othersOfKey(e.key)))
            out += Hit(Kind.SETTING, e.key, "= ${show(e.value)}  ${e.source}${othersOfKey(e.key)}", e.key, e) }
        // the schema's paths, for what is not set (a path with <key> is shown as declared)
        val set = config?.values?.keys ?: emptySet()
        for (d in Schema.decls) if (d.path !in set && matches(d.path, d.meaning, others(d.path))) out += Hit(Kind.SETTING, d.path, "${d.type.label} · ${d.meaning}${others(d.path)}", d.path)
        for (c in commands) if (matches(c.line, c.help)) out += Hit(Kind.COMMAND, c.line, c.help.take(140), c.line)
        for ((obj, part) in partNames) if (matches(part, obj)) out += Hit(Kind.PART, part, "a part of $obj", "$obj/$part")
        decisions.forEach { d -> if (matches(d.step, d.why)) out += Hit(Kind.DECISION, d.step, d.why.take(140), d.step) }
        // the last check's problems: a hit opens the tray's Check pane
        for (pb in problems) if (matches(pb, "problem check")) out += Hit(Kind.PROBLEM, pb.trim().take(90), "a problem the check found", pb)
        // the user docs' pages by title and path: a hit opens the page online
        for (pg in Docs.pages) if (matches(pg.title, pg.path.replace('-', ' '), "docs")) out += Hit(Kind.DOC, pg.title, pg.path, pg.path)
        // the hits whose own name holds the words first (all of them, then most of them), then the ones only described by
        // them; settings before commands; the shortest name first among equals
        fun inTitle(h: Hit) = words.count { it in h.title.lowercase().replace('_', ' ') || it in h.title.lowercase() }
        return out.sortedWith(compareBy({ -inTitle(it) }, { it.kind.ordinal }, { it.title.length })).take(limit)
    }

    private fun show(v: Any): String = when (v) { is String -> "\"$v\""; is List<*> -> v.joinToString(", ", "[", "]"); else -> v.toString() }
}

/** The finder's box and results, over the stage. [query] and [onQuery] are the window's; a hit calls [onPick]. */
@Composable
fun FinderBox(j: JobState, query: String, onQuery: (String) -> Unit, onClose: () -> Unit, modifier: Modifier, onPick: (Finder.Hit) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val hits = remember(query, j.config, j.marks, j.checkProblems) { Finder.search(query, j) }
    Column(modifier.width(760.dp).background(Ink.glass, RoundedCornerShape(12.dp)).border(1.dp, Ink.line, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth().background(Ink.bg, RoundedCornerShape(8.dp)).border(1.dp, Ink.accent, RoundedCornerShape(8.dp)).padding(12.dp, 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⌕", color = Ink.muted, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("a setting, a command, a part, a decision…", color = Ink.faint, fontSize = 14.sp)
                BasicTextField(query, onQuery, singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 14.sp), cursorBrush = SolidColor(Ink.accent), modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
            Text("esc", color = Ink.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { onClose() })
        }
        if (query.isNotEmpty() && hits.isEmpty()) Text("nothing matches", color = Ink.faint, fontSize = 13.sp, modifier = Modifier.padding(12.dp, 10.dp))
        for (h in hits) Row(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(h.kind.label, color = Ink.faint, fontSize = 10.sp, letterSpacing = 0.5.sp, modifier = Modifier.width(64.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(h.title, color = Ink.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = if (h.kind == Finder.Kind.DECISION || h.kind == Finder.Kind.PART) FontFamily.Default else FontFamily.Monospace, maxLines = 1)
                    h.entry?.let { ProvenanceChip(it, j) }      // where the value came from, one word
                }
                Text(h.detail, color = Ink.muted, fontSize = 11.5.sp, maxLines = 2, lineHeight = 15.sp)
            }
        }
    }
}

/**
 * For the plan's first-layer speed and height, which are only stand-ins: what applies instead for each loaded filament
 * (a first-layer window's choice, a lock over it, the filament's, or the machine's own speed), in the words
 * `plymeld config` prints under the same entries. Empty for every other path.
 */
fun appliesInstead(c: plymeld.config.Config, path: String): List<String> {
    val speedKey = path == "plan.mechanism.motion.first_layer.speed"; val heightKey = path == "plan.mechanism.adhesion.first_layer.height"
    if (!speedKey && !heightKey) return emptyList()
    val machine = c.machine ?: return emptyList()
    val slots = plymeld.config.ConfigSetup.slots(c, machine, ArrayList())
    val out = ArrayList<String>()
    for (f in slots.map { it.filament }.distinct()) {
        val site = plymeld.config.Site(machine = machine, plate = c.loadout("plate")?.value as String?, tool = slots.first { it.filament == f }.tool, filament = f)
        val use = plymeld.config.ConfigSetup.windowAt(c, site)
        val choice = use.choice; val w = use.window
        val locked = if (speedKey) plymeld.config.ConfigSetup.speedLock(c, site) else plymeld.config.ConfigSetup.heightLock(c, site)
        if (choice != null && w != null && locked == null) {
            out += "not used for '$f': the first layer comes from its window: ${plymeld.config.FirstLayerWindow.mm(choice.heightMm)} at ${plymeld.config.FirstLayerWindow.mms(choice.speedMmS)} (${w.key}, ${w.source})"
            continue
        }
        if (choice != null && w != null) out += "locked for '$f' over its first-layer window (${w.key})"
        use.warning?.let { out += "for '$f': $it" }
        if (speedKey) plymeld.config.ConfigSetup.firstLayerSpeed(c, site)?.takeIf { it.key != path }?.let { u -> out += "not used for '$f': its first layer prints at ${u.value}, ${u.key} (${u.source})" }
    }
    return out
}

/**
 * The drawer's page for one setting (the finder's pick): the path, its meaning and type from the schema, the scopes it
 * may be set for, the value in force with where it was set, and every value it overrode, lowest first: the config
 * stack read as `plymeld config` prints it (in the drawer).
 */
@Composable
fun SettingPage(j: JobState, path: String) {
    val decl = Schema.find(path.split('.'))
    val entry = j.config?.values?.get(path)
    val over = j.stack?.overridden?.filter { it.key == path } ?: emptyList()
    Column(Modifier.fillMaxWidth().background(Ink.surface2, RoundedCornerShape(10.dp)).border(1.dp, Ink.line, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("SETTING", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp)
        Text(path, color = Ink.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        decl?.let {
            Text(it.meaning, color = Ink.text, fontSize = 13.sp, lineHeight = 18.sp)
            Text("${it.type.label} · may be set for ${it.scopes.sorted().joinToString(", ") { s -> s.words }}", color = Ink.muted, fontSize = 12.sp)
        } ?: Text("not a path the schema declares", color = Ink.warn, fontSize = 12.sp)
        Text("IN FORCE", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 6.dp))
        if (entry == null) Text("not set: nothing in the files, no bundled value (the pipeline's stand-in applies where one exists)", color = Ink.muted, fontSize = 12.sp)
        else {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProvenanceChip(entry, j, decl)
                // a formula is written with its own leading '='
                Text(entry.value.toString().let { if (it.startsWith("=")) it else "= $it" }, color = Ink.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            }
            Text(entry.source, color = Ink.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        // the first-layer stand-ins: what applies instead (the window's choice, the filament's, or the machine's speed), as `plymeld config` says
        for (line in j.config?.let { appliesInstead(it, path) } ?: emptyList()) Text(line, color = Ink.warn, fontSize = 12.sp, lineHeight = 16.sp)
        if (over.isNotEmpty()) {
            Text("OVERRIDES", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 6.dp))
            for (o in over.reversed()) Column {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProvenanceChip(o, j, decl)
                    Text(o.value.toString().let { if (it.startsWith("=")) it else "= $it" }, color = Ink.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                }
                Text(o.source, color = Ink.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text("WHAT YOU CAN DO", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 6.dp))
        // a list of numbers (a pose's turn) offered as a file writes it, separated by spaces: its list text would not read back
        var value by remember(path, entry?.value) { mutableStateOf(entry?.value?.let { v -> if (v is List<*> && v.isNotEmpty() && v.all { it is Number }) v.joinToString(" ") { (it as Number).toDouble().toString().removeSuffix(".0") } else v.toString() } ?: "") }
        var busy by remember { mutableStateOf(false) }
        var scope by remember(path) { mutableStateOf("") }   // "" the plate, "obj" an object key, "obj/part" a part
        val scoped = scopedPath(path, scope)
        // set the value: a line in the user's own config file (`plymeld edit`), or in the plan file when the value in force is
        // its line (JobState.fileFor), the job read again; for the plate, or for one object or part where the path may be
        // set for one
        // where the value goes, by what it is ("the UI names the file and line first"); a job with no file of its
        // own gets one made when set (JobState.setValue)
        val t = j.targetFor(scoped)
        val target = t.file
        if ('<' in path) Text("a path with <key> names a family: open one of its members to set it", color = Ink.muted, fontSize = 12.sp)
        else {
            Text(if (target != null) "goes to ${target.fileName}" + (if (t.why.isNotEmpty()) ": ${t.why}" else "") else "goes to a job config file of your own, made when you set it",
                color = Ink.faint, fontSize = 11.sp, lineHeight = 15.sp)
            val scopes = decl?.scopes ?: emptySet()
            val objects = j.marks?.objects ?: j.partNames.map { it.first }.distinct()
            if (Schema.Scope.OBJECT in scopes && objects.isNotEmpty() && path.split('.').getOrNull(1) != "object") Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("for", color = Ink.muted, fontSize = 12.sp)
                Chip("the plate", scope == "") { scope = "" }
                for (o in objects.take(6)) Chip(o, scope == o) { scope = o }
                if (Schema.Scope.PART in scopes) for ((o, p) in j.partNames.filter { it.first in objects.take(6) }.take(8)) Chip("$o / $p", scope == "$o/$p") { scope = "$o/$p" }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).background(Ink.bg, RoundedCornerShape(6.dp)).border(1.dp, Ink.line, RoundedCornerShape(6.dp)).padding(8.dp, 5.dp)) {
                    BasicTextField(value, { value = it }, singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(Ink.accent))
                }
                Text(if (busy) "setting…" else "Set in ${target?.fileName ?: "a job file"}", color = if (busy) Ink.faint else Ink.accent, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 10.dp).clickable(enabled = !busy && value.isNotBlank()) { busy = true; j.setValue(scoped, value.trim()) { busy = false } })
                // a try: for this run only, as -cv, nothing written
                Text("Try", color = if (busy) Ink.faint else Ink.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 10.dp).clickable(enabled = !busy && value.isNotBlank()) { busy = true; j.tryValue(scoped, value.trim()) { busy = false } })
            }
            // tried now: one click from a file value, or dropped
            j.tries.firstOrNull { it.substringBefore('=') == scoped }?.let { tr ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("trying ${tr.substringAfter('=')} for this run (-cv)", color = Ink.hot, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("Make it a file value", color = Ink.accent, fontSize = 12.sp, modifier = Modifier.clickable(enabled = !busy) { busy = true; j.setValue(scoped, tr.substringAfter('=')) { busy = false } })
                    Text("Drop", color = Ink.muted, fontSize = 12.sp, modifier = Modifier.clickable(enabled = !busy) { busy = true; j.dropTry(scoped) { busy = false } })
                }
            }
            // refused: said here, where it was asked, not only in the console
            j.refusal?.takeIf { it.first == scoped }?.let { Text("not set: ${it.second}", color = Ink.warn, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        Text("the same as: plymeld edit ${target?.fileName ?: "FILE"} $scoped=VALUE", color = Ink.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A step's group of settings in the drawer: its title, a line on what setting them does, and the paths, each a page. */
class StepSettings(val title: String, val intro: String, val paths: List<String>)

/**
 * The settings a step's link opens in the drawer, each on its page as the finder opens one (every path a real one, no
 * `<key>`, so each can be set): `pose` (step 1's "Turn or move": how objects sit and where, then each object's locked
 * turn), `loadout` (step 2's "Change what is loaded": the plate on the machine's bed, the filament in each of its tools,
 * then whatever else the config loads), `priorities` (step 3's "More choices": every goal's weight, then the measures
 * the config weighs). [objects] are the plate's object keys. Null for any other name.
 */
fun stepSettings(name: String, c: plymeld.config.Config?, objects: List<String>, modelOpen: Boolean = true): StepSettings? = when (name) {
    "pose" -> StepSettings("TURN OR MOVE: HOW EACH OBJECT SITS",
        "How each object sits: decided, or as the model gives it (plan.pose); where: centered, or where the model places it (plan.placement; " +
            "no setting moves an object to a point yet); and each object's turn, a rotation row by row, which locks its orientation. " +
            "A value set here is a lock: the job is read again, and sliced again if it was.",
        listOf("plan.pose", "plan.placement") + objects.map { "plan.object.$it.pose.turn" })
    "loadout" -> {
        val m = c?.machine
        // with no model the config is not read yet (it is read with one): say so, not "choose a machine", which would send
        // the user round between this page and Another printer
        if (c == null && !modelOpen) StepSettings("CHANGE WHAT IS LOADED", "The loadout is read with the model: open a model (step 1), then what each tool holds can be changed here.", emptyList())
        else if (c == null || m == null) StepSettings("CHANGE WHAT IS LOADED", "No machine prints this job yet" + (c?.machineProblem?.let { " ($it)" } ?: "") + ": choose one with Another printer.", emptyList())
        else {
            val lo = "hardware.machine.$m.loadout"
            val tools = c.keysOf("tool").filter { (c.entry(listOf("hardware", "tool", it, "machine"))?.value as? String) == m }
            val named = listOf("$lo.plate") + tools.map { "$lo.tool.$it.filament" }
            StepSettings("CHANGE WHAT IS LOADED ON ${m.uppercase()}",
                "The build plate on $m's bed and the filament in each of its tools (a hardware.filament key), as the config says; " +
                    "Ask the printer compares them with what it reports. Set here, the job is read again with them.",
                named + c.values.keys.filter { it.startsWith("$lo.") && it !in named })
        }
    }
    "priorities" -> StepSettings("MORE CHOICES: WHAT MATTERS",
        "How much each goal weighs in the decisions, relative to the others, then the measures the config weighs on their own. " +
            "A decision the plan file locks stays as it is when they change (step 4's Change something frees the layers and the support spots: Decide again).",
        Schema.GOALS.map { "job.priorities.$it" }.let { goals -> goals + (c?.values?.keys?.filter { it.startsWith("job.priorities.") && it !in goals } ?: emptyList()) })
    else -> null
}

/**
 * The generic form of a command (every command has a UI entry, a form built from the command model at least): its
 * options as fields (a flag a toggle, fixed choices as chips, a value a field, the required ones marked), its arguments
 * as fields, the job's own config files, machine, and models filled in where the command takes them, and Run, which is
 * `Commands.invoke` with the console showing the lines. The command line it would run is shown under the form.
 */
@Composable
fun CommandForm(j: JobState, line: String, onRan: () -> Unit) {
    val c = Finder.commands.firstOrNull { it.line == line } ?: run { Text("no command '$line'", color = Ink.warn, fontSize = 12.sp); return }
    val takesConfig = c.options.any { "-cf" in it.names }; val takesModels = c.options.any { "-m" in it.names }
    // the job's config files, machine, and models filled in as the fields' values, not hidden and forced, so another
    // config, machine, or model can be given, and in the empty window a command needing -m can run;
    // `edit` journaled in the job's journal unless another is typed, so the bar's undo takes it back
    val values = remember(line) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
        for (o in c.options) {
            val name = o.names.last()
            when {
                "-cf" in o.names -> j.openConfigFiles.takeIf { it.isNotEmpty() }?.let { put(name, it.joinToString("; ")) }
                "-m" in o.names -> j.modelFilesOpen.takeIf { it.isNotEmpty() }?.let { put(name, it.joinToString("; ")) }
                "--machine" in o.names -> j.openMachine?.let { put(name, it) }
                "--journal" in o.names && c.path == listOf("edit") -> j.journalPath?.let { put(name, it.toString()) }
            }
        }
    } }
    val flags = remember(line) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val args = remember(line) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var busy by remember(line) { mutableStateOf(false) }
    fun words(): List<String> = commandWords(c, values, flags, args)
    Column(Modifier.fillMaxWidth().background(Ink.surface2, RoundedCornerShape(10.dp)).border(1.dp, Ink.line, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("COMMAND", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp)
        Text(c.line, color = Ink.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text(c.help, color = Ink.muted, fontSize = 12.sp, lineHeight = 16.sp)
        if (takesConfig || takesModels) Text("the job's " + listOfNotNull("config files".takeIf { takesConfig }, "models".takeIf { takesModels }).joinToString(" and ") + " are filled in below; change them to run on others",
            color = Ink.faint, fontSize = 11.sp)
        for (a in c.arguments) Field("<${a.name}>" + (if (a.required) " (required)" else ""), a.help + (if (a.repeatable) " · several: separate with ;" else ""), args[a.name] ?: "") { args[a.name] = it }
        for (o in c.options) {
            val name = o.names.last()
            when {
                o.flag -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { flags[name] = flags[name] != true }) {
                    Text(if (flags[name] == true) "☑" else "☐", color = Ink.accent, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                    Column { Text(name, color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace); Text(o.help, color = Ink.muted, fontSize = 11.sp, lineHeight = 14.sp) }
                }
                o.choices.isNotEmpty() -> Column {
                    Text(name + (if (o.required) " (required)" else ""), color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text(o.help, color = Ink.muted, fontSize = 11.sp, lineHeight = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) { for (ch in o.choices) Chip(ch, values[name] == ch) { values[name] = if (values[name] == ch) "" else ch } }
                }
                else -> Field(name + (o.metavar?.let { "=$it" } ?: "") + (if (o.required) " (required)" else "") + (o.tags["default"]?.let { " · default $it" } ?: ""), o.help + (if (o.repeated) " · several: separate with ;" else ""), values[name] ?: "") { values[name] = it }
            }
        }
        val w = words()
        Text("$ plymeld " + w.joinToString(" ") { if (' ' in it) "'$it'" else it }, color = Ink.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
        Text(if (busy) "running…" else "Run", color = if (busy) Ink.faint else Ink.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(if (busy) Ink.surface3 else Ink.accent, RoundedCornerShape(8.dp)).clickable(enabled = !busy) { busy = true; j.run(w) { busy = false; onRan() } }.padding(14.dp, 7.dp))
    }
}

/**
 * A command's line from its form's fields: the command's words, every option given a value ([values] by the option's last
 * name; a repeated one several values separated with ;) or a flag set ([flags]), then the arguments ([args] by name). No
 * option is left out or forced (the parity test checks every option of every command reaches the line).
 */
fun commandWords(c: CommandModel.Command, values: Map<String, String>, flags: Map<String, Boolean>, args: Map<String, String>): List<String> {
    val w = ArrayList(c.path)
    for (o in c.options) {
        val name = o.names.last()
        if (o.flag) { if (flags[name] == true) w += name }
        else values[name]?.takeIf { it.isNotBlank() }?.let { v -> if (o.repeated) v.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { w += name; w += it } else { w += name; w += v } }
    }
    for (a in c.arguments) args[a.name]?.takeIf { it.isNotBlank() }?.let { v -> if (a.repeatable) v.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { w += it } else w += v }
    return w
}

@Composable
internal fun Field(label: String, help: String, value: String, onValue: (String) -> Unit) {
    Column {
        Text(label, color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(help, color = Ink.muted, fontSize = 11.sp, lineHeight = 14.sp)
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).background(Ink.bg, RoundedCornerShape(6.dp)).border(1.dp, Ink.line, RoundedCornerShape(6.dp)).padding(8.dp, 5.dp)) {
            BasicTextField(value, onValue, singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(Ink.accent))
        }
    }
}

/** [path] set for the plate (scope ""), one object (its key), or one part ("object/part"): `plan.x` -> `plan.object.K.x`, `plan.object.K.part.P.x`. */
fun scopedPath(path: String, scope: String): String {
    if (scope.isEmpty()) return path
    val segs = path.split('.')
    if (segs.size < 2 || (segs[0] != "plan" && segs[0] != "job")) return path
    val rest = segs.drop(1).joinToString(".")
    val obj = scope.substringBefore('/'); val part = scope.substringAfter('/', "")
    return "${segs[0]}.object.$obj" + (if (part.isEmpty()) "" else ".part.$part") + ".$rest"
}

@Composable
internal fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) Ink.text else Ink.muted, fontSize = 11.sp, maxLines = 1,
        modifier = Modifier.background(if (on) Ink.surface3 else Color.Transparent, RoundedCornerShape(6.dp)).border(1.dp, Ink.line, RoundedCornerShape(6.dp)).clickable { onClick() }.padding(7.dp, 3.dp))
}
