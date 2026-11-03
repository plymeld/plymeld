package plymeld.printer

/** plymeld's word for what a printer is doing; the printer's own word rides beside it in [PrinterState.rawStatus]. */
enum class Status(val words: String) {
    PRINTING("printing"), PAUSED("paused"), IDLE("idle"), FINISHED("finished"), CANCELLED("canceled"), ERROR("error"), BUSY("busy"),
    /** A word the adapter's table does not know: kept raw, never guessed at. */
    UNKNOWN("unknown"),
}

/** One heater as the printer reports it: its [name] (`T1`, `bed`), actual and target temperatures in C. */
data class Heater(val name: String, val actualC: Reported<Double>, val targetC: Reported<Double>) {
    fun text(): String = "${actualC.text(::c)}/${targetC.text(::c)} C"
    private fun c(v: Double) = "%.0f".format(java.util.Locale.ROOT, v)
}

/**
 * A printer's state, typed, as one state request reported it. Every field is
 * the printer's own figure or not reported: nothing here is derived in place of a missing figure. An inference the
 * adapter can make (the Creator 5's active tool from the nozzle targets) is a line of [notes], not a value.
 */
class PrinterState(
    /** The machine key (hardware.machine.<key>): the printer's name everywhere; never its model. */
    val key: String,
    /** Which protocol and request answered, and what the printer says it is (model, firmware), when the answer carries it. */
    val source: String,
    val status: Status,
    /** The printer's own status word, as sent. */
    val rawStatus: String?,
    /** The file printing (or last printed, as the printer keeps it). */
    val job: Reported<String>,
    /** 0..1, the printer's own figure. */
    val progress: Reported<Double>,
    val layer: Reported<Int>,
    val layers: Reported<Int>,
    val elapsedS: Reported<Double>,
    /** The printer's own estimate of the time left; what it is based on is the printer's business. */
    val remainingS: Reported<Double>,
    /** The tool printing, 1-based as the config's `hardware.tool.<key>.index`. */
    val activeTool: Reported<Int>,
    /** The nozzles in tool order (`T1`...); empty when the answer names none. */
    val nozzles: List<Heater>,
    val bed: Reported<Heater>,
    /** The speed override, 1.0 = 100 %. */
    val speedFactor: Reported<Double>,
    /** The flow override, 1.0 = 100 %. */
    val flowFactor: Reported<Double>,
    /** What is loaded, when the same answer carries it; null when the state request does not say. */
    val loadout: ReportedLoadout?,
    /** What the adapter noticed or inferred, each said in words. */
    val notes: List<String>,
) {
    /** The state as `plymeld printer status` prints it: one line per field, "not reported" where the printer gave none. */
    fun text(): String = buildString {
        appendLine("printer: $key ($source)")
        appendLine("status: ${status.words}${rawStatus?.let { if (it != status.words) " (the printer's word: \"$it\")" else "" } ?: ""}")
        appendLine("job: ${job.text()}")
        appendLine("progress: ${progress.text { "%.1f%%".format(java.util.Locale.ROOT, it * 100) }}")
        appendLine("layer: ${layer.text()} of ${layers.text()}")
        appendLine("elapsed: ${elapsedS.text(::hms)}")
        appendLine("remaining: ${remainingS.text(::hms)}${if (remainingS is Reported.Value) " (the printer's own estimate)" else ""}")
        appendLine("active tool: ${activeTool.text { "T$it" }}")
        appendLine("nozzles: ${if (nozzles.isEmpty()) "not reported" else nozzles.joinToString(", ") { "${it.name} ${it.text()}" }}")
        appendLine("bed: ${bed.text { it.text() }}")
        appendLine("speed: ${speedFactor.text(::pct)}")
        appendLine("flow: ${flowFactor.text(::pct)}")
        loadout?.let { l ->
            for (s in l.slots) {
                val where = if (s.feeds == ReportedSlot.Feeds.TOOL) "tool ${s.position}" else "slot ${s.position}"
                appendLine("loaded in $where: " + when {
                    s.loaded == false -> "empty"
                    else -> listOf(s.material ?: "no material", s.color ?: "no color").joinToString(" ") + (if (s.loaded == null) " (loaded? not reported)" else "")
                })
            }
            l.notes.forEach { appendLine("note: $it") }
        }
        notes.forEach { appendLine("note: $it") }
    }

    private fun pct(v: Double) = "%.0f%%".format(java.util.Locale.ROOT, v * 100)
    private fun hms(s: Double): String {
        val t = s.toLong(); val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, sec) else "%d:%02d".format(java.util.Locale.ROOT, m, sec)
    }
}

/** A file on the printer, as its list names it. */
data class PrinterFile(val name: String, val sizeBytes: Reported<Long>, val modifiedEpochS: Reported<Double>)

/**
 * The outcome of an upload: [confirmed] = whether the printer's own file list gained [name] afterwards (null where the
 * API has no list to ask). A reply code is never the confirmation.
 */
data class Upload(val name: String, val bytes: Int, val confirmed: Boolean?)

/** A control call's outcome: [sent], what went to the printer in words, and the state read back afterwards where the adapter can. */
class Done(val sent: String, val readBack: PrinterState?)

/** One JPEG from the printer's camera and where it came from (the URL, never a credential). */
class CameraFrame(val jpeg: ByteArray, val source: String)
