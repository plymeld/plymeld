package plymeld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale


/**
 * The Layers tab's controls, on the stage (the toolpaths after a slice): the layers shown
 * (a range, or one at a time), the move slider through the top layer, what colors the lines (the filaments' colors
 * by tool, the features, a ramp of speed, line width, layer height, or fan), the travels, and the legend of the
 * coloring in force. Every control is the viewer's; nothing here reads the file itself.
 */
@Composable
fun LayersBar(j: JobState, modifier: Modifier) {
    val s = j.viewer
    val p = s.paths ?: return
    val n = p.layers
    Column(modifier.background(Ink.glass, RoundedCornerShape(10.dp)).border(1.dp, Ink.line, RoundedCornerShape(10.dp)).padding(12.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // the layers: a range, or one at a time
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (s.singleLayer) "layer ${s.layerHi + 1} of $n · z ${f2(p.layerTop(s.layerHi))} mm" else "layers ${s.layerLo + 1}–${s.layerHi + 1} of $n · top z ${f2(p.layerTop(s.layerHi))} mm",
                color = Ink.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(300.dp))
            if (n > 1) Box(Modifier.weight(1f)) {
                if (s.singleLayer) Slider(value = s.layerHi.toFloat(), valueRange = 0f..(n - 1).toFloat(), onValueChange = { s.setLayers(0, Math.round(it)) }, colors = sliderColors())
                else RangeSlider(value = s.layerLo.toFloat()..s.layerHi.toFloat(), valueRange = 0f..(n - 1).toFloat(),
                    onValueChange = { r -> s.setLayers(Math.round(r.start), Math.round(r.endInclusive)) }, colors = sliderColors())
            }
            Toggle("one layer", s.singleLayer) { s.useSingleLayer(!s.singleLayer) }
            Toggle("travels", s.showTravels) { s.useTravels(!s.showTravels) }
        }
        // the moves of the top layer, one by one
        val moves = s.topLayerMoves
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val seg = s.currentSegment()
            val info = if (seg == null) "nothing printed on this layer" else buildString {
                append(p.features[p.feature[seg].toInt()].ifEmpty { "unnamed" })
                append(" · ").append("%.0f".format(Locale.ROOT, p.speed[seg].toDouble())).append(" mm/s")
                if (!p.width[seg].isNaN()) append(" · ").append(f2(p.width[seg].toDouble())).append(" mm wide")
                if (!p.height[seg].isNaN()) append(" × ").append(f2(p.height[seg].toDouble())).append(" mm")
                append(" · T").append(p.tool[seg].toInt())
            }
            Text("move ${s.topMoves ?: moves} / $moves · $info", color = Ink.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(520.dp), maxLines = 1)
            Box(Modifier.weight(1f)) {
                Slider(value = (s.topMoves ?: moves).toFloat(), valueRange = 0f..maxOf(1, moves).toFloat(), onValueChange = { s.moveTo(Math.round(it)) }, colors = sliderColors())
            }
        }
        // what colors the lines, and the legend
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("color by", color = Ink.muted, fontSize = 11.sp)
            for (by in listOf(ColorBy.TOOL, ColorBy.FEATURE, ColorBy.SPEED, ColorBy.HEIGHT, ColorBy.WIDTH, ColorBy.FAN)) {
                val on = s.colorBy == by
                Text(by.label.lowercase(), color = if (on) Ink.text else Ink.muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Ink.surface3 else Color.Transparent)
                        .clickable { if (by == ColorBy.TOOL) j.colorByTool() else s.useColorBy(by) }.padding(8.dp, 3.dp))
            }
            Spacer(Modifier.width(8.dp))
            s.legend?.let { lg ->
                if (lg.isRamp) {
                    Text("${lg.format(lg.lo)} ${lg.by.unit}", color = Ink.muted, fontSize = 11.sp)
                    Box(Modifier.width(140.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient((0..8).map { rgb(Coloring.ramp(it / 8.0)) })))
                    Text("${lg.format(lg.hi)} ${lg.by.unit}", color = Ink.muted, fontSize = 11.sp)
                    if (lg.unknown > 0) Text("· ${"%,d".format(lg.unknown)} lines without a value: gray", color = Ink.muted, fontSize = 11.sp)
                } else FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {   // wraps: twelve swatches overflowed the bar (live capture)
                    for ((name, c) in lg.swatches.take(12)) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(rgb(c), RoundedCornerShape(3.dp))); Spacer(Modifier.width(4.dp)); Text(name, color = Ink.muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) Ink.text else Ink.muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Ink.surface3 else Color.Transparent).border(1.dp, Ink.line, RoundedCornerShape(6.dp)).clickable { onClick() }.padding(8.dp, 3.dp))
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Ink.accent, activeTrackColor = Ink.accent, inactiveTrackColor = Ink.surface3)

private fun rgb(c: Int) = Color(0xFF000000 or c.toLong())
private fun f2(v: Double) = "%.2f".format(Locale.ROOT, v)
