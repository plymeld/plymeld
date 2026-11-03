package plymeld.config

import java.nio.file.Path

/**
 * The config sources of one run, layered: config files in the
 * order given, then `--config-value`s in order. For the same path the later source wins, except that two
 * files of one numbered group may not set the same path ([ConfigFiles.readFiles]). A value
 * set for an object or part wins over the general one whatever the order — see [Config.scoped].
 */
class ConfigStack(entries: List<ConfigFiles.Entry>) {
    /** Every entry as given, in order (for a stack built from this one with some left out). */
    val all: List<ConfigFiles.Entry> = entries
    /** Each path's value from the last source that set it, in first-seen path order. */
    val effective: Map<String, ConfigFiles.Entry> = LinkedHashMap<String, ConfigFiles.Entry>().also { m -> for (e in entries) { m.remove(e.key); m[e.key] = e } }
    /** Every entry that was overridden by a later source at the same path (for `config explain`). */
    val overridden: List<ConfigFiles.Entry> = entries.filter { effective[it.key] !== it }

    fun entry(path: List<String>): ConfigFiles.Entry? = effective[ConfigFiles.pathText(path)]

    companion object {
        /** The stack for `--config-file` arguments (files or globs) and `--config-value` arguments, after plymeld's bundled files. */
        fun load(fileArgs: List<String>, valueArgs: List<String>, cwd: Path = Path.of("").toAbsolutePath(), bundled: Boolean = true): ConfigStack =
            ConfigStack((if (bundled) ConfigFiles.bundled() else emptyList()) +
                ConfigFiles.readFiles(fileArgs, cwd) + valueArgs.map { ConfigFiles.value(it) })
    }
}
