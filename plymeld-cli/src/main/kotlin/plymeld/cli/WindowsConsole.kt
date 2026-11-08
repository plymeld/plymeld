package plymeld.cli

/**
 * A Windows console shows plymeld's text in its own code page (437 by default in many places), while plymeld writes
 * UTF-8: its chooser's pointer and keys (❯, ↑ · ↓), °, ², → came out as `Γ¥»`, `Γåæ`. So, writing to a console window on
 * Windows, the console is switched to UTF-8 (code page 65001) for the run and given its own back when plymeld exits.
 * Nothing changes when the output goes to a file or a pipe (no console to show it), or off Windows.
 */
object WindowsConsole {
    /** The code page number in `chcp`'s answer ("Active code page: 437"), or null. */
    fun codePage(chcpOutput: String): Int? = Regex("(\\d+)\\s*\\.?\\s*$").find(chcpOutput.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** On Windows, writing to a console: the console in UTF-8 for this run, its own code page back at exit. */
    fun useUtf8() {
        if (!System.getProperty("os.name").lowercase().contains("windows") || System.console() == null) return
        val was = chcp() ?: return
        if (was == UTF8) return
        if (chcp(UTF8.toString()) == null) return
        Runtime.getRuntime().addShutdownHook(Thread { chcp(was.toString()) })
    }

    private const val UTF8 = 65001

    /** `chcp [page]` in this process's console (cmd.exe's built-in): the code page it reports, or null when it fails. */
    private fun chcp(page: String? = null): Int? = try {
        val p = ProcessBuilder(listOfNotNull("cmd.exe", "/c", "chcp", page))
            .redirectInput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) codePage(out) else null
    } catch (_: Exception) { null }
}
