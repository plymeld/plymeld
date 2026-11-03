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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the tray shows (the console, the check's report, the timeline, and the history). */
enum class TrayTab(val label: String) { CONSOLE("Console"), CHECK("Check"), TIMELINE("Timeline"), HISTORY("History") }


/**
 * The tray under the stage: the console (every line the commands printed), the check's report (each problem, a click
 * taking the Layers tab to its layer), the timeline (the print's layers as lanes per tool, the pauses marked), and the
 * history (the journal). It slides up on the `›_` icon or `--console`; its tabs are the window's state.
 */
@Composable
fun Tray(j: JobState, tab: TrayTab, onTab: (TrayTab) -> Unit, onLayer: (Int) -> Unit, modifier: Modifier) {
    Column(modifier.background(Ink.surface).border(1.dp, Ink.line).padding(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in TrayTab.entries) {
                val on = t == tab
                val count = when (t) { TrayTab.CHECK -> j.checkProblems.size; TrayTab.HISTORY -> j.journal?.entries?.size ?: 0; else -> 0 }
                Text(t.label.uppercase() + (if (count > 0) " $count" else ""), color = if (on) Ink.text else Ink.faint, fontSize = 11.sp, letterSpacing = 0.6.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Ink.surface3 else Color.Transparent).clickable { onTab(t) }.padding(8.dp, 3.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            when (tab) {
                TrayTab.CONSOLE -> ConsoleLines(j)
                TrayTab.CHECK -> CheckPane(j, onLayer)
                TrayTab.TIMELINE -> Timeline(j, onLayer)
                TrayTab.HISTORY -> History(j, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The console: every line the commands printed, the newest in view (it follows them unless
 * scrolled up), long lines wrapped and the text selectable; under it, a command line: what is typed runs as `plymeld` takes
 * it, in-process, exactly as typed (nothing is added to it), Up and Down for the lines typed before. A command that
 * changes one of the job's files reads the job again ([JobState.run]).
 */
@Composable
private fun ConsoleLines(j: JobState) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val lines = j.console
    // follow the newest line, unless the user has scrolled up to read
    androidx.compose.runtime.LaunchedEffect(lines.size) {
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lines.isNotEmpty() && (last < 0 || last >= list.layoutInfo.totalItemsCount - 3)) list.scrollToItem(lines.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(lines.size) { i ->
                    val line = lines[i]
                    Text(line, color = if (line.startsWith("$ ")) Ink.accent else if (line.startsWith("plymeld: ") || line.startsWith("warning")) Ink.warn else Ink.muted,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp))
                }
            }
        }
        ConsoleInput(j)
    }
}

/** The console's command line: Enter runs what is typed ([JobState.typed]), Up / Down step through the lines typed before. */
@Composable
private fun ConsoleInput(j: JobState) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var back by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(-1) }   // -1: not stepping through the history
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).background(Ink.bg, RoundedCornerShape(6.dp)).border(1.dp, Ink.line, RoundedCornerShape(6.dp)).padding(8.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$ plymeld ", color = Ink.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("a command, as typed after plymeld (the job's files are not added: -cf, -m)", color = Ink.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            androidx.compose.foundation.text.BasicTextField(text, { text = it; back = -1 }, singleLine = true,
                textStyle = TextStyle(color = Ink.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace), cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.accent),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val typed = j.typedLines
                    when (e.key) {
                        androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter -> { if (text.isNotBlank()) { j.typed(text); text = ""; back = -1 }; true }
                        androidx.compose.ui.input.key.Key.DirectionUp -> { if (typed.isNotEmpty()) { back = if (back < 0) typed.size - 1 else maxOf(0, back - 1); text = typed[back] }; true }
                        androidx.compose.ui.input.key.Key.DirectionDown -> { if (back >= 0) { back += 1; if (back >= typed.size) { back = -1; text = "" } else text = typed[back] }; true }
                        else -> false
                    }
                })
        }
    }
}

/** The check's problems, one per line; a problem at a layer is a link to that layer in the Layers tab. */
@Composable
private fun CheckPane(j: JobState, onLayer: (Int) -> Unit) {
    val p = j.viewer.paths
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (j.gcode == null && j.checkProblems.isEmpty()) Text("no slice yet", color = Ink.faint, fontSize = 12.sp)
        else if (j.checkProblems.isEmpty()) Text("the g-code reads back as the config and the model say: no problems", color = Ink.ok, fontSize = 12.sp)
        else for (problem in j.checkProblems) {
            val z = Regex("layer at z ([0-9.]+)").find(problem)?.groupValues?.get(1)?.toDoubleOrNull()
            val layer = if (z != null && p != null) (0 until p.layers).firstOrNull { p.layerTop(it) >= z - 1e-6 } else null
            Row(verticalAlignment = Alignment.Top) {
                Text(if (layer != null) "layer ${layer + 1}" else "·", color = if (layer != null) Ink.accent else Ink.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(84.dp).let { m -> if (layer != null) m.clickable { onLayer(layer) } else m })
                Text(problem, color = Ink.warn, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
        }
    }
}

/**
 * The timeline: the print's layers left to right, each a stripe in its tools' colors (the share of the layer's lines
 * each tool lays), the pauses as marks at their height; a click takes the Layers tab to that layer.
 */
@Composable
private fun Timeline(j: JobState, onLayer: (Int) -> Unit) {
    val p = j.viewer.paths
    val measurer = rememberTextMeasurer()
    if (p == null) { Text("no slice yet", color = Ink.faint, fontSize = 12.sp); return }
    val byT = HashMap<Int, Int>(); for (s in j.loadout) byT.putIfAbsent(s.toolIndex - 1, s.rgb)
    val label = TextStyle(fontSize = 10.sp, color = Ink.muted, fontFamily = FontFamily.Monospace)
    Canvas(Modifier.fillMaxSize().pointerInput(p) {
        detectTapGestures { pos -> val n = p.layers; onLayer(((pos.x / size.width) * n).toInt().coerceIn(0, n - 1)) }
    }) {
        val n = p.layers; val w = size.width; val h = size.height - 18f
        val lane = w / n
        for (l in 0 until n) {
            val a = p.layerStart[l]; val b = p.layerStart[l + 1]
            if (b <= a) continue
            // the tools' shares of this layer's lines, stacked bottom up
            val counts = HashMap<Int, Int>()
            for (s in a until b) counts.merge(p.tool[s].toInt() and 0xFF, 1, Int::plus)
            var y = h
            for ((t, c) in counts.entries.sortedBy { it.key }) {
                val hh = h * c / (b - a)
                drawRect(Color(0xFF000000 or (byT[t] ?: 0x9AA3B0).toLong()), Offset(l * lane, y - hh), Size(maxOf(lane - 0.5f, 0.5f), hh))
                y -= hh
            }
        }
        // the pauses at their heights, and the ones at a layer at that layer
        val marks = (j.marks?.pauses?.mapNotNull { (z, words) -> (0 until n).firstOrNull { p.layerTop(it) >= z - 1e-6 }?.let { it to words } } ?: emptyList()) +
            (j.marks?.layerPauses?.mapNotNull { (k, words) -> (k - 1).takeIf { it in 0 until n }?.let { it to words } } ?: emptyList())
        for ((l, words) in marks) {
            val x = l * lane
            drawLine(Ink.text, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
            drawText(measurer.measure(words, label.copy(color = Ink.text)), topLeft = Offset(x + 4f, 2f))
        }
        // the check's problems in time: a short mark under each layer a problem names
        for (problem in j.checkProblems) {
            val z = Regex("layer at z ([0-9.]+)").find(problem)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val l = (0 until n).firstOrNull { p.layerTop(it) >= z - 1e-6 } ?: continue
            drawRect(Ink.warn, Offset(l * lane, h - 8f), Size(maxOf(lane, 2f), 8f))
        }
        drawText(measurer.measure("layer 1", label), topLeft = Offset(0f, h + 2f))
        val t = measurer.measure("layer $n · ${JobState.mm(p.layerTop(n - 1))} mm", label); drawText(t, topLeft = Offset(w - t.size.width, h + 2f))
    }
}
