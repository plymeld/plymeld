package plymeld.core.decide

import plymeld.config.UserDirs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The persistent search log (how often the search short-circuits):
 * every technique decision appends one line, tab-separated: the date (UTC, to the
 * second), the model (the object's name), the stop reason (TechniqueExplain.StopReason), the time the search ran, and the
 * rounds. Where it goes is a setting, `method.search.log.file` (`method.search.log.enabled` switches it off): unset or
 * empty, `search.log` in plymeld's state folder ([UserDirs.state]); a leading `~/` is the user's home directory, as in
 * a config file's path. Whether a satisfaction threshold is needed, and its value, are read off this log over real use
 * (the regression run summarizes the test models' lines), never guessed.
 */
object SearchLog {
    /** The date of a line: now, UTC, to the second (e.g. 2026-10-09T14:03:21Z). */
    fun now(): String = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))

    /** [path] with a leading `~/` (or `~`) replaced by the user's home directory; blank: `search.log` in [UserDirs.state]. */
    fun resolve(path: String, dirs: () -> UserDirs = UserDirs::current): Path = when {
        path.isBlank() -> dirs().state.resolve("search.log")
        path == "~" || path.startsWith("~/") -> Path.of(System.getProperty("user.home") + path.substring(1))
        else -> Path.of(path)
    }

    /**
     * Appends [line] to the log at [path] (made, with its directories, when missing). A log that cannot be written is
     * not worth refusing the print for: the note returned says what went wrong and where, and the caller reports it.
     */
    fun append(path: String, line: String): String? {
        val p = resolve(path)
        return try {
            p.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(p, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            null
        } catch (e: java.io.IOException) {
            "search log not written to $p (method.search.log.file): ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
