package plymeld.printer

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where one credential's value comes from. The config
 * names sources, never values: `connection.credential.<name>.env` (an environment variable) and
 * `connection.credential.<name>.file` (a local secrets file). The sources are asked in order and the first value
 * wins; the environment is asked before the file. The OS keychain is a third source to add
 * here when its library is chosen; nothing else changes.
 */
interface CredentialSource {
    /** The source in words for a refusal: "environment variable C5_CHECK_CODE", "file ~/.config/plymeld/secrets.toml". Never the value. */
    val words: String
    /** The value, or null when this source has none (an unset or empty variable, a file without the line). */
    fun value(): String?
}

/** An environment variable; empty counts as unset. */
class EnvironmentCredential(private val variable: String, private val env: (String) -> String?) : CredentialSource {
    override val words get() = "environment variable $variable"
    override fun value(): String? = env(variable)?.takeIf { it.isNotEmpty() }
}

/**
 * One line of a local secrets file: a TOML-shaped file of `name = "value"` lines (double or single quotes, `#`
 * comments, blank lines; nothing else), read with plymeld's own few lines rather than the config reader, which would
 * hold it to the schema. A missing file or a line of another shape is refused naming the file (and the line), so a
 * typo never reads as "no credential".
 */
class FileCredential(private val path: Path, private val name: String) : CredentialSource {
    override val words get() = "file $path (its '$name' line)"
    override fun value(): String? {
        check(Files.isRegularFile(path)) { "credential '$name': the secrets file $path does not exist" }
        var found: String? = null
        Files.readAllLines(path).forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val m = LINE.matchEntire(line) ?: error("credential '$name': line ${i + 1} of the secrets file $path is not `name = \"value\"`")
            if (m.groupValues[1] == name) found = m.groupValues[2].ifEmpty { m.groupValues[3] }
        }
        return found?.takeIf { it.isNotEmpty() }
    }

    /**
     * Sets this credential to [value]: its line replaced where the file has one, else added at the end, every other line
     * kept as it was. A file that does not exist yet is created (its folder too), readable by its owner only where the
     * system allows. Refused, nothing written, for a value the file's lines cannot hold (empty, a line break, or both kinds
     * of quote) and for a file with a line of another shape. Returns true when a line was replaced, false when one was added.
     */
    fun write(value: String): Boolean {
        require(value.isNotEmpty()) { "credential '$name': the value is empty" }
        require('\n' !in value && '\r' !in value) { "credential '$name': the value holds a line break, which a secrets file's line cannot" }
        require(!('"' in value && '\'' in value)) { "credential '$name': the value holds both \" and ', which a secrets file's line cannot" }
        val line = if ('"' in value) "$name = '$value'" else "$name = \"$value\""
        val exists = Files.isRegularFile(path)
        val lines = if (exists) Files.readAllLines(path) else emptyList()
        var replaced = false
        val out = lines.mapIndexed { i, raw ->
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapIndexed raw
            val m = LINE.matchEntire(t) ?: error("credential '$name': line ${i + 1} of the secrets file $path is not `name = \"value\"`; nothing written")
            if (m.groupValues[1] == name) { replaced = true; line } else raw
        } + (if (replaced) emptyList() else listOf(line))
        if (!exists) {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            // created owner-only before the value goes in, where the file system has POSIX permissions
            try { Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))) }
            catch (_: UnsupportedOperationException) { Files.createFile(path) }
        }
        Files.writeString(path, out.joinToString("\n", postfix = "\n"))
        return replaced
    }

    companion object {
        private val LINE = Regex("""([A-Za-z0-9_.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')\s*(?:#.*)?""")

        /** [path] with a leading `~` or `~/` as [home] (the same rule as the config's own paths). */
        fun expandHome(path: String, home: String = System.getProperty("user.home")): Path =
            Path.of(if (path == "~" || path.startsWith("~/")) home + path.substring(1) else path)
    }
}

object Credentials {
    /** The first value the [sources] give, in their order; null when none has one. */
    fun resolve(sources: List<CredentialSource>): String? = sources.firstNotNullOfOrNull { it.value() }
}
