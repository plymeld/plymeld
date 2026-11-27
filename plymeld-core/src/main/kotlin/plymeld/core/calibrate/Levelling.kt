package plymeld.core.calibrate

/**
 * What every calibration plate says of levelling the bed, on its page and in the command's output. Never an instruction to
 * level: a plate's readings do not depend on a levelled bed beyond what the reader already prints with. It says what the
 * file itself does where it levels (the machine's `gcode.level`) or was made with `--no-level`; where the file does not
 * level and the printer's connection can level as a print starts, it names that as an option; else nothing.
 */
object Levelling {
    /** [levels]: the file runs the machine's levelling; [skipped]: `--no-level` left it out; [onStart]: the printer can level as a print starts. */
    class State(val levels: Boolean, val skipped: Boolean, val onStart: Boolean)

    /** The page's sentence, or null when there is nothing to say. */
    fun page(s: State): String? = when {
        s.levels -> "This file runs the printer's bed levelling (`hardware.machine.<key>.gcode.level`) before the plate, once it has heated; `--no-level` leaves it out."
        s.skipped -> "This file does not level the bed: it was made with `--no-level`."
        s.onStart -> "The printer can level the bed as the print starts, should you want it: `plymeld printer send --print --level`."
        else -> null
    }

    /** The command's line, or null when there is nothing to say. */
    fun said(s: State): String? = when {
        s.levels -> "levelling: the file runs the machine's bed levelling before the plate (hardware.machine.<key>.gcode.level); --no-level leaves it out"
        s.skipped -> "levelling: left out of the file (--no-level)"
        s.onStart -> "levelling: optional, as the print starts: `plymeld printer send --print --level`"
        else -> null
    }
}
