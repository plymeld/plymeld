package plymeld.ui

import java.io.Writer
import java.util.concurrent.LinkedBlockingQueue

/**
 * The stage filled while a slice is written (the toolpaths come up as the writer lays them, not after):
 * a [Writer] the session's g-code goes through on its way to the file, its lines handed to a thread that feeds the
 * reader and, every [EVERY_MS] or so, puts what is read so far into the view. [finish] waits for the thread and shows
 * the whole file as read, which is what loading the file would give. The writer's own thread does nothing but queue.
 */
class StreamingLoad(private val viewer: ViewerState, private val name: String) : Writer() {
    private val queue = LinkedBlockingQueue<String>()
    private val pending = StringBuilder()
    private val parser = GcodeToolpaths.Parser()
    @Volatile private var result: Toolpaths? = null
    /** When the first layers reached the view (System.nanoTime), 0 before. */
    @Volatile var firstShownNanos = 0L; private set
    private val worker = Thread({
        var shownAt = System.nanoTime(); var shownLayers = 0
        while (true) {
            val chunk = queue.take()
            if (chunk === END) break
            parser.feed(chunk)
            // the view refreshed at a measured pace: a whole upload each time, so not for every layer
            if (parser.layers > shownLayers && System.nanoTime() - shownAt > EVERY_MS * 1_000_000L) {
                parser.snapshot()?.let { viewer.setPathsNow(it, name); if (firstShownNanos == 0L) firstShownNanos = System.nanoTime() }
                shownAt = System.nanoTime(); shownLayers = parser.layers
            }
        }
        val whole = parser.finish()
        viewer.setPathsNow(whole, name)
        result = whole
    }, "plymeld-stream").apply { isDaemon = true; start() }

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        var from = off
        val to = off + len
        for (i in off until to) if (cbuf[i] == '\n') { pending.appendRange(cbuf, from, i); queue.put(pending.toString()); pending.setLength(0); from = i + 1 }
        if (from < to) pending.appendRange(cbuf, from, to)
    }
    override fun flush() {}
    override fun close() { if (pending.isNotEmpty()) { queue.put(pending.toString()); pending.setLength(0) }; queue.put(END) }

    /** The whole file as read, once the writer is closed and the thread has shown it. */
    fun finish(): Toolpaths { close(); worker.join(); return result ?: error("the stream read nothing") }

    companion object {
        /** How often the view is refreshed while the file is written (ms). plymeld's choice: an upload is ~100 ms on the Benchy. */
        const val EVERY_MS = 300L
        private val END = String(CharArray(0))
    }
}

/** A writer that writes to two: the file, and the stream shown in the stage. */
class TeeWriter(private val a: Writer, private val b: Writer) : Writer() {
    override fun write(cbuf: CharArray, off: Int, len: Int) { a.write(cbuf, off, len); b.write(cbuf, off, len) }
    override fun flush() { a.flush(); b.flush() }
    override fun close() { a.close(); b.close() }
}
