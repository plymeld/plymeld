package plymeld.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plymeld.core.decide.Decision
import java.util.Locale

/** The marks' colors: bands on the ruler, spots as boxes, pauses as planes. plymeld's choices. */

/**
 * The plan drawn on the model (the stage's Plan tab): each support spot as its box, each
 * pause as a plane across the plate at its height, and the height bands on a ruler at the stage's right edge, all
 * projected with the camera the shown frame was drawn with, so they sit on the picture. Everything drawn comes from
 * [JobState.PlanMarks], which comes from the plan; nothing here decides or estimates.
 */
@Composable
fun PlanOverlay(j: JobState, modifier: Modifier, onPick: (Int) -> Unit = {}) {
    val m = j.marks ?: return
    val measurer = rememberTextMeasurer()
    val cam = j.viewer.frameCamera
    val label = TextStyle(fontSize = 11.sp, color = Ink.text, fontFamily = FontFamily.Monospace)
    // a tap on a spot's box or a band on the ruler opens its decision; a drag still turns the view and the wheel zooms it
    // (viewGestures: the overlay lies over the view, so it passes them on)
    Canvas(modifier.viewGestures(j.viewer) { pos, size ->
        run {
            val aspect = size.width.toDouble() / size.height
            fun at(x: Double, y: Double, z: Double): Offset {
                val n = cam.project(doubleArrayOf(x, y, z), aspect)
                return Offset(((n[0] + 1) / 2 * size.width).toFloat(), ((1 - n[1]) / 2 * size.height).toFloat())
            }
            // the smallest box whose picture holds the point
            val spot = m.spots.map { s ->
                val c = listOf(at(s.x0, s.y0, s.z0), at(s.x1, s.y0, s.z0), at(s.x1, s.y1, s.z0), at(s.x0, s.y1, s.z0), at(s.x0, s.y0, s.z1), at(s.x1, s.y0, s.z1), at(s.x1, s.y1, s.z1), at(s.x0, s.y1, s.z1))
                val x0 = c.minOf { it.x }; val x1 = c.maxOf { it.x }; val y0 = c.minOf { it.y }; val y1 = c.maxOf { it.y }
                Triple(s, pos.x in x0..x1 && pos.y in y0..y1, (x1 - x0) * (y1 - y0))
            }.filter { it.second }.minByOrNull { it.third }?.first
            if (spot != null) { onPick(spot.decision); return@run }
            val top = m.bounds[5]; val x = size.width - 300f; val y0 = size.height * 0.12f; val y1 = size.height * 0.88f
            fun yOf(z: Double) = (y1 - (z / top) * (y1 - y0)).toFloat()
            if (kotlin.math.abs(pos.x - x) < 40f) m.bands.firstOrNull { pos.y in yOf(it.hi)..yOf(it.lo) }?.let { onPick(it.decision) }
        }
    }) {
        val aspect = size.width.toDouble() / size.height
        fun at(x: Double, y: Double, z: Double): Offset {
            val n = cam.project(doubleArrayOf(x, y, z), aspect)
            return Offset(((n[0] + 1) / 2 * size.width).toFloat(), ((1 - n[1]) / 2 * size.height).toFloat())
        }
        fun box(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double, ink: Color, dashed: Boolean) {
            val c = listOf(at(x0, y0, z0), at(x1, y0, z0), at(x1, y1, z0), at(x0, y1, z0), at(x0, y0, z1), at(x1, y0, z1), at(x1, y1, z1), at(x0, y1, z1))
            val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
            val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null
            for ((a, b) in edges) drawLine(ink, c[a], c[b], strokeWidth = 1.6f, pathEffect = effect)
            // the top face tinted, so the box reads as a volume
            val top = Path().apply { moveTo(c[4].x, c[4].y); lineTo(c[5].x, c[5].y); lineTo(c[6].x, c[6].y); lineTo(c[7].x, c[7].y); close() }
            drawPath(top, ink.copy(alpha = 0.18f))
        }
        val b = m.bounds
        // the height bands as tinted slabs over the model's footprint,
        // alternating two tints so neighbors read apart; their words stay on the ruler
        for ((k, band) in m.bands.withIndex()) {
            val tint = (if (k % 2 == 0) Ink.accent else Ink.ok).copy(alpha = 0.08f)
            // over its own object's footprint (the plate's when the band names none)
            val f = band.footprint ?: doubleArrayOf(b[0], b[1], b[3], b[4])
            val c = listOf(at(f[0], f[1], band.lo), at(f[2], f[1], band.lo), at(f[2], f[3], band.lo), at(f[0], f[3], band.lo),
                at(f[0], f[1], band.hi), at(f[2], f[1], band.hi), at(f[2], f[3], band.hi), at(f[0], f[3], band.hi))
            // the slab's four sides as tinted quads, then its top edge
            for ((a0, a1) in listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)) {
                val side = Path().apply { moveTo(c[a0].x, c[a0].y); lineTo(c[a1].x, c[a1].y); lineTo(c[a1 + 4].x, c[a1 + 4].y); lineTo(c[a0 + 4].x, c[a0 + 4].y); close() }
                drawPath(side, tint)
            }
            val top = Path().apply { moveTo(c[4].x, c[4].y); for (q in 5..7) lineTo(c[q].x, c[q].y); close() }
            drawPath(top, tint.copy(alpha = 0.5f), style = Stroke(1f))
        }
        // pauses: a plane across the model's footprint at the height, named at its corner
        for ((z, words) in m.pauses) {
            val margin = 4.0
            val c = listOf(at(b[0] - margin, b[1] - margin, z), at(b[3] + margin, b[1] - margin, z), at(b[3] + margin, b[4] + margin, z), at(b[0] - margin, b[4] + margin, z))
            val p = Path().apply { moveTo(c[0].x, c[0].y); for (k in 1..3) lineTo(c[k].x, c[k].y); close() }
            drawPath(p, Ink.text.copy(alpha = 0.10f))
            drawPath(p, Ink.text, style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))))
            tag(measurer, words, c[1], label, Ink.text)
        }
        // support spots: their boxes, locked ones in another ink and solid
        for (s in m.spots) {
            box(s.x0, s.x1, s.y0, s.y1, s.z0, s.z1, if (s.locked) Ink.hot else Ink.warn, dashed = !s.locked)
            tag(measurer, s.label, at(s.x1, s.y1, s.z1), label, if (s.locked) Ink.hot else Ink.warn)
        }
        // the height ruler at the right edge: the bands' boundaries and words
        val top = b[5]; val x = size.width - 300f; val y0 = size.height * 0.12f; val y1 = size.height * 0.88f
        fun yOf(z: Double) = (y1 - (z / top) * (y1 - y0)).toFloat()
        drawLine(Ink.muted, Offset(x, y0), Offset(x, y1), strokeWidth = 1.2f)
        for (band in m.bands) {
            val ya = yOf(band.lo); val yb = yOf(band.hi)
            drawRect(Ink.accent.copy(alpha = 0.22f), Offset(x - 6f, yb), androidx.compose.ui.geometry.Size(12f, ya - yb))
            drawLine(Ink.accent, Offset(x - 10f, ya), Offset(x + 10f, ya), strokeWidth = 1.6f)
            drawLine(Ink.accent, Offset(x - 10f, yb), Offset(x + 10f, yb), strokeWidth = 1.6f)
            val t = measurer.measure("${fmt(band.lo)}–${fmt(band.hi)} mm  ${band.label}", label)
            drawText(t, topLeft = Offset(x + 14f, (ya + yb) / 2 - t.size.height / 2))
        }
        // the pauses on the ruler's left, ending at it (the bands' words are on its right: they collided there)
        for ((z, words) in m.pauses) {
            val y = yOf(z)
            drawLine(Ink.text, Offset(x - 14f, y), Offset(x + 14f, y), strokeWidth = 1.4f)
            val t = measurer.measure(words, label.copy(color = Ink.text))
            drawText(t, topLeft = Offset(x - 18f - t.size.width, y - t.size.height / 2))
        }
        // a pause at a layer has no height until sliced: listed under the ruler (the timeline places it)
        for ((k, pause) in m.layerPauses.withIndex()) {
            val t = measurer.measure("${pause.second} (placed when sliced)", label.copy(color = Ink.text))
            drawText(t, topLeft = Offset(x - 10f, y1 + 18f + k * (t.size.height + 2f)))
        }
        val t0 = measurer.measure("0 mm", label.copy(color = Ink.muted)); drawText(t0, topLeft = Offset(x + 14f, y1 - t0.size.height / 2))
        val t1 = measurer.measure("${fmt(top)} mm  top", label.copy(color = Ink.muted)); drawText(t1, topLeft = Offset(x + 14f, y0 - t1.size.height / 2))
    }
}

private fun DrawScope.tag(measurer: androidx.compose.ui.text.TextMeasurer, words: String, at: Offset, style: TextStyle, ink: Color) {
    val t = measurer.measure(words, style.copy(color = ink))
    val tl = Offset(at.x + 6f, at.y - t.size.height - 4f)
    drawRect(Ink.glass, Offset(tl.x - 4f, tl.y - 2f), androidx.compose.ui.geometry.Size(t.size.width + 8f, t.size.height + 4f))
    drawText(t, topLeft = tl)
}

private fun fmt(v: Double) = "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')

/**
 * The actions on a technique decision: lock the flat layers at a height (every band one height, written into the plan
 * file), keep a support spot across re-plans or let it be decided
 * again (its lines marked `!`). Each is `plymeld edit`; the job is read again after it.
 */
@Composable
private fun TechniqueActions(j: JobState, obj: String, tp: plymeld.core.decide.TechniquePlan) {
    var height by remember(obj) { mutableStateOf(tp.layerHeightMm?.let { fmt(it) } ?: "") }
    var busy by remember { mutableStateOf(false) }
    Text("what you can do", color = Ink.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("flat layers of", color = Ink.muted, fontSize = 12.sp)
        Box(Modifier.padding(horizontal = 6.dp).width(64.dp).background(Ink.bg, RoundedCornerShape(6.dp)).border(1.dp, Ink.line, RoundedCornerShape(6.dp)).padding(6.dp, 4.dp)) {
            androidx.compose.foundation.text.BasicTextField(height, { height = it }, singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.accent))
        }
        Text("mm", color = Ink.muted, fontSize = 12.sp)
        Text(if (busy) "locking…" else "Lock", color = if (busy) Ink.muted else Ink.accent, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp).clickable(enabled = !busy && height.isNotBlank()) {
            busy = true; j.lockHeight(obj, height.trim()) { busy = false } })
        // the plan file's lines for the layers removed: decided again when the job is read
        if (j.planFile != null) Text("Decide again", color = Ink.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp).clickable(enabled = !busy) { busy = true; j.decideAgain(obj, "technique") { busy = false } })
    }
    val spots = j.marks?.spotsOf?.get(obj) ?: emptyList()
    for (id in spots) {
        val kept = j.spotKept(obj, id)
        Row(Modifier.padding(top = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("spot $id" + (if (kept) " (kept)" else ""), color = if (kept) Ink.hot else Ink.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (kept) "release" else "keep across re-plans", color = Ink.accent, fontSize = 12.sp,
                modifier = Modifier.clickable(enabled = !busy) { busy = true; j.keepSpot(obj, id, !kept) { busy = false } })
            if (j.planFile != null) Text("decide again", color = Ink.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp).clickable(enabled = !busy) { busy = true; j.decideAgain(obj, id) { busy = false } })
        }
    }
    // a lock, keep, release, or decide-again refused: said here, where it was asked
    j.refusal?.takeIf { it.first == JobState.REFUSED_IN_PLAN }?.let { Text("not changed: ${it.second}", color = Ink.warn, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp)) }
}

/**
 * The drawer: every decision of the plan in the same shape: what it is (the step and its
 * value in words), why (the plan's reason), what else was weighed (the alternatives with their scores, the chosen one
 * first, rejected ones with the reason), the facts it was made from (folded), the stand-ins it leaned on, and what you
 * can do (the plan file is the lock: `plymeld plan -o plan.toml`, then `plymeld slice -cf plan.toml`; a value in it edited
 * is a lock on that alone). Nothing here is re-worded from the plan's own text: the words are the decisions'. It also
 * holds what the finder and the steps' links open: one setting's page, a command's form, a step's group of settings
 * ([stepSettings]), and the decisions that can be locked from here alone (step 4's "Change something").
 */
@Composable
fun Drawer(j: JobState, modifier: Modifier, onClose: () -> Unit, page: String? = null, focus: Int? = null, onDecisions: () -> Unit = {}, onRan: () -> Unit = {}) {
    val m = j.marks
    val one = focus?.let { m?.decisions?.getOrNull(it) }
    // a step's link: its group of settings (WindowUi.GROUP), or the decisions that can be changed (WindowUi.CHANGE)
    val changing = page == WindowUi.CHANGE
    val group = page?.takeIf { it.startsWith(WindowUi.GROUP) }?.let { stepSettings(it.removePrefix(WindowUi.GROUP), j.config, m?.objects ?: j.partNames.map { p -> p.first }.distinct(), j.modelFilesOpen.isNotEmpty()) }
    Column(modifier.background(Ink.surface).border(1.dp, Ink.line).padding(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(when { group != null -> group.title; changing -> "CHANGE SOMETHING: WHAT CAN BE LOCKED"; page == WindowUi.SETTINGS -> "THE WINDOW'S OWN SETTINGS"; page == WindowUi.SETUP -> "SET UP YOUR PRINTER AND FILAMENTS"; page != null && page.startsWith("command:") -> "A COMMAND, AS A FORM"; page != null -> "ONE SETTING, WHERE IT COMES FROM"
                one != null -> "FROM THE STAGE: ONE DECISION"; else -> "THE PLAN, DECISION BY DECISION" },
                color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            if (page != null || one != null) Text("all decisions", color = Ink.accent, fontSize = 12.sp, modifier = Modifier.clickable { onDecisions() }.padding(end = 12.dp))
            Text("close", color = Ink.accent, fontSize = 12.sp, modifier = Modifier.clickable { onClose() })
        }
        if (group != null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.intro, color = Ink.muted, fontSize = 12.sp, lineHeight = 17.sp)
                for (p in group.paths) key(p) { SettingPage(j, p) }
            }
            return@Column
        }
        if (page != null && page.startsWith("command:")) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) { CommandForm(j, page.removePrefix("command:"), onRan = onRan) }; return@Column }
        if (page == WindowUi.SETTINGS) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) { WindowSettings(j) }; return@Column }
        if (page == WindowUi.SETUP) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) { SetupForm(j) }; return@Column }
        if (page != null && !changing) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) { SettingPage(j, page) }; return@Column }
        if (m == null) { Text("nothing decided yet", color = Ink.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)); return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // changing: only the decisions with actions here (a technique's layers and support spots), and where the rest are changed
            if (changing) Text("The decisions that can be locked from here, each with its actions. The others are settings: the pose and placement " +
                "under step 1's Turn or move, the weights under step 3's More choices, any other through the finder (Ctrl K).",
                color = Ink.muted, fontSize = 12.sp, lineHeight = 17.sp)
            for ((i, d) in m.decisions.withIndex()) if ((one == null || d === one) && (!changing || i in m.objectOf)) DecisionCard(d, j, i)
            Text("Every decision above is a line in the plan file" + (j.planFile?.let { " (${it.fileName})" } ?: " (`plymeld plan -o` writes one; the first lock from here writes it)") +
                ". A lock from here edits that line (`plymeld edit`), the job is read again, and a slice made before is dropped. Undo and redo are in the bar.",
                color = Ink.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * The window's own settings (the bar's "settings"): per-viewer
 * conveniences only, nothing that changes a print (those are config values, found by the finder): the theme, whether
 * the window checks for updates, and where it keeps what it writes and remembers.
 */
@Composable
private fun WindowSettings(j: JobState) {
    var check by remember { mutableStateOf(Updates.Pref.also { it.load() }.check) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Per-viewer conveniences: nothing here changes a print. What does is a config value; the finder (Ctrl K) finds every one.", color = Ink.muted, fontSize = 12.sp, lineHeight = 17.sp)
        Text("THEME", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((label, dark) in listOf("dark" to true, "light" to false)) Text(label, color = if (Ink.dark == dark) Ink.text else Ink.accent, fontSize = 12.sp,
                fontWeight = if (Ink.dark == dark) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable(enabled = Ink.dark != dark) { Ink.toggle(); j.viewer.vp.gl { this.dark = Ink.dark }; j.viewer.requestFrame() })
        }
        Text("UPDATES", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (c in Updates.Pref.Check.entries) Text(when (c) { Updates.Pref.Check.ASK -> "say so, install on a click"; Updates.Pref.Check.AUTO -> "install at the next start"; Updates.Pref.Check.NEVER -> "never check" },
                color = if (check == c) Ink.text else Ink.accent, fontSize = 12.sp, fontWeight = if (check == c) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable { Updates.Pref.save(c); check = c })
        }
        Text("kept in ${Updates.Pref.file}", color = Ink.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text("WHERE THE WINDOW KEEPS THINGS", color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 6.dp))
        for ((what, where) in listOf("plan files and their journals" to JobState.userDataDir().resolve("plans"), "job config files it made" to JobState.userDataDir().resolve("jobs"),
            "the last job's config files" to JobState.LastJob.file, "the recent jobs" to JobState.RecentJobs.file, "g-code it sliced (temporary)" to JobState.outFor(emptyList()).parent)) Column {
            Text(what, color = Ink.muted, fontSize = 12.sp)
            Text(where.toString(), color = Ink.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        if (j.recent.isNotEmpty()) Text("Forget the recent jobs", color = Ink.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp).clickable { j.forgetRecent() })
    }
}

@Composable
private fun DecisionCard(d: Decision<*>, j: JobState, index: Int) {
    var facts by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().background(Ink.surface2, shape).border(1.dp, Ink.line, shape).padding(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(d.step.uppercase(), color = Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            if (d.overridden) Text("locked", color = Ink.hot, fontSize = 11.sp)
        }
        Text(d.why, color = Ink.text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        // what you can do with a technique decision: lock its layers' height, keep or release each support spot
        val tp = d.value as? plymeld.core.decide.TechniquePlan
        val obj = j.marks?.objectOf?.get(index)
        if (tp != null && obj != null) TechniqueActions(j, obj, tp)
        // the search records a candidate once per round it survived, so the same label and score repeat (seen:
        // the chosen plan five times); the drawer shows each distinct line once, the chosen one first
        val distinct = d.alternatives.distinctBy { it.label + "|" + it.score + "|" + (it.rejected ?: "") }.sortedByDescending { it.chosen }
        if (distinct.isNotEmpty()) {
            Text("what else was weighed", color = Ink.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            for (a in distinct.take(8)) {
                Row(Modifier.padding(top = 3.dp)) {
                    Text(if (a.chosen) "●" else "○", color = if (a.chosen) Ink.accent else Ink.muted, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp, top = 1.dp))
                    Column {
                        Text(a.label, color = if (a.chosen) Ink.text else Ink.muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3)
                        Text(a.score + (a.rejected?.let { " · rejected: $it" } ?: ""), color = if (a.rejected != null) Ink.warn else Ink.muted, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                    }
                }
            }
            if (distinct.size > 8) Text("and ${distinct.size - 8} more (the console has them all)", color = Ink.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (d.standIns.isNotEmpty()) {
            Text("stand-ins it leaned on", color = Ink.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            for (s in d.standIns) Text("· $s", color = Ink.warn, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (d.facts.isNotEmpty()) {
            Text(if (facts) "▾ the facts it was made from (${d.facts.size})" else "▸ the facts it was made from (${d.facts.size})", color = Ink.accent, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp).clickable { facts = !facts })
            if (facts) for (f in d.facts) Text("· $f", color = Ink.muted, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
