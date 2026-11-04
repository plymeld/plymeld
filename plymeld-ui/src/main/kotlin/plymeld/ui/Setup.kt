package plymeld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plymeld.commands.FirstUse

/**
 * The window's first-use setup: the questions of `plymeld init` ([FirstUse]) as one form in the drawer. Every field
 * starts at its default, with where that comes from under it; Check and write slices a test cube with the answers on
 * each printer before anything is written, then writes the config folder's files and opens the job on them.
 */
private class PrinterDraft(m: FirstUse.Model, key: String, plate: String) {
    var model by mutableStateOf(m)
    var key by mutableStateOf(key)
    val values = mutableStateMapOf<String, String>()
    val conn = mutableStateMapOf<String, String>()
    val tools = mutableStateListOf<MutableMap<String, String>>()
    val same = mutableStateListOf<Boolean>()
    var plate by mutableStateOf(plate)
    /** The filament in each tool, by the tool's index from 0. */
    val loaded = mutableStateMapOf<Int, String>()

    init { use(m) }

    /** [m]'s questions, every field at its default. */
    fun use(m: FirstUse.Model) {
        model = m
        values.clear(); conn.clear(); tools.clear(); same.clear(); loaded.clear()
        for (a in FirstUse.printerAsks(m)) values[a.path] = a.default ?: ""
        for (a in FirstUse.connectionAsks(m)) conn[a.path] = a.default ?: ""
        repeat(m.toolheads) { i ->
            tools += mutableStateMapOf<String, String>().apply { for (a in FirstUse.toolAsks(m)) put(a.path, a.default ?: "") }
            same += i > 0
        }
    }
}

private class FilamentDraft(base: FirstUse.Base, key: String) {
    var base by mutableStateOf(base)
    var key by mutableStateOf(key)
    var color by mutableStateOf("")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupForm(j: JobState) {
    val c = remember { FirstUse.choices() }
    val dir = remember { plymeld.config.UserDirs.current().config }
    val problem = remember { FirstUse.folderProblem(dir) }
    fun freeKey(base: String, also: Collection<String>): String {
        var k = base; var n = 2
        while (FirstUse.keyProblem(k, c.taken + also) != null) k = "$base-${n++}"
        return k
    }
    val printers = remember { mutableStateListOf(PrinterDraft(c.models.first(), freeKey(c.models.first().id.substringAfterLast('-'), emptyList()), c.plates.first())) }
    val filaments = remember { mutableStateListOf<FilamentDraft>() }
    val lines = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    fun keysInUse(): List<String> = printers.flatMap { p -> listOf(p.key) + p.tools.indices.map { "${p.key}-t${it + 1}" } } + filaments.map { it.key }
    fun kindOf(f: String) = filaments.firstOrNull { it.key == f }?.base?.kind ?: ""

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("The same steps as plymeld init: your printers, your filaments, what is loaded where. Every field starts at its " +
            "default, with where that comes from under it. Nothing is written until Check and write, which first slices a 20 mm " +
            "test cube with the answers on each printer. The files go to $dir.", color = Ink.muted, fontSize = 12.sp, lineHeight = 17.sp)
        if (problem != null) { Text(problem, color = Ink.warn, fontSize = 12.sp, lineHeight = 17.sp); return@Column }
        if (done) { for (l in lines) Text(l, color = Ink.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp); return@Column }

        for ((pi, p) in printers.withIndex()) Card("PRINTER ${pi + 1}") {
            Text("Model", color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in c.models) Chip(m.name, p.model.id == m.id) { if (p.model.id != m.id) { p.use(m); p.key = freeKey(m.id.substringAfterLast('-'), keysInUse() - p.key) } }
            }
            Field("name", "the key this printer is known by (lower-case letters, digits, -)", p.key) { p.key = it.trim() }
            for (a in FirstUse.printerAsks(p.model)) Ask(a, p.values[a.path] ?: "") { p.values[a.path] = it }
            Text("Connection", color = Ink.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            for (a in FirstUse.connectionAsks(p.model)) Ask(a, p.conn[a.path] ?: "") { p.conn[a.path] = it }
            for ((ti, tv) in p.tools.withIndex()) {
                Text("Tool ${ti + 1} (${p.key}-t${ti + 1})", color = Ink.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                if (ti > 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { p.same[ti] = !p.same[ti] }) {
                    Text(if (p.same[ti]) "☑" else "☐", color = Ink.accent, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                    Text("the same nozzle as tool 1", color = Ink.text, fontSize = 12.sp)
                }
                if (ti == 0 || !p.same[ti]) for (a in FirstUse.toolAsks(p.model)) Ask(a, tv[a.path] ?: "") { tv[a.path] = it }
            }
            Text("Build plate", color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (pl in c.plates) Chip(pl, p.plate == pl) { p.plate = pl } }
            if (printers.size > 1) Text("remove this printer", color = Ink.accent, fontSize = 12.sp, modifier = Modifier.clickable { printers.removeAt(pi) })
        }
        Text("Add another printer", color = Ink.accent, fontSize = 12.sp, modifier = Modifier.clickable {
            printers += PrinterDraft(c.models.first(), freeKey(c.models.first().id.substringAfterLast('-'), keysInUse()), c.plates.first())
        })

        Card("FILAMENTS") {
            Text("Each starts from plymeld's own for its kind (like); add one for each spool you print with.", color = Ink.muted, fontSize = 11.sp, lineHeight = 14.sp)
            for ((fi, f) in filaments.withIndex()) {
                Text("${f.base.kind} (like ${f.base.key})", color = Ink.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                Field("name", "the key this filament is known by", f.key) { f.key = it.trim() }
                Field("color", "#RRGGBB, for the pictures; empty: none", f.color) { f.color = it.trim() }
                Text("remove", color = Ink.accent, fontSize = 12.sp, modifier = Modifier.clickable { filaments.removeAt(fi) })
            }
            Text("Add", color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in c.filaments) Chip("+ ${b.kind}", false) { filaments += FilamentDraft(b, freeKey(b.kind.lowercase(), keysInUse())) }
            }
        }

        if (filaments.isNotEmpty()) Card("WHAT IS LOADED") {
            for (p in printers) for (ti in p.tools.indices) {
                val fits = filaments.filter { c.suits(p.plate, it.base.kind) }.map { it.key }
                Text("${p.key}-t${ti + 1}", color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                if (fits.isEmpty()) Text("none of the filaments has a bed temperature on ${p.plate}: pick another plate or add a filament", color = Ink.warn, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in fits) Chip(f, p.loaded[ti] == f) { p.loaded[ti] = f }
                    if (ti > 0) Chip("nothing", p.loaded[ti] == null) { p.loaded.remove(ti) }
                }
            }
        }

        // what is missing before a check can run: every key free, every required value, a filament in each first tool
        val missing = buildList {
            val keys = keysInUse()
            for (k in keys.distinct()) FirstUse.keyProblem(k, c.taken)?.let { add("$k: $it") }
            for ((k, n) in keys.groupingBy { it }.eachCount()) if (n > 1) add("$k: used twice (each key names one thing)")
            for (p in printers) {
                for (a in FirstUse.printerAsks(p.model)) if (!a.optional && p.values[a.path].isNullOrBlank()) add("${p.key}: ${a.question}")
                p.tools.forEachIndexed { ti, tv -> if (ti == 0 || !p.same[ti]) for (a in FirstUse.toolAsks(p.model)) if (!a.optional && tv[a.path].isNullOrBlank()) add("${p.key}-t${ti + 1}: ${a.question}") }
                if (p.loaded[0] == null || !c.suits(p.plate, kindOf(p.loaded[0]!!))) add("${p.key}-t1: a filament loaded")
            }
            if (filaments.isEmpty()) add("a filament")
        }
        if (missing.isNotEmpty()) Text("Still needed: " + missing.joinToString("; "), color = Ink.faint, fontSize = 11.sp, lineHeight = 15.sp)
        val ready = missing.isEmpty() && !busy
        Text(if (busy) "checking…" else "Check and write", color = if (ready) Ink.onAccent else Ink.faint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(if (ready) Ink.accent else Ink.surface3, RoundedCornerShape(8.dp)).clickable(enabled = ready) {
                busy = true; lines.clear()
                val answers = FirstUse.Answers(printers.map { p ->
                    val values = LinkedHashMap(p.values.filterValues { it.isNotBlank() })
                    val host = p.conn["host"]?.takeIf { it.isNotBlank() }
                    if (host != null) { values["connection.host"] = host; FirstUse.api(p.model)?.let { values["connection.api"] = it } }
                    val secrets = if (host == null) emptyMap() else p.conn.filterKeys { it != "host" }.filterValues { it.isNotBlank() }
                    val tools = p.tools.mapIndexed { ti, tv ->
                        FirstUse.Tool("${p.key}-t${ti + 1}", ti + 1, (if (ti > 0 && p.same[ti]) p.tools[0] else tv).filterValues { it.isNotBlank() }, p.loaded[ti])
                    }
                    FirstUse.Printer(p.key, p.model.id, values, tools, p.plate, secrets)
                }, filaments.map { FirstUse.Filament(it.key, it.base.key, it.color.takeIf { c -> c.isNotBlank() }) })
                Thread({
                    val said = ArrayList<String>()
                    val say: (String) -> Unit = { l -> said += l; Snapshot.withMutableSnapshot { lines += l } }
                    val files = FirstUse.files(answers, dir)
                    val ok = FirstUse.trial(files, answers.printers.map { it.key }, say)
                    if (ok) {
                        for (path in FirstUse.write(files, dir)) say("wrote $path")
                        // printing needs the printer reached: each one given an address asked for its state
                        val unreached = FirstUse.connected(answers).filterNot { p ->
                            FirstUse.reach(dir, p, say).also { r ->
                                say(if (r) "$p answered: Send can reach it"
                                    else "$p did not answer (above): check it is on and its address and credentials, then Ask the printer (step 2)")
                            }
                        }
                        say((if (unreached.isEmpty()) "Set up: plan, slice, and print." else "Set up: plan and slice; printing waits on reaching ${unreached.joinToString()}.") +
                            " The job opens on these files.")
                        Snapshot.withMutableSnapshot { done = true }
                        j.open(plymeld.config.ConfigFiles.defaultFiles().map { it.toString() }, j.modelFilesOpen, answers.printers.first().key)
                    } else say("Nothing written: change the answers above and check again.")
                    Snapshot.withMutableSnapshot { busy = false }
                }, "plymeld-setup").apply { isDaemon = true }.start()
            }.padding(14.dp, 7.dp))
        for (l in lines) Text(l, color = Ink.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
    }
}

/** One question: chips for a fixed set, else a field; its default shown in the field, where that comes from under it. */
@Composable
private fun Ask(a: FirstUse.Ask, value: String, onValue: (String) -> Unit) {
    if (a.choices.isNotEmpty()) Column {
        Text(a.question, color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(a.why, color = Ink.muted, fontSize = 11.sp, lineHeight = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) { for (ch in a.choices) Chip(ch, value == ch) { onValue(ch) } }
    } else Field(a.question + (if (a.optional) " (optional)" else ""), a.why, value, onValue)
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ink.surface2, RoundedCornerShape(10.dp)).border(1.dp, Ink.line, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp)
        content()
    }
}
