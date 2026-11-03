package plymeld.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plymeld.config.ConfigFiles
import plymeld.config.Schema
import java.nio.file.Path

/**
 * Where a value came from, as one word on a chip (six words, six colors, the chain one
 * click in): bundled (plymeld's own files), placeholder (a bundled value the schema calls a stand-in or a placeholder;
 * its chip is dashed), a file's line (`02_c5.toml:40`), decided (the job's plan file), kept (`!value`), this run
 * (`-cv`). Calibration's word waits on a calibration that writes a file plymeld can tell apart.
 */
internal enum class Provenance {
    BUNDLED, PLACEHOLDER, FILE, DECIDED, KEPT, THIS_RUN;

    val color: Color get() = when (this) {
        BUNDLED -> Ink.muted; PLACEHOLDER -> Ink.warn; FILE -> Ink.accent; DECIDED -> Ink.ok; KEPT -> Ink.hot; THIS_RUN -> Ink.text
    }

    companion object {
        /** The word for [e] and the chip's label (a file's chip reads its name and line); [planName] is the job's plan file. */
        fun of(e: ConfigFiles.Entry, planName: String?, decl: Schema.Decl? = Schema.find(e.path)): Pair<Provenance, String> {
            val src = e.source
            val line = Regex("^\\d+").find(src.substringAfterLast(':', ""))?.value ?: ""
            val file = runCatching { Path.of(src.substringBeforeLast(':')).fileName?.toString() }.getOrNull() ?: src
            return when {
                e.keep -> KEPT to "kept"
                src.startsWith("command line") -> THIS_RUN to "this run"
                src.startsWith(ConfigFiles.BUNDLED_SOURCE) ->
                    if (decl?.meaning?.let { "placeholder" in it || "stand-in" in it } == true) PLACEHOLDER to "placeholder" else BUNDLED to "bundled"
                planName != null && file == planName -> DECIDED to "decided"
                else -> FILE to (if (line.isEmpty()) file else "$file:$line")
            }
        }
    }
}

/** The chip for one config entry; a placeholder's is dashed (stand-ins are visible wherever a number is). */
@Composable
internal fun ProvenanceChip(e: ConfigFiles.Entry, j: JobState, decl: Schema.Decl? = Schema.find(e.path)) {
    val planName = remember(j.config) { j.planFile?.fileName?.toString() }
    val (p, label) = Provenance.of(e, planName, decl)
    val dashed = p == Provenance.PLACEHOLDER
    Text(label, color = p.color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.drawBehind {
        val stroke = Stroke(width = 1.5f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5f, 4f)) else null)
        drawRoundRect(p.color.copy(alpha = 0.75f), cornerRadius = CornerRadius(5.dp.toPx()), style = stroke)
    }.padding(6.dp, 2.dp))
}
