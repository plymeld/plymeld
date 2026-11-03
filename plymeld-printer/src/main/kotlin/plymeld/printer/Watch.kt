package plymeld.printer

import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Watching a print: the state polled every [Duration],
 * the first full state printed, then one timestamped line per line of the state that changed, until the print ends
 * or the user stops it. Polling only: the HTTP families push nothing; Moonraker's WebSocket subscription can feed the
 * same loop later.
 */
object Watch {
    /**
     * How often the state is asked for when `--every` is not given: 5 s. plymeld's choice: a layer takes
     * longer than that on most prints, so nothing is missed, and one small request every five seconds is no load on a
     * printer's web server. Not measured against anything.
     */
    val DEFAULT_EVERY: Duration = Duration.ofSeconds(5)

    /** The print has ended: finished, canceled, or in error; or idle again after it was seen printing (or paused). */
    fun ended(now: Status, sawPrinting: Boolean): Boolean =
        now == Status.FINISHED || now == Status.CANCELLED || now == Status.ERROR || (now == Status.IDLE && sawPrinting)

    /**
     * Polls [printer] every [every] and hands [out] the first state in full, then each later state's lines that are
     * not in the previous one (a changed field reads as a new line; a line that merely disappears is not reported),
     * each prefixed with [stamp]'s time; stops when [ended] says the print has, or after [maxPolls] polls (a cap for
     * tests; the command runs until the print ends or Ctrl+C). Returns the last state read. [sleep] waits between
     * polls (a test substitutes it).
     */
    fun run(printer: Printer, every: Duration, out: (String) -> Unit, stamp: () -> String = ::now, sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) }, maxPolls: Int = Int.MAX_VALUE): PrinterState {
        var previous: List<String>? = null
        var sawPrinting = false
        var last: PrinterState
        var polls = 0
        while (true) {
            last = printer.state()
            polls++
            val lines = last.text().trimEnd().lines()
            val before = previous
            if (before == null) lines.forEach(out)
            else for (l in lines) if (l !in before) out("${stamp()}  $l")
            previous = lines
            if (last.status == Status.PRINTING || last.status == Status.PAUSED) sawPrinting = true
            if (ended(last.status, sawPrinting)) { out("${stamp()}  the print has ended: ${last.status.words}"); return last }
            if (polls >= maxPolls) return last
            sleep(every)
        }
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private fun now(): String = LocalTime.now().format(TIME)
}
